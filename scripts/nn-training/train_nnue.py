#!/usr/bin/env python3
"""
Train NNUE network from position data.

Architecture: HalfKP(40960) -> L1(256) -> ClippedReLU -> L2(32) -> ClippedReLU -> L3(1)

Input: npz file from generate_nnue_data.py with fens, evals, wdls.
Output: binary weight file for Java NNUENetwork.java.

Usage:
  python train_nnue.py --data nnue_data_200k.npz --out ../../src/main/resources/nn/nnue/weights.bin --epochs 100
"""
import argparse
import struct
import sys
import time

import chess
import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import DataLoader, TensorDataset

from nnue_features import extract_active_features, TOTAL_FEATURES, ACCUMULATOR_SIZE

# Network dimensions
L1_SIZE = ACCUMULATOR_SIZE  # 256
L2_SIZE = 32
OUTPUT_SCALE = 400  # centipawns per output unit

# Training
EVAL_SCALE = 400.0  # sigmoid scaling for eval targets
WDL_WEIGHT = 0.3    # weight for WDL loss component
EVAL_WEIGHT = 0.7   # weight for eval loss component


class NNUENet(nn.Module):
    """NNUE network with sparse first layer (EmbeddingBag)."""

    def __init__(self, feature_size=TOTAL_FEATURES, l1_size=L1_SIZE, l2_size=L2_SIZE):
        super().__init__()
        # L1: sparse embedding (40960 -> 256 per perspective, summed)
        self.l1 = nn.EmbeddingBag(feature_size, l1_size, mode='sum', sparse=True)
        # L2: dense (512 -> 32), input is concatenation of both perspectives
        self.l2 = nn.Linear(l1_size * 2, l2_size)
        # L3: dense (32 -> 1)
        self.l3 = nn.Linear(l2_size, 1)

    def forward(self, white_features, white_offsets, black_features, black_offsets):
        """
        Args:
            white_features: 1D tensor of active feature indices for white perspective
            white_offsets: tensor of batch offsets for white_features
            black_features: 1D tensor of active feature indices for black perspective
            black_offsets: tensor of batch offsets for black_features
        """
        # L1: sparse sum for each perspective
        w_acc = self.l1(white_features, white_offsets)  # (batch, 256)
        b_acc = self.l1(black_features, black_offsets)  # (batch, 256)

        # ClippedReLU on accumulators
        w_acc = torch.clamp(w_acc, 0, 127.0 / 64.0)  # approximate clipped relu
        b_acc = torch.clamp(b_acc, 0, 127.0 / 64.0)

        # Concatenate perspectives (side-to-move first)
        # Note: we handle perspective ordering in the data loader
        combined = torch.cat([w_acc, b_acc], dim=1)  # (batch, 512)

        # L2 + ClippedReLU
        x = self.l2(combined)
        x = torch.clamp(x, 0, 127.0 / 64.0)

        # L3 (no activation)
        x = self.l3(x)
        return x.squeeze(1)


def prepare_training_data(npz_path):
    """Load positions and extract HalfKP features."""
    print(f"[+] Loading data from {npz_path}")
    data = np.load(npz_path, allow_pickle=True)
    fens = data['fens']
    evals = data['evals'].astype(np.float32)
    wdls = data['wdls'].astype(np.float32)

    print(f"  Positions: {len(fens)}")
    print(f"  Eval range: [{evals.min()}, {evals.max()}], mean={evals.mean():.1f}")
    print(f"  WDL dist: W={np.sum(wdls==2)}, D={np.sum(wdls==1)}, L={np.sum(wdls==0)}")

    # Extract features for all positions
    print("[+] Extracting HalfKP features...")
    all_white_features = []
    all_black_features = []
    all_stm = []  # side to move: 1=white, 0=black
    valid_indices = []

    t0 = time.time()
    for i, fen in enumerate(fens):
        try:
            board = chess.Board(str(fen))
            wf, bf = extract_active_features(board)
            all_white_features.append(wf)
            all_black_features.append(bf)
            all_stm.append(1 if board.turn == chess.WHITE else 0)
            valid_indices.append(i)
        except Exception as e:
            if i < 3:
                print(f"  [warn] Position {i} failed: {e}")

        if (i + 1) % 10000 == 0:
            elapsed = time.time() - t0
            print(f"  {i+1}/{len(fens)} ({(i+1)/elapsed:.0f} pos/s)", end="\r")

    print(f"\n[+] Extracted {len(valid_indices)} valid positions in {time.time()-t0:.1f}s")

    evals = evals[valid_indices]
    wdls = wdls[valid_indices]
    stm = np.array(all_stm, dtype=np.float32)

    # WDL: normalize to [0, 1] (0=loss, 0.5=draw, 1=win from white perspective)
    wdl_targets = wdls / 2.0

    # Eval: sigmoid squash
    eval_targets = 1.0 / (1.0 + np.exp(-evals / EVAL_SCALE))

    return all_white_features, all_black_features, stm, eval_targets, wdl_targets


def collate_sparse(batch_wf, batch_bf, batch_stm, batch_eval, batch_wdl):
    """Collate sparse features into EmbeddingBag format."""
    white_features = []
    white_offsets = [0]
    black_features = []
    black_offsets = [0]

    for i in range(len(batch_wf)):
        stm = batch_stm[i]
        # Order: side-to-move perspective first
        if stm > 0.5:  # white to move
            wf, bf = batch_wf[i], batch_bf[i]
        else:  # black to move
            wf, bf = batch_bf[i], batch_wf[i]

        white_features.extend(wf)
        white_offsets.append(len(white_features))
        black_features.extend(bf)
        black_offsets.append(len(black_features))

    return (
        torch.tensor(white_features, dtype=torch.long),
        torch.tensor(white_offsets[:-1], dtype=torch.long),
        torch.tensor(black_features, dtype=torch.long),
        torch.tensor(black_offsets[:-1], dtype=torch.long),
        torch.tensor(batch_eval, dtype=torch.float32),
        torch.tensor(batch_wdl, dtype=torch.float32),
    )


def train(model, train_data, val_data, epochs, lr, batch_size, device):
    """Train the NNUE model."""
    wf_train, bf_train, stm_train, eval_train, wdl_train = train_data
    wf_val, bf_val, stm_val, eval_val, wdl_val = val_data

    optimizer = optim.Adam(model.parameters(), lr=lr, weight_decay=1e-6)
    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)
    loss_fn = nn.MSELoss()

    best_val_loss = float('inf')
    best_state = None
    patience = 10
    no_improve = 0

    n_train = len(eval_train)
    n_val = len(eval_val)

    for epoch in range(1, epochs + 1):
        model.train()
        # Shuffle training data
        perm = np.random.permutation(n_train)
        epoch_loss = 0.0
        batches = 0

        for start in range(0, n_train, batch_size):
            end = min(start + batch_size, n_train)
            idx = perm[start:end]

            b_wf = [wf_train[i] for i in idx]
            b_bf = [bf_train[i] for i in idx]
            b_stm = stm_train[idx]
            b_eval = eval_train[idx]
            b_wdl = wdl_train[idx]

            wf, wo, bf, bo, et, wt = collate_sparse(b_wf, b_bf, b_stm, b_eval, b_wdl)
            wf, wo, bf, bo = wf.to(device), wo.to(device), bf.to(device), bo.to(device)
            et, wt = et.to(device), wt.to(device)

            optimizer.zero_grad()
            pred = torch.sigmoid(model(wf, wo, bf, bo))

            # Hybrid loss: eval + WDL
            eval_loss = loss_fn(pred, et)
            wdl_loss = loss_fn(pred, wt)
            loss = EVAL_WEIGHT * eval_loss + WDL_WEIGHT * wdl_loss

            loss.backward()
            optimizer.step()

            epoch_loss += loss.item()
            batches += 1

        scheduler.step()
        avg_train = epoch_loss / max(1, batches)

        # Validation
        model.eval()
        val_loss = 0.0
        val_batches = 0
        val_cp_errors = []

        with torch.no_grad():
            for start in range(0, n_val, batch_size):
                end = min(start + batch_size, n_val)
                idx = list(range(start, end))

                b_wf = [wf_val[i] for i in idx]
                b_bf = [bf_val[i] for i in idx]
                b_stm = stm_val[start:end]
                b_eval = eval_val[start:end]
                b_wdl = wdl_val[start:end]

                wf, wo, bf, bo, et, wt = collate_sparse(b_wf, b_bf, b_stm, b_eval, b_wdl)
                wf, wo, bf, bo = wf.to(device), wo.to(device), bf.to(device), bo.to(device)
                et, wt = et.to(device), wt.to(device)

                pred = torch.sigmoid(model(wf, wo, bf, bo))
                loss = EVAL_WEIGHT * loss_fn(pred, et) + WDL_WEIGHT * loss_fn(pred, wt)
                val_loss += loss.item()
                val_batches += 1

                # Convert back to centipawns for RMSE
                pred_cp = -EVAL_SCALE * torch.log((1.0 / pred.clamp(1e-6, 1-1e-6)) - 1.0)
                true_cp_from_sigmoid = -EVAL_SCALE * torch.log((1.0 / et.clamp(1e-6, 1-1e-6)) - 1.0)
                val_cp_errors.extend((pred_cp - true_cp_from_sigmoid).cpu().numpy())

        avg_val = val_loss / max(1, val_batches)
        cp_rmse = np.sqrt(np.mean(np.array(val_cp_errors) ** 2))

        print(f"epoch {epoch:3d}: train={avg_train:.5f}  val={avg_val:.5f}  val_cp_rmse~{cp_rmse:.0f}")

        if avg_val < best_val_loss:
            best_val_loss = avg_val
            best_state = {k: v.cpu().clone() for k, v in model.state_dict().items()}
            no_improve = 0
        else:
            no_improve += 1
            if no_improve >= patience:
                print(f"  early stop at epoch {epoch}")
                break

    if best_state:
        model.load_state_dict(best_state)
    return model


def export_weights(model, path, output_scale=OUTPUT_SCALE):
    """Export weights in binary format matching Java NNUENetwork.java."""
    import os
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)

    state = model.state_dict()

    # L1 weights from EmbeddingBag: shape (40960, 256)
    l1_w = state['l1.weight'].numpy()  # (40960, 256)
    # No L1 biases in EmbeddingBag — use zeros
    l1_b = np.zeros(L1_SIZE, dtype=np.float32)

    # L2 weights: shape (32, 512)
    l2_w = state['l2.weight'].numpy()  # (32, 512) — row-major, need to transpose for Java
    l2_b = state['l2.bias'].numpy()

    # L3 weights: shape (1, 32)
    l3_w = state['l3.weight'].numpy().flatten()
    l3_b = state['l3.bias'].numpy().item()

    # Quantize to int16
    # L1: scale to fit int16 range. The accumulators sum ~20 of these, so keep values moderate.
    l1_scale = 64.0  # quantization scale
    l1_w_q = np.clip(np.round(l1_w * l1_scale), -32767, 32767).astype(np.int16)
    l1_b_q = np.clip(np.round(l1_b * l1_scale), -32767, 32767).astype(np.int16)

    # L2: input is clipped to [0, 127], weights scale accordingly
    l2_scale = 64.0
    l2_w_q = np.clip(np.round(l2_w * l2_scale), -32767, 32767).astype(np.int16)
    l2_b_q = np.clip(np.round(l2_b * l2_scale * l1_scale), -32767, 32767).astype(np.int16)

    # L3
    l3_scale = 64.0
    l3_w_q = np.clip(np.round(l3_w * l3_scale), -32767, 32767).astype(np.int16)
    l3_b_q = int(np.clip(np.round(l3_b * l3_scale * l2_scale), -32767, 32767))

    # Output scale
    out_scale = int(output_scale)

    with open(path, 'wb') as f:
        # Header
        f.write(struct.pack('<i', 1))           # version
        f.write(struct.pack('<i', TOTAL_FEATURES))
        f.write(struct.pack('<i', L1_SIZE))
        f.write(struct.pack('<i', L2_SIZE))

        # L1 weights: (40960, 256) row-major as int16
        for row in l1_w_q:
            f.write(row.tobytes())
        f.write(l1_b_q.tobytes())

        # L2 weights: Java expects (l2Size, l2InputSize) row-major
        # PyTorch Linear stores (out_features, in_features) which IS row-major
        for row in l2_w_q:
            f.write(row.tobytes())
        f.write(l2_b_q.tobytes())

        # L3
        f.write(l3_w_q.tobytes())
        f.write(struct.pack('<h', l3_b_q))
        f.write(struct.pack('<h', out_scale))

    file_size = os.path.getsize(path)
    print(f"[+] Saved NNUE weights to {path} ({file_size/1e6:.1f} MB)")
    print(f"    L1: {l1_w_q.shape}, L2: {l2_w_q.shape}, L3: {l3_w_q.shape}")


def main():
    parser = argparse.ArgumentParser(description="Train NNUE network")
    parser.add_argument("--data", required=True, help="Input npz from generate_nnue_data.py")
    parser.add_argument("--out", default="../../src/main/resources/nn/nnue/weights.bin")
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--batch-size", type=int, default=8192)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--val-split", type=float, default=0.1)
    args = parser.parse_args()

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"[+] Device: {device}")

    # Load and prepare data
    wf, bf, stm, eval_targets, wdl_targets = prepare_training_data(args.data)

    # Split train/val
    n = len(eval_targets)
    n_val = int(n * args.val_split)
    n_train = n - n_val

    perm = np.random.permutation(n)
    train_idx = perm[:n_train]
    val_idx = perm[n_train:]

    train_data = (
        [wf[i] for i in train_idx],
        [bf[i] for i in train_idx],
        stm[train_idx], eval_targets[train_idx], wdl_targets[train_idx]
    )
    val_data = (
        [wf[i] for i in val_idx],
        [bf[i] for i in val_idx],
        stm[val_idx], eval_targets[val_idx], wdl_targets[val_idx]
    )

    print(f"[+] Train: {n_train}, Val: {n_val}")

    # Create model
    model = NNUENet().to(device)
    total_params = sum(p.numel() for p in model.parameters())
    print(f"[+] Model parameters: {total_params:,}")

    # Train
    model = train(model, train_data, val_data, args.epochs, args.lr, args.batch_size, device)

    # Export
    model = model.cpu()
    export_weights(model, args.out)


if __name__ == "__main__":
    main()
