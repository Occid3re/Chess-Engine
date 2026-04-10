"""
Train a small MLP on extracted features labeled with Stockfish evals.

Architecture: 70 → 64 → 32 → 1 (ReLU activations, no output activation)
Loss: MSE on tanh(eval / 400) — squashes centipawns into [-1, 1]

Output: a binary weights file consumable by Java SmallNN.

Usage:
    python train.py --data training_data.npz --out ../../src/main/resources/nn/v1/weights.bin
"""

from __future__ import annotations

import argparse
import struct
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import DataLoader, TensorDataset, random_split

FEATURE_COUNT = 71
HIDDEN1 = 128
HIDDEN2 = 64
# Residual targets typically fall in [-400, +400] centipawns, so tanh(x/400) stays
# away from saturation.
OUTPUT_SCALE = 400.0


class SmallNet(nn.Module):
    def __init__(self, input_size=FEATURE_COUNT, h1=HIDDEN1, h2=HIDDEN2):
        super().__init__()
        self.fc1 = nn.Linear(input_size, h1)
        self.fc2 = nn.Linear(h1, h2)
        self.fc3 = nn.Linear(h2, 1)

    def forward(self, x):
        x = torch.relu(self.fc1(x))
        x = torch.relu(self.fc2(x))
        x = self.fc3(x)
        return x


def squash(cp: torch.Tensor) -> torch.Tensor:
    """Centipawns → [-1, 1] via tanh(cp / OUTPUT_SCALE)."""
    return torch.tanh(cp / OUTPUT_SCALE)


def train(data_path: str, out_path: str, epochs: int, batch_size: int, lr: float):
    print(f"Loading data from {data_path}")
    data = np.load(data_path)
    X = data["X"].astype(np.float32)
    # Prefer residual target if present (v3 data), fall back to raw Stockfish eval (v2 data).
    if "y_residual" in data.files:
        y = data["y_residual"].astype(np.float32)
        print("  Using RESIDUAL target (stockfish - classic)")
    else:
        y = data["y"].astype(np.float32)
        print("  Using RAW target (stockfish eval)")
    print(f"  X: {X.shape}, y: {y.shape}")
    print(f"  y range: [{y.min():.0f}, {y.max():.0f}], mean: {y.mean():.1f}, std: {y.std():.1f}")

    # Compute normalization stats (per-feature mean/std)
    means = X.mean(axis=0).astype(np.float32)
    stds = X.std(axis=0).astype(np.float32)
    stds[stds < 1e-6] = 1.0  # avoid divide-by-zero

    # Apply normalization once here — the Java side also normalizes using these values.
    X_norm = (X - means) / stds

    X_t = torch.from_numpy(X_norm)
    y_squashed = squash(torch.from_numpy(y))

    dataset = TensorDataset(X_t, y_squashed)
    val_size = max(1, int(len(dataset) * 0.1))
    train_size = len(dataset) - val_size
    train_ds, val_ds = random_split(dataset, [train_size, val_size],
                                    generator=torch.Generator().manual_seed(42))

    train_loader = DataLoader(train_ds, batch_size=batch_size, shuffle=True)
    val_loader = DataLoader(val_ds, batch_size=batch_size, shuffle=False)

    model = SmallNet()
    optimizer = optim.Adam(model.parameters(), lr=lr, weight_decay=1e-5)
    # Huber loss is more robust to large outliers (mate scores, clamped positions)
    loss_fn = nn.SmoothL1Loss(beta=0.1)
    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)

    best_val = float("inf")
    best_state = None
    patience = 6
    bad_epochs = 0

    for epoch in range(epochs):
        model.train()
        train_loss_sum = 0.0
        n_train = 0
        for xb, yb in train_loader:
            optimizer.zero_grad()
            pred = model(xb).squeeze(-1)
            loss = loss_fn(pred, yb)
            loss.backward()
            optimizer.step()
            train_loss_sum += loss.item() * xb.size(0)
            n_train += xb.size(0)
        train_loss = train_loss_sum / n_train

        scheduler.step()

        model.eval()
        with torch.no_grad():
            val_loss_sum = 0.0
            val_mse_sum = 0.0
            n_val = 0
            for xb, yb in val_loader:
                pred = model(xb).squeeze(-1)
                val_loss_sum += loss_fn(pred, yb).item() * xb.size(0)
                val_mse_sum += ((pred - yb) ** 2).sum().item()
                n_val += xb.size(0)
            val_loss = val_loss_sum / n_val
            val_mse = val_mse_sum / n_val

        # Report cp-level RMSE for interpretability
        val_cp_rmse = np.sqrt(val_mse) * OUTPUT_SCALE
        print(f"epoch {epoch+1:3d}: train={train_loss:.5f}  val={val_loss:.5f}  val_cp_rmse~{val_cp_rmse:.0f}")

        if val_loss < best_val - 1e-5:
            best_val = val_loss
            best_state = {k: v.clone() for k, v in model.state_dict().items()}
            bad_epochs = 0
        else:
            bad_epochs += 1
            if bad_epochs >= patience:
                print(f"  early stop at epoch {epoch+1}")
                break

    if best_state is not None:
        model.load_state_dict(best_state)

    # Export weights to binary
    export_weights(model, means, stds, out_path)
    print(f"Saved weights to {out_path}")


def export_weights(model: SmallNet, means: np.ndarray, stds: np.ndarray, out_path: str):
    """
    Serialize weights to the binary format expected by SmallNN.java:
      int32 inputSize, int32 h1, int32 h2  (little-endian)
      float[inputSize] means
      float[inputSize] stds
      float[h1 * inputSize] W1 (row-major)
      float[h1] b1
      float[h2 * h1] W2 (row-major)
      float[h2] b2
      float[1 * h2] W3
      float[1] b3
      float outputScale
    """
    Path(out_path).parent.mkdir(parents=True, exist_ok=True)
    input_size = FEATURE_COUNT
    h1 = HIDDEN1
    h2 = HIDDEN2

    W1 = model.fc1.weight.detach().cpu().numpy().astype(np.float32)  # (h1, input)
    b1 = model.fc1.bias.detach().cpu().numpy().astype(np.float32)
    W2 = model.fc2.weight.detach().cpu().numpy().astype(np.float32)  # (h2, h1)
    b2 = model.fc2.bias.detach().cpu().numpy().astype(np.float32)
    W3 = model.fc3.weight.detach().cpu().numpy().astype(np.float32)  # (1, h2)
    b3 = model.fc3.bias.detach().cpu().numpy().astype(np.float32)

    with open(out_path, "wb") as f:
        f.write(struct.pack("<iii", input_size, h1, h2))
        f.write(means.astype("<f4").tobytes())
        f.write(stds.astype("<f4").tobytes())
        f.write(W1.ravel().astype("<f4").tobytes())
        f.write(b1.astype("<f4").tobytes())
        f.write(W2.ravel().astype("<f4").tobytes())
        f.write(b2.astype("<f4").tobytes())
        f.write(W3.ravel().astype("<f4").tobytes())
        f.write(b3.astype("<f4").tobytes())
        f.write(struct.pack("<f", OUTPUT_SCALE))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=str, default="training_data.npz")
    ap.add_argument("--out", type=str, default="../../src/main/resources/nn/v1/weights.bin")
    ap.add_argument("--epochs", type=int, default=50)
    ap.add_argument("--batch-size", type=int, default=8192)
    ap.add_argument("--lr", type=float, default=1e-3)
    args = ap.parse_args()
    train(args.data, args.out, args.epochs, args.batch_size, args.lr)
