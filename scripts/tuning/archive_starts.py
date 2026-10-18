#!/usr/bin/env python3
"""Start positions for self-play (core.tools.DataGen -Dgen.starts) from the bot's own Lichess games.

The bot archives every game it plays (games.jsonl) and imported its earlier history once
(games-lichess.jsonl), see alieknek_service.py. Self-play from those positions produces training data in
the positions real opponents steer the bot into, instead of only the lines it chooses against itself;
positions from games the bot lost are written twice, so its mistakes get more data.

The output mixes these positions with an existing start file (e.g. the 8-20-piece endgame starts) so that
the archive positions make up --share of the result. Each line is a FEN; DataGen adds 0-2 random plies.

Usage (python-chess required, e.g. C:\\Python38\\python.exe):
  python scripts/tuning/archive_starts.py --archive games.jsonl games-lichess.jsonl \\
      --mix E:/Chess-Data/selfplay/starts_8_20.txt --out E:/Chess-Data/selfplay/starts_lichess_mix.txt
"""
import argparse
import json
import random
from pathlib import Path

import chess

FIRST_PLY = 8      # earlier positions are opening theory; the opening suite covers them
LAST_PLY = 100


def archive_games(paths):
    """Unique games (by id) from archive JSONL files; later files win (the bot's own record has evals)."""
    games = {}
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    g = json.loads(line)
                except ValueError:
                    continue
                if g.get("id") and g.get("result") in ("win", "draw", "loss") and g.get("moves"):
                    games[g["id"]] = g
    return list(games.values())


def positions(game):
    """FENs of plies FIRST_PLY..LAST_PLY of one game (legal replay only; stops at the first bad move)."""
    board = chess.Board()
    out = []
    for ply, uci in enumerate(game["moves"]):
        if ply >= LAST_PLY:
            break
        try:
            move = chess.Move.from_uci(uci)
        except ValueError:
            break
        if move not in board.legal_moves:
            break
        board.push(move)
        if ply + 1 >= FIRST_PLY and not board.is_game_over():
            out.append(board.fen())
    return out


def build(games, loss_weight=2):
    starts = []
    for g in games:
        fens = positions(g)
        starts.extend(fens * (loss_weight if g["result"] == "loss" else 1))
    return starts


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--archive", nargs="+", required=True, help="games.jsonl / games-lichess.jsonl files")
    ap.add_argument("--mix", help="existing start file to mix in (one FEN or fen;... per line)")
    ap.add_argument("--share", type=float, default=0.4, help="share of archive positions in the output")
    ap.add_argument("--loss-weight", type=int, default=2)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()

    games = archive_games(args.archive)
    starts = build(games, args.loss_weight)
    print(f"{len(games)} games -> {len(starts)} archive start positions "
          f"({sum(g['result'] == 'loss' for g in games)} lost games weighted x{args.loss_weight})")
    rng = random.Random(args.seed)
    if args.mix:
        other = [l.strip() for l in open(args.mix, encoding="utf-8") if l.strip()]
        wanted = int(len(starts) * (1 - args.share) / args.share)
        other = rng.sample(other, min(wanted, len(other)))
        starts = starts + other
        print(f"mixed with {len(other)} positions from {args.mix}")
    rng.shuffle(starts)
    Path(args.out).write_text("\n".join(starts) + "\n", encoding="utf-8")
    print(f"wrote {len(starts)} start positions to {args.out}")


if __name__ == "__main__":
    main()
