#!/usr/bin/env python3
"""Feasibility probe: can the network predict where its own static evaluation is wrong?

For self-play positions of a network (packed records: the score is that network's search at ~6000 nodes) the
error is search - static eval, measured as a win probability (--space wdl, sigmoid(cp / 230) as the trainer) or in
centipawns (--space cp). Two heads on the frozen network's own SCReLU activations learn to predict it: a linear one
(one more output per bucket, ~1 % extra cost at play time) and, as an upper bound, one with a 16-neuron hidden
layer. On held-out games (the end of the file) they are compared with simple rules: |static eval|, p(1 - p) of the
static eval's win probability, piece count, and a small regression on those.

Reported per predictor: Spearman rank correlation with |error| and the share of large errors (wdl: > 0.10,
cp: > 100) found in the 10 % / 20 % of positions it ranks most uncertain (a random ranking finds 10 % / 20 %).

With --export (and --net, the network's .bin) the 16-neuron head (wdl space) is written as the file
core.nnue.UncertaintyHead reads (core/nnue-unc.bin), bound to the network by the CRC32 of its file.

Usage (PyTorch venv):
  python scripts/nnue/uncertainty_probe.py --pt E:/Chess-Data/gpugen/cand01/candidate.pt \\
      --data E:/Chess-Data/gpugen/sp-gen02-10190721.bin \\
      --net E:/Chess-Data/gpugen/cand01/candidate.bin --export src/main/resources/core/nnue-unc.bin
"""
import argparse
import struct
import sys
import zlib
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import train_gpu as tg  # noqa: E402

BATCH = 16384


def batches(data: np.ndarray, device, kb, shuffle_seed=None):
    order = np.arange(0, len(data), BATCH)
    if shuffle_seed is not None:
        np.random.default_rng(shuffle_seed).shuffle(order)
    for lo in order:
        yield tg.Batch(np.asarray(data[lo:lo + BATCH]), device, kb)


def activations(net: tg.Net, batch: tg.Batch):
    """The frozen network's SCReLU activations (us, them), output bucket, static eval in cp (side to move)."""
    rows = net.ft.weight.shape[0]
    x = torch.zeros(batch.indices.shape[0], rows, device=batch.indices.device, dtype=torch.float16)
    x.scatter_(1, batch.indices, 1.0)
    x[:, tg.PAD] = 0
    with torch.autocast("cuda", dtype=torch.float16):
        acc = x @ net.ft.weight
    v = torch.clamp(acc.float() + net.b1, 0, 1) ** 2
    b = v.shape[0] // 2
    ob = tg.output_bucket(batch.count, net.buckets)
    w = net.w2[ob]
    static = ((v[:b] * w[:, 0]).sum(1) + (v[b:] * w[:, 1]).sum(1) + net.b2[ob]) * tg.SCALE
    return v[:b], v[b:], ob, static


def rank(x: np.ndarray) -> np.ndarray:
    r = np.empty(len(x))
    r[np.argsort(x, kind="stable")] = np.arange(len(x))
    return r


def report(name: str, pred: np.ndarray, err: np.ndarray, threshold: float) -> None:
    rho = np.corrcoef(rank(pred), rank(err))[0, 1]
    big = err > threshold
    order = np.argsort(-pred)
    found = [big[order[:int(len(pred) * f)]].sum() / max(1, big.sum()) for f in (0.1, 0.2)]
    print(f"  {name:36s} spearman {rho:6.3f}   large errors in top 10 %: {found[0]:5.1%}   top 20 %: {found[1]:5.1%}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pt", required=True, help="float model (.pt) of the network that played the data")
    ap.add_argument("--data", required=True)
    ap.add_argument("--train", type=int, default=20_000_000)
    ap.add_argument("--test", type=int, default=2_000_000)
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--hidden", type=int, default=256)
    ap.add_argument("--buckets", type=int, default=8)
    ap.add_argument("--space", choices=["wdl", "cp"], default="wdl")
    ap.add_argument("--k", type=float, default=230.0)
    ap.add_argument("--net", help="the network's exported .bin (its CRC32 binds the head to it)")
    ap.add_argument("--export", help="write the 16-neuron head here (needs --net, --space wdl)")
    args = ap.parse_args()

    def error(search, static):
        if args.space == "cp":
            return (search - static).abs()
        return (torch.sigmoid(search / args.k) - torch.sigmoid(static / args.k)).abs()

    def target_of(err):
        return torch.log1p(err / 50) if args.space == "cp" else err * 10

    tg.configure("plain")
    device = torch.device("cuda")
    kb = torch.tensor(tg.KING_BUCKETS, device=device)
    net = tg.Net(args.hidden, args.buckets).to(device)
    net.load_state_dict(torch.load(args.pt, map_location=device))
    net.eval()
    for p in net.parameters():
        p.requires_grad_(False)
    raw = np.memmap(args.data, dtype=np.uint8, mode="r")
    data = raw[:len(raw) // tg.RECORD * tg.RECORD].reshape(-1, tg.RECORD)
    train, test = data[:args.train], data[-args.test:]  # the file is written game by game: the tail = other games

    wu = torch.zeros(args.buckets, 2, args.hidden, device=device, requires_grad=True)
    bu = torch.zeros(args.buckets, device=device, requires_grad=True)
    mlp = torch.nn.Sequential(torch.nn.Linear(2 * args.hidden + args.buckets, 16), torch.nn.ReLU(),
                              torch.nn.Linear(16, 1)).to(device)
    opt = torch.optim.Adam([wu, bu, *mlp.parameters()], lr=1e-3)

    def heads(us, them, ob):
        w = wu[ob]
        lin = (us * w[:, 0]).sum(1) + (them * w[:, 1]).sum(1) + bu[ob]
        onehot = torch.nn.functional.one_hot(ob, args.buckets).float()
        return lin, mlp(torch.cat([us, them, onehot], dim=1)).squeeze(1)
    for epoch in range(args.epochs):
        total, n = 0.0, 0
        for batch in batches(train, device, kb, shuffle_seed=epoch):
            with torch.no_grad():
                us, them, ob, static = activations(net, batch)
                target = target_of(error(batch.score, static))
            lin, deep = heads(us, them, ob)
            loss = torch.mean((lin - target) ** 2) + torch.mean((deep - target) ** 2)
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item() * len(target)
            n += len(target)
        print(f"epoch {epoch + 1}: train mse {total / n:.4f}", flush=True)

    lins, deeps, errs, statics, pieces, keeps = [], [], [], [], [], []
    with torch.no_grad():
        for batch in batches(test, device, kb):
            us, them, ob, static = activations(net, batch)
            lin, deep = heads(us, them, ob)
            lins.append(lin.cpu().numpy())
            deeps.append(deep.cpu().numpy())
            errs.append(error(batch.score, static).cpu().numpy())
            statics.append(static.abs().cpu().numpy())
            pieces.append(batch.count.cpu().numpy())
            keeps.append((batch.score.abs() < 3000).cpu().numpy())  # mate / tablebase scores are no eval errors
    keep = np.concatenate(keeps)
    lin, deep, err, st, pc = (np.concatenate(a)[keep] for a in (lins, deeps, errs, statics, pieces))
    pc = pc.astype(float)
    big = 100 if args.space == "cp" else 0.10
    p = 1 / (1 + np.exp(-st / args.k))
    slope = p * (1 - p)
    print(f"held-out ({args.space}): {len(err)} positions, |search - static| mean {err.mean():.3f}, "
          f"median {np.median(err):.3f}, large (> {big}): {(err > big).mean():.1%}")
    # a small hand-made model: least squares on the simple features (fit on the first half of the test set, all
    # predictors scored on the second half)
    x = np.stack([np.ones_like(st), st / 100, (st / 100) ** 2, slope * 4, pc / 32, (pc / 32) ** 2,
                  slope * pc / 8], axis=1)
    h = len(err) // 2
    coef, *_ = np.linalg.lstsq(x[:h], err[:h], rcond=None)
    for name, pred in (("learned head, linear (+1 % cost)", lin), ("learned head, 16 hidden (bound)", deep),
                       ("|static eval|", st), ("p(1 - p) of static eval", slope), ("piece count", pc),
                       ("regression on all of these", x @ coef)):
        report(name, pred[h:], err[h:], big)

    if args.export:
        if not args.net or args.space != "wdl":
            raise SystemExit("--export needs --net and --space wdl")
        crc = zlib.crc32(Path(args.net).read_bytes())
        l1, l2 = mlp[0], mlp[2]
        with open(args.export, "wb") as f:
            f.write(b"ALNU")
            f.write(struct.pack("<iIiii", 1, crc, args.hidden, args.buckets, l1.out_features))
            for t in (l1.weight, l1.bias, l2.weight.reshape(-1), l2.bias):
                f.write(t.detach().float().cpu().numpy().astype("<f4").tobytes())
        print(f"head written to {args.export} (network CRC32 {crc:08x})")


if __name__ == "__main__":
    main()
