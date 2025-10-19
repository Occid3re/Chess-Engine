# MoveDebugger.py
# Interactive decoder aligned with the Java move encoding in MoveHelper.
# Layout (LSB → MSB):
#   0..5   : from square index
#   6..11  : to square index
#   12..14 : piece type (1=P, 2=N, 3=B, 4=R, 5=Q, 6=K)
#   15     : color (1 = white, 0 = black)
#   16..17 : special (0=normal, 1=capture, 2=castling, 3=en-passant)
#   18..20 : promotion piece type (same mapping as pieces, 0 = none)
#   21..23 : captured piece type (same mapping as pieces, 0 = none)
#   24..29 : castling state bits prior to the move:
#            bit0: white king moved
#            bit1: white rook a1 moved
#            bit2: white rook h1 moved
#            bit3: black king moved
#            bit4: black rook a8 moved
#            bit5: black rook h8 moved
#
# The script prints all derived flags, including castling rights identical to
# MoveHelper#deriveCastlingRights.

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional

PIECE_TYPES: Dict[int, Optional[str]] = {
    0: None,
    1: "P",
    2: "N",
    3: "B",
    4: "R",
    5: "Q",
    6: "K",
}

SPECIAL_MAP = {
    0: "normal",
    1: "capture",
    2: "castling",
    3: "en-passant",
}

CASTLING_FLAG_NAMES = [
    ("whiteKingMoved", 0x01, "White king has moved"),
    ("whiteRookA1Moved", 0x02, "White rook a1 has moved"),
    ("whiteRookH1Moved", 0x04, "White rook h1 has moved"),
    ("blackKingMoved", 0x08, "Black king has moved"),
    ("blackRookA8Moved", 0x10, "Black rook a8 has moved"),
    ("blackRookH8Moved", 0x20, "Black rook h8 has moved"),
]

HELP_TEXT = """\
Enter a moveInt (decimal, 0xHEX, or 0bBINARY) to decode.
Commands:
  q / quit    exit
  h / help    show this help
  v           toggle verbose (show bit detail)
"""


@dataclass(frozen=True)
class Position:
    file: str
    rank: int

    def __str__(self) -> str:
        return f"{self.file}{self.rank}"


def bit_index_to_position(bit_index: int) -> Position:
    if bit_index < 0 or bit_index >= 64:
        raise ValueError(f"Square index out of range: {bit_index}")
    file_char = chr(ord("a") + (bit_index % 8))
    rank = 1 + (bit_index // 8)
    return Position(file_char, rank)


def parse_int(value: str) -> int:
    value = value.strip().lower().replace("_", "")
    if value.startswith("0x"):
        return int(value, 16)
    if value.startswith("0b"):
        return int(value, 2)
    return int(value, 10)


def extract(value: int, offset: int, width: int) -> int:
    return (value >> offset) & ((1 << width) - 1)


def derive_castling_rights(state: int) -> str:
    white_king_moved = (state & 0x01) != 0
    white_rook_a_moved = (state & 0x02) != 0
    white_rook_h_moved = (state & 0x04) != 0
    black_king_moved = (state & 0x08) != 0
    black_rook_a_moved = (state & 0x10) != 0
    black_rook_h_moved = (state & 0x20) != 0

    rights = []
    if not white_king_moved and not white_rook_h_moved:
        rights.append("K")
    if not white_king_moved and not white_rook_a_moved:
        rights.append("Q")
    if not black_king_moved and not black_rook_h_moved:
        rights.append("k")
    if not black_king_moved and not black_rook_a_moved:
        rights.append("q")
    return "".join(rights) or "-"


def decode_move(move_int: int) -> Dict[str, object]:
    from_index = extract(move_int, 0, 6)
    to_index = extract(move_int, 6, 6)
    piece_bits = extract(move_int, 12, 3)
    color_white = bool(extract(move_int, 15, 1))
    special = extract(move_int, 16, 2)
    promo_bits = extract(move_int, 18, 3)
    captured_bits = extract(move_int, 21, 3)
    castling_state = extract(move_int, 24, 6)

    decoded = {
        "move_int": move_int,
        "from_index": from_index,
        "to_index": to_index,
        "from": bit_index_to_position(from_index),
        "to": bit_index_to_position(to_index),
        "color": "White" if color_white else "Black",
        "piece_bits": piece_bits,
        "piece": PIECE_TYPES.get(piece_bits),
        "special": special,
        "special_name": SPECIAL_MAP.get(special, f"unknown({special})"),
        "promotion_bits": promo_bits,
        "promotion": PIECE_TYPES.get(promo_bits),
        "captured_bits": captured_bits,
        "captured": PIECE_TYPES.get(captured_bits),
        "castling_state": castling_state,
        "castling_rights": derive_castling_rights(castling_state),
        "is_capture": special == 1,
        "is_castling": special == 2,
        "is_en_passant": special == 3,
        "is_promotion": promo_bits != 0,
        "color_white": color_white,
    }
    decoded["san"] = to_algebraic(decoded)
    decoded["castling_flags"] = {
        name: bool(castling_state & flag) for name, flag, _ in CASTLING_FLAG_NAMES
    }
    return decoded


def to_algebraic(decoded: Dict[str, object]) -> str:
    """Best-effort SAN-style representation (without full disambiguation)."""
    if decoded["is_castling"]:
        return "O-O" if decoded["to"].file == "g" else "O-O-O"

    piece = decoded["piece"]
    san = ""
    if piece and piece != "P":
        san += piece

    if decoded["is_capture"]:
        if piece == "P":
            san += decoded["from"].file
        san += "x"

    san += str(decoded["to"])

    if decoded["is_promotion"]:
        promo = decoded["promotion"] or "?"
        san += f"={promo}"

    if decoded["is_en_passant"]:
        san += " e.p."
    return san or str(decoded["to"])


def format_castling_flags(state: int) -> str:
    parts = []
    for name, flag, description in CASTLING_FLAG_NAMES:
        parts.append(f"  - {description}: {'yes' if state & flag else 'no'}")
    return "\n".join(parts)


def format_decoded(decoded: Dict[str, object], verbose: bool) -> str:
    move_int = decoded["move_int"]
    lines = [
        f"moveInt: {move_int} (dec) | 0x{move_int:08X} | 0b{move_int:030b}",
        f"  SAN guess     : {decoded['san']}",
        f"  From          : {decoded['from']} (index {decoded['from_index']})",
        f"  To            : {decoded['to']} (index {decoded['to_index']})",
        f"  Piece         : {decoded['piece']} (bits {decoded['piece_bits']})",
        f"  Color         : {decoded['color']}",
        f"  Special       : {decoded['special_name']} (bits {decoded['special']})",
        f"  Capture?      : {'yes' if decoded['is_capture'] else 'no'}",
        f"  En-passant?   : {'yes' if decoded['is_en_passant'] else 'no'}",
        f"  Castling?     : {'yes' if decoded['is_castling'] else 'no'}",
        f"  Promotion     : {decoded['promotion']} (bits {decoded['promotion_bits']})",
        f"  Captured piece: {decoded['captured']} (bits {decoded['captured_bits']})",
        f"  Castling state: 0b{decoded['castling_state']:06b} ({decoded['castling_state']})",
        f"  Castling rights before move: {decoded['castling_rights']}",
    ]
    if verbose:
        lines.append("  Castling flags:")
        lines.append(format_castling_flags(decoded["castling_state"]))
    return "\n".join(lines)


def interactive():
    verbose = False
    print("Java MoveHelper-compatible decoder. Type 'h' for help.")
    while True:
        try:
            raw = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break

        if not raw:
            continue
        if raw in {"q", "quit", "exit"}:
            break
        if raw in {"h", "help"}:
            print(HELP_TEXT)
            continue
        if raw == "v":
            verbose = not verbose
            print(f"Verbose mode {'on' if verbose else 'off'}")
            continue

        try:
            move_int = parse_int(raw)
            decoded = decode_move(move_int)
            print(format_decoded(decoded, verbose))
        except ValueError as ex:
            print(f"! {ex}")


def main():
    interactive()


if __name__ == "__main__":
    main()
