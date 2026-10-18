#!/usr/bin/env python3
"""GPU trainer for Alieknek's NNUE: king-bucketed (768 x K -> H) x 2 -> 1 SCReLU networks, file format v3.

Same maths as the Java trainer (core.nnue.Trainer), so its results stay comparable:
  acc = b1 + sum of the active features' weights (per perspective), v = clamp(acc, 0, 1)^2,
  out = v_us . w2[bucket, 0] + v_them . w2[bucket, 1] + b2[bucket]   (output bucket by piece count),
  loss = mean((sigmoid(out * 400 / K) - (lambda * wdl / 2 + (1 - lambda) * sigmoid(score / K)))^2),
  Adam, learning rate decaying 10x over the run, weights clipped to +-1.98 (int16-safe after quantisation).

New: input (king) buckets. A perspective's features depend on the square of its own king (8 buckets that tell
castled kings apart, see KING_BUCKETS) and the board is mirrored horizontally while that king is on the e-h
files. A factoriser (one shared 768-input layer added to every bucket during training, merged on export)
lets the buckets learn from the data each of them shares.

Input: 32-byte records from core.tools.PackData (one or more .bin files, loaded into RAM).
Output: a v3 network file for the engine (-Dcore.nnueFile=... or src/main/resources/core/nnue.bin).

Speed: the feature transformer runs as a dense 0/1 matrix times the weights in half precision on the tensor
cores (with loss scaling). PyTorch's sparse EmbeddingBag needs a sort of ~2M indices per batch for its gradient
and was half as fast; it remains the CPU path.

Usage (PyTorch with CUDA, e.g. E:/Tools/torch-venv):
  python scripts/nnue/train_gpu.py --data E:/Chess-Data/packed/all.bin --out E:/Chess-Data/nets/kb256.bin \\
      --hidden 256 --epochs 15
"""
import argparse
import struct
import time
from pathlib import Path

import numpy as np
import torch

RECORD = 32
QA, QB, SCALE = 255, 256, 400
CLIP = 1.98
INPUTS = 768
# Input bucket of the perspective's own king square (perspective frame, a1 = 0): the castled squares get their
# own buckets, the rest of the first two ranks share two, the third rank one, everything further up one.
KB8 = [
    0, 1, 2, 3, 3, 2, 1, 0,
    4, 4, 5, 5, 5, 5, 4, 4,
    6, 6, 6, 6, 6, 6, 6, 6,
] + [7] * 40
# Input layouts: (king bucket map, mirror, factoriser). "plain" is the Java trainer's 768-input network.
LAYOUTS = {"kb8": (KB8, True, True), "plain": ([0] * 64, False, False)}


def configure(layout):
    """Sets the module's input layout (king buckets, mirroring, factoriser) before nets and batches are made."""
    global KING_BUCKETS, NUM_KB, MIRROR, FACTORISER, FACTOR, PAD
    KING_BUCKETS, MIRROR, FACTORISER = LAYOUTS[layout] if isinstance(layout, str) else layout
    NUM_KB = max(KING_BUCKETS) + 1
    # One embedding table: NUM_KB * 768 bucketed rows, then the 768 factoriser rows (if any), then padding.
    FACTOR = NUM_KB * INPUTS
    PAD = FACTOR + (INPUTS if FACTORISER else 0)


configure("kb8")
HALF = True  # fp16 tensor-core feature transformer on CUDA (--fp32 turns it off)


def output_bucket(pieces: torch.Tensor, buckets: int) -> torch.Tensor:
    """core.nnue.Network.bucketFor: eight buckets of four pieces each."""
    if buckets <= 1:
        return torch.zeros_like(pieces)
    return torch.clamp((pieces - 2) * buckets // 32, 0, buckets - 1)


class Batch:
    """Decodes packed records on the GPU into feature indices for both perspectives."""

    def __init__(self, raw, device: torch.device, kb_map: torch.Tensor):
        if isinstance(raw, np.ndarray):
            raw = torch.from_numpy(np.ascontiguousarray(raw))
        rec = raw.to(device, non_blocking=True)                                  # [B, 32] uint8
        b = rec.shape[0]
        occ = rec[:, :8].to(torch.int64)
        occ = sum(occ[:, i] << (8 * i) for i in range(8))                        # [B] int64
        sq = torch.arange(64, device=device)
        bits = ((occ.unsqueeze(1) >> sq) & 1).bool()                             # [B, 64]
        squares = torch.where(bits, sq, torch.full_like(sq, 64))
        squares, _ = torch.sort(squares, dim=1)
        squares = squares[:, :32]                                                # occupied squares, 64 = none
        nib = rec[:, 8:24].to(torch.int64)
        pieces = torch.stack([nib & 15, nib >> 4], dim=2).reshape(b, 32)         # piece code per slot
        valid = squares < 64
        self.count = valid.sum(dim=1)
        score = (rec[:, 24].to(torch.int32) | (rec[:, 25].to(torch.int32) << 8)).to(torch.int16).float()
        wdl = rec[:, 26].float()
        stm = rec[:, 27].long()
        white_stm = stm == 0
        self.score = torch.where(white_stm, score, -score)                       # side to move's view
        self.wdl = torch.where(white_stm, wdl, 2 - wdl) / 2
        sqc = torch.clamp(squares, max=63)
        color = (pieces >= 6).long()
        ptype = pieces % 6
        feats, facts = [], []
        for persp in (0, 1):
            king_code = 5 + 6 * persp
            is_king = valid & (pieces == king_code)
            ksq = torch.where(is_king, sqc, torch.zeros_like(sqc)).sum(dim=1)    # exactly one own king
            k = ksq ^ (56 * persp)
            s = sqc ^ (56 * persp)
            if MIRROR:
                mirror = (k & 7) >= 4
                s = torch.where(mirror.unsqueeze(1), s ^ 7, s)
            rel = torch.where(color == persp, 0, 384)
            f = rel + ptype * 64 + s                                             # factoriser feature (0..767)
            bucket = kb_map[k].unsqueeze(1)
            feats.append(torch.where(valid, bucket * INPUTS + f, PAD))
            facts.append(torch.where(valid, FACTOR + f, PAD))
        both = [torch.cat([feats[i], facts[i]], dim=1) if FACTORISER else feats[i] for i in (0, 1)]
        # one lookup for both sides: side to move first, then the other side
        stm_white = white_stm.unsqueeze(1)
        self.indices = torch.cat([torch.where(stm_white, both[0], both[1]),
                                  torch.where(stm_white, both[1], both[0])], dim=0)  # [2B, 64]


class Net(torch.nn.Module):
    def __init__(self, hidden: int, buckets: int):
        super().__init__()
        self.hidden, self.buckets = hidden, buckets
        self.ft = torch.nn.EmbeddingBag(PAD + 1, hidden, mode="sum", padding_idx=PAD)
        self.b1 = torch.nn.Parameter(torch.zeros(hidden))
        self.w2 = torch.nn.Parameter(torch.zeros(buckets, 2, hidden))
        self.b2 = torch.nn.Parameter(torch.zeros(buckets))
        with torch.no_grad():
            # as the Java trainer: first layer uniform(-1, 1) * 0.5 / sqrt(32) (with a factoriser that is where it
            # starts, the buckets at 0: they learn the differences), output layer uniform(-1, 1) / sqrt(2H)
            self.ft.weight.zero_()
            init = slice(FACTOR, PAD) if FACTORISER else slice(0, FACTOR)
            self.ft.weight[init].uniform_(-1, 1).mul_(0.5 / 32 ** 0.5)
            self.w2.uniform_(-1, 1).mul_(1 / (2 * hidden) ** 0.5)

    def forward(self, batch: Batch) -> torch.Tensor:
        if batch.indices.is_cuda and HALF:
            # dense multi-hot input [2B, rows] x weights in fp16 on the tensor cores
            rows = self.ft.weight.shape[0]
            x = torch.zeros(batch.indices.shape[0], rows, device=batch.indices.device, dtype=torch.float16)
            x.scatter_(1, batch.indices, 1.0)
            x[:, PAD] = 0  # padding row contributes nothing
            with torch.autocast("cuda", dtype=torch.float16):
                acc = x @ self.ft.weight
            acc = acc.float() + self.b1
        else:
            acc = self.ft(batch.indices) + self.b1
        v = torch.clamp(acc, 0, 1) ** 2
        b = v.shape[0] // 2
        v_us, v_them = v[:b], v[b:]
        ob = output_bucket(batch.count, self.buckets)
        w = self.w2[ob]                                                         # [B, 2, H]
        return (v_us * w[:, 0]).sum(1) + (v_them * w[:, 1]).sum(1) + self.b2[ob]

    def clip(self):
        with torch.no_grad():
            # with a factoriser, bucket + factoriser stay within CLIP
            limit = CLIP / 2 if FACTORISER else CLIP
            self.ft.weight.clamp_(-limit, limit)
            self.b1.clamp_(-CLIP, CLIP)
            self.w2.clamp_(-CLIP, CLIP)

    def merged_first_layer(self) -> torch.Tensor:
        """[NUM_KB * 768, H]: each bucket's weights plus the shared factoriser."""
        w = self.ft.weight[:FACTOR].view(NUM_KB, INPUTS, self.hidden)
        if FACTORISER:
            w = w + self.ft.weight[FACTOR:PAD].unsqueeze(0)
        return w.reshape(NUM_KB * INPUTS, self.hidden)


class RowAdam(torch.optim.Optimizer):
    """Adam as the Java trainer does it: for the feature-transformer table ("rows" group) only the rows whose
    features occur in the batch are updated (moments and weights). Dense Adam would keep moving every other row on
    its stale momentum, which matters for rarely seen features (king buckets make most rows rare)."""

    def __init__(self, params, lr=1e-3, betas=(0.9, 0.999), eps=1e-8):
        super().__init__(params, dict(lr=lr, betas=betas, eps=eps, rows=False))

    @torch.no_grad()
    def step(self, closure=None):
        for group in self.param_groups:
            b1, b2 = group["betas"]
            for p in group["params"]:
                if p.grad is None:
                    continue
                st = self.state[p]
                if not st:
                    st["step"] = 0
                    st["m"] = torch.zeros_like(p)
                    st["v"] = torch.zeros_like(p)
                st["step"] += 1
                g, m, v = p.grad, st["m"], st["v"]
                bc1 = 1 - b1 ** st["step"]
                bc2 = 1 - b2 ** st["step"]
                if group["rows"]:
                    rows = (g != 0).any(dim=1).nonzero().squeeze(1)
                    gr = g.index_select(0, rows)
                    mr = m.index_select(0, rows).mul_(b1).add_(gr, alpha=1 - b1)
                    vr = v.index_select(0, rows).mul_(b2).addcmul_(gr, gr, value=1 - b2)
                    m.index_copy_(0, rows, mr)
                    v.index_copy_(0, rows, vr)
                    upd = (mr / bc1) / ((vr / bc2).sqrt_() + group["eps"])
                    p.index_add_(0, rows, upd, alpha=-group["lr"])
                else:
                    m.mul_(b1).add_(g, alpha=1 - b1)
                    v.mul_(b2).addcmul_(g, g, value=1 - b2)
                    p.addcdiv_(m / bc1, (v / bc2).sqrt_().add_(group["eps"]), value=-group["lr"])


def loss_fn(net: Net, batch: Batch, lam: float, k: float) -> torch.Tensor:
    pred = torch.sigmoid(net(batch) * SCALE / k)
    target = lam * batch.wdl + (1 - lam) * torch.sigmoid(batch.score / k)
    return torch.mean((pred - target) ** 2)


def export(net: Net, path: Path) -> None:
    """Quantised v3 file, as core.nnue.Network reads it."""
    with torch.no_grad():
        w1 = torch.round(net.merged_first_layer().clamp(-CLIP, CLIP) * QA).to(torch.int16).cpu().numpy()
        b1 = torch.round(net.b1 * QA).to(torch.int16).cpu().numpy()
        w2 = torch.round(net.w2 * QB).to(torch.int16).cpu().numpy().reshape(-1)  # row bucket * 2 + half
        b2 = torch.round(net.b2 * QA * QB).to(torch.int32).cpu().numpy()
    with open(path, "wb") as f:
        f.write(b"ALNN")
        f.write(struct.pack("<6i", 3, net.hidden, QA, QB, SCALE, net.buckets))
        f.write(struct.pack("<2i", NUM_KB, 1 if MIRROR else 0))
        f.write(bytes(KING_BUCKETS))
        f.write(w1.astype("<i2").tobytes())
        f.write(b1.astype("<i2").tobytes())
        f.write(w2.astype("<i2").tobytes())
        f.write(b2.astype("<i4").tobytes())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", nargs="+", required=True, help="packed .bin files (core.tools.PackData)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--hidden", type=int, default=256)
    ap.add_argument("--buckets", type=int, default=8, help="output buckets by piece count")
    ap.add_argument("--epochs", type=int, default=15)
    ap.add_argument("--batch", type=int, default=16384)
    ap.add_argument("--lr", type=float, default=0.001)
    ap.add_argument("--lam", type=float, default=0.3)
    ap.add_argument("--k", type=float, default=230.0)
    ap.add_argument("--valid", type=float, default=0.01, help="share held out (ignored with --valid-data)")
    ap.add_argument("--valid-data", help="packed file from games NOT in the training data: the honest check")
    ap.add_argument("--layout", choices=sorted(LAYOUTS), default="kb8")
    ap.add_argument("--fp32", action="store_true", help="exact EmbeddingBag feature transformer instead of fp16")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--chunk", type=int, default=0, help="positions per GPU chunk (default: half the free memory)")
    args = ap.parse_args()

    configure(args.layout)
    global HALF
    HALF = not args.fp32
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)
    # Memory-mapped sources; a file still being written (DataGen) is read up to its last whole record.
    sources = []
    for path in args.data:
        count = Path(path).stat().st_size // RECORD
        if count:
            sources.append(np.memmap(path, dtype=np.uint8, mode="r", shape=(count, RECORD)))
    n = sum(len(src) for src in sources)
    if args.valid_data:
        # validation from other games: positions of a training game never leak into it
        held_out = np.fromfile(args.valid_data, dtype=np.uint8)
        held_out = held_out[: len(held_out) // RECORD * RECORD].reshape(-1, RECORD)
    else:
        pick = np.sort(rng.choice(n, size=int(n * args.valid), replace=False))
        held_out = np.concatenate([src[pick[(pick >= lo) & (pick < lo + len(src))] - lo]
                                   for src, lo in zip(sources, np.cumsum([0] + [len(x) for x in sources[:-1]]))])
    valid_gpu = torch.from_numpy(np.ascontiguousarray(held_out)).to(device)
    # Chunks that fit into GPU memory (32 bytes per position); with one chunk the data stays there for good.
    budget = int(0.5 * torch.cuda.mem_get_info()[0]) // RECORD if device.type == "cuda" else 50_000_000
    chunk = max(args.batch, min(budget, args.chunk or budget))
    chunks = [(i, lo, min(lo + chunk, len(src))) for i, src in enumerate(sources) for lo in range(0, len(src), chunk)]
    resident = None
    if len(chunks) == 1:
        i, lo, hi = chunks[0]
        resident = torch.from_numpy(np.ascontiguousarray(sources[i][lo:hi])).to(device)
    print(f"{n} positions in {len(chunks)} chunk(s), {len(held_out)} validation, on {device}"
          f"{' ' + torch.cuda.get_device_name(0) if device.type == 'cuda' else ''}", flush=True)
    kb_map = torch.tensor(KING_BUCKETS, device=device)
    net = Net(args.hidden, args.buckets).to(device)
    others = [q for name, q in net.named_parameters() if name != "ft.weight"]
    opt = RowAdam([{"params": [net.ft.weight], "rows": True}, {"params": others}], lr=args.lr)
    scaler = torch.cuda.amp.GradScaler(enabled=device.type == "cuda" and HALF)
    steps_per_epoch = sum((hi - lo) // args.batch for _, lo, hi in chunks)
    total = steps_per_epoch * args.epochs
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: 0.1 ** (s / max(1, total - 1)))
    for epoch in range(1, args.epochs + 1):
        started = time.time()
        net.train()
        running, sampled = 0.0, 0
        for c in rng.permutation(len(chunks)):
            i, lo, hi = chunks[c]
            data = resident if resident is not None else \
                torch.from_numpy(np.ascontiguousarray(sources[i][lo:hi])).to(device)
            order = torch.randperm(hi - lo, device=device)
            for step in range((hi - lo) // args.batch):
                batch = Batch(data[order[step * args.batch:(step + 1) * args.batch]], device, kb_map)
                opt.zero_grad(set_to_none=True)
                loss = loss_fn(net, batch, args.lam, args.k)
                scaler.scale(loss).backward()
                scaler.step(opt)
                scaler.update()
                sched.step()
                net.clip()
                if step % 50 == 0:
                    running += loss.item()
                    sampled += 1
            del data
        net.eval()
        with torch.no_grad():
            vl, vn = 0.0, 0
            for s in range(0, len(valid_gpu), args.batch):
                batch = Batch(valid_gpu[s:s + args.batch], device, kb_map)
                m = batch.count.shape[0]
                vl += loss_fn(net, batch, args.lam, args.k).item() * m
                vn += m
        took = time.time() - started
        print(f"epoch {epoch} lr {sched.get_last_lr()[0]:.6f} train {running / max(1, sampled):.6f} "
              f"valid {vl / max(1, vn):.6f} ({took:.0f} s, {steps_per_epoch * args.batch / took / 1e6:.2f} M pos/s)",
              flush=True)
        export(net, Path(args.out))
        torch.save(net.state_dict(), Path(args.out).with_suffix(".pt"))
    print(f"wrote {args.out}")

if __name__ == "__main__":
    main()
