"""
Python port of Java FeatureExtractor. Must produce IDENTICAL output to the Java
version for any given position, otherwise training data won't match inference.

The Java source of truth:
  src/main/java/julius/game/chessengine/evaluation/nn/FeatureExtractor.java

Feature order and scaling constants must stay in sync. If you change anything
here, update the Java version and retrain the network.
"""

import chess
import numpy as np

FEATURE_COUNT = 71  # 70 position features + 1 classic eval prior

# Scaling constants (must match Java)
PIECE_COUNT_SCALE = 0.125
MATERIAL_SCALE = 1.0 / 1000.0
MOBILITY_SCALE = 0.05
COUNT_SCALE = 0.25
PHASE_SCALE = 1.0 / 256.0
HALFMOVE_SCALE = 1.0 / 50.0

PAWN_CP = 100
KNIGHT_CP = 320
BISHOP_CP = 330
ROOK_CP = 500
QUEEN_CP = 900

CENTER_SQUARES = chess.SquareSet([chess.D4, chess.E4, chess.D5, chess.E5])


def _file_mask(file: int) -> int:
    return chess.BB_FILES[file]


def _rank_mask(rank: int) -> int:
    return chess.BB_RANKS[rank]


def _bitcount(bb: int) -> int:
    return bin(bb).count("1")


def _phase(board: chess.Board) -> int:
    # Match BitBoard.getPhase() logic approximately: pawn 0, knight 1, bishop 1, rook 2, queen 4
    # Total phase = 24 at start. Scale to 0..256.
    phase_total = 24
    phase = phase_total
    phase -= _bitcount(board.knights) * 1
    phase -= _bitcount(board.bishops) * 1
    phase -= _bitcount(board.rooks) * 2
    phase -= _bitcount(board.queens) * 4
    phase = max(0, phase)
    return int(phase * 256 / phase_total)


def _attacks_by_color(board: chess.Board, color: bool) -> int:
    bb = 0
    for sq in chess.scan_forward(board.occupied_co[color]):
        bb |= int(board.attacks_mask(sq))
    return bb


def _count_passed(own_pawns: int, enemy_pawns: int, is_white: bool) -> int:
    count = 0
    pawns = own_pawns
    while pawns:
        sq = (pawns & -pawns).bit_length() - 1
        pawns &= pawns - 1
        file = sq & 7
        rank = sq >> 3
        adj = _file_mask(file)
        if file > 0:
            adj |= _file_mask(file - 1)
        if file < 7:
            adj |= _file_mask(file + 1)
        if is_white:
            forward = 0
            for r in range(rank + 1, 8):
                forward |= _rank_mask(r)
        else:
            forward = 0
            for r in range(rank - 1, -1, -1):
                forward |= _rank_mask(r)
        if (enemy_pawns & adj & forward) == 0:
            count += 1
    return count


def _count_doubled(pawns: int) -> int:
    count = 0
    for f in range(8):
        on_file = _bitcount(pawns & _file_mask(f))
        if on_file > 1:
            count += on_file - 1
    return count


def _count_isolated(pawns: int) -> int:
    count = 0
    for f in range(8):
        fm = pawns & _file_mask(f)
        if fm == 0:
            continue
        adj = 0
        if f > 0:
            adj |= _file_mask(f - 1)
        if f < 7:
            adj |= _file_mask(f + 1)
        if (pawns & adj) == 0:
            count += _bitcount(fm)
    return count


def _count_backward(own: int, enemy: int, is_white: bool) -> int:
    count = 0
    pawns = own
    while pawns:
        sq = (pawns & -pawns).bit_length() - 1
        pawns &= pawns - 1
        file = sq & 7
        rank = sq >> 3
        adj_files = 0
        if file > 0:
            adj_files |= _file_mask(file - 1)
        if file < 7:
            adj_files |= _file_mask(file + 1)
        if is_white:
            back_mask = _rank_mask(rank)
            for r in range(rank - 1, -1, -1):
                back_mask |= _rank_mask(r)
            backward = adj_files & back_mask
        else:
            back_mask = _rank_mask(rank)
            for r in range(rank + 1, 8):
                back_mask |= _rank_mask(r)
            backward = adj_files & back_mask
        if (own & backward) == 0:
            if is_white:
                ahead = 0
                for r in range(rank + 1, 8):
                    ahead |= _rank_mask(r)
            else:
                ahead = 0
                for r in range(rank - 1, -1, -1):
                    ahead |= _rank_mask(r)
            if (enemy & _file_mask(file) & ahead) != 0:
                count += 1
    return count


def _count_islands(pawns: int) -> int:
    if pawns == 0:
        return 0
    islands = 0
    in_island = False
    for f in range(8):
        if (pawns & _file_mask(f)) != 0:
            if not in_island:
                islands += 1
                in_island = True
        else:
            in_island = False
    return islands


def _count_rooks_on_open_files(rooks: int, pawns: int) -> int:
    count = 0
    r = rooks
    while r:
        sq = (r & -r).bit_length() - 1
        r &= r - 1
        file = sq & 7
        if (pawns & _file_mask(file)) == 0:
            count += 1
    return count


def _count_rooks_on_half_open_files(rooks: int, own_pawns: int, enemy_pawns: int) -> int:
    count = 0
    r = rooks
    while r:
        sq = (r & -r).bit_length() - 1
        r &= r - 1
        file = sq & 7
        if (own_pawns & _file_mask(file)) == 0 and (enemy_pawns & _file_mask(file)) != 0:
            count += 1
    return count


def _has_doubled_rooks(rooks: int) -> bool:
    for f in range(8):
        if _bitcount(rooks & _file_mask(f)) >= 2:
            return True
    return False


def _count_passers_for_connected(own_pawns: int, enemy_pawns: int, is_white: bool) -> int:
    passers = 0
    pawns = own_pawns
    while pawns:
        sq = (pawns & -pawns).bit_length() - 1
        pawns &= pawns - 1
        file = sq & 7
        rank = sq >> 3
        adj = _file_mask(file)
        if file > 0:
            adj |= _file_mask(file - 1)
        if file < 7:
            adj |= _file_mask(file + 1)
        if is_white:
            forward = 0
            for r in range(rank + 1, 8):
                forward |= _rank_mask(r)
        else:
            forward = 0
            for r in range(rank - 1, -1, -1):
                forward |= _rank_mask(r)
        if (enemy_pawns & adj & forward) == 0:
            passers |= 1 << sq
    return passers


def _has_connected_passed(own: int, enemy: int, is_white: bool) -> bool:
    passers = _count_passers_for_connected(own, enemy, is_white)
    for f in range(7):
        if (passers & _file_mask(f)) != 0 and (passers & _file_mask(f + 1)) != 0:
            return True
    return False


def _count_protected_passers(own: int, enemy: int, is_white: bool) -> int:
    count = 0
    pawns = own
    while pawns:
        sq = (pawns & -pawns).bit_length() - 1
        pawns &= pawns - 1
        file = sq & 7
        rank = sq >> 3
        adj = _file_mask(file)
        if file > 0:
            adj |= _file_mask(file - 1)
        if file < 7:
            adj |= _file_mask(file + 1)
        if is_white:
            forward = 0
            for r in range(rank + 1, 8):
                forward |= _rank_mask(r)
        else:
            forward = 0
            for r in range(rank - 1, -1, -1):
                forward |= _rank_mask(r)
        if (enemy & adj & forward) != 0:
            continue
        behind_rank = rank - 1 if is_white else rank + 1
        if behind_rank < 0 or behind_rank > 7:
            continue
        if file > 0 and (own & (1 << (behind_rank * 8 + file - 1))) != 0:
            count += 1
        elif file < 7 and (own & (1 << (behind_rank * 8 + file + 1))) != 0:
            count += 1
    return count


def _count_knight_outposts(knights: int, own_pawns: int, enemy_pawns: int, is_white: bool) -> int:
    count = 0
    n = knights
    if is_white:
        outpost_mask = _rank_mask(3) | _rank_mask(4) | _rank_mask(5)
    else:
        outpost_mask = _rank_mask(2) | _rank_mask(3) | _rank_mask(4)
    while n:
        sq = (n & -n).bit_length() - 1
        n &= n - 1
        if ((1 << sq) & outpost_mask) == 0:
            continue
        file = sq & 7
        rank = sq >> 3
        # Check no enemy pawn can attack this square
        fwd_files = 0
        if file > 0:
            fwd_files |= _file_mask(file - 1)
        if file < 7:
            fwd_files |= _file_mask(file + 1)
        if is_white:
            ahead = 0
            for r in range(rank + 1, 8):
                ahead |= _rank_mask(r)
        else:
            ahead = 0
            for r in range(rank - 1, -1, -1):
                ahead |= _rank_mask(r)
        if (enemy_pawns & fwd_files & ahead) != 0:
            continue
        count += 1
    return count


def _queen_approx_mobility(queens: int, all_pieces: int) -> int:
    total = 0
    q = queens
    while q:
        sq = (q & -q).bit_length() - 1
        q &= q - 1
        file = sq & 7
        rank = sq >> 3
        directions = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]
        for df, dr in directions:
            f, r, steps = file + df, rank + dr, 0
            while 0 <= f < 8 and 0 <= r < 8 and steps < 3:
                s = r * 8 + f
                if (all_pieces & (1 << s)) != 0:
                    break
                total += 1
                f += df
                r += dr
                steps += 1
    return total


def _king_zone(king_square: int) -> int:
    file = king_square & 7
    rank = king_square >> 3
    zone = 0
    for df in (-1, 0, 1):
        for dr in (-1, 0, 1):
            f = file + df
            r = rank + dr
            if 0 <= f < 8 and 0 <= r < 8:
                zone |= 1 << (r * 8 + f)
    return zone


def _pawn_shield(own_pawns: int, king_square: int, is_white: bool) -> int:
    file = king_square & 7
    rank = king_square >> 3
    target = rank + 1 if is_white else rank - 1
    if target < 0 or target > 7:
        return 0
    shield = 0
    for df in (-1, 0, 1):
        f = file + df
        if 0 <= f < 8:
            if (own_pawns & (1 << (target * 8 + f))) != 0:
                shield += 1
    return shield


def _hanging_value(pieces: int, enemy_attacks: int, own_attacks: int, piece_value: int) -> int:
    attacked = pieces & enemy_attacks
    undefended = attacked & ~own_attacks
    return _bitcount(undefended) * piece_value


def extract_features(board: chess.Board, classic_score_cp: int = 0) -> np.ndarray:
    """
    Return a float32 numpy array of length FEATURE_COUNT matching the Java
    FeatureExtractor output for the given position.

    classic_score_cp: classic evaluator score (white-perspective, centipawns).
    Written to feature index 70. Use 0 if unavailable.
    """
    out = np.zeros(FEATURE_COUNT, dtype=np.float32)

    wp = int(board.pawns & board.occupied_co[chess.WHITE])
    bp = int(board.pawns & board.occupied_co[chess.BLACK])
    wn = int(board.knights & board.occupied_co[chess.WHITE])
    bn = int(board.knights & board.occupied_co[chess.BLACK])
    wb_ = int(board.bishops & board.occupied_co[chess.WHITE])
    bb_ = int(board.bishops & board.occupied_co[chess.BLACK])
    wr = int(board.rooks & board.occupied_co[chess.WHITE])
    br_ = int(board.rooks & board.occupied_co[chess.BLACK])
    wq = int(board.queens & board.occupied_co[chess.WHITE])
    bq = int(board.queens & board.occupied_co[chess.BLACK])
    wk = int(board.kings & board.occupied_co[chess.WHITE])
    bk = int(board.kings & board.occupied_co[chess.BLACK])
    all_pieces = int(board.occupied)

    # Attack maps (expensive but needed for a few features)
    w_attacks = _attacks_by_color(board, chess.WHITE)
    b_attacks = _attacks_by_color(board, chess.BLACK)

    wpc = _bitcount(wp)
    bpc = _bitcount(bp)
    wnc = _bitcount(wn)
    bnc = _bitcount(bn)
    wbc = _bitcount(wb_)
    bbc = _bitcount(bb_)
    wrc = _bitcount(wr)
    brc = _bitcount(br_)
    wqc = _bitcount(wq)
    bqc = _bitcount(bq)

    i = 0
    # Material (12)
    out[i] = (wpc - bpc) * PIECE_COUNT_SCALE; i += 1
    out[i] = (wnc - bnc) * PIECE_COUNT_SCALE; i += 1
    out[i] = (wbc - bbc) * PIECE_COUNT_SCALE; i += 1
    out[i] = (wrc - brc) * PIECE_COUNT_SCALE; i += 1
    out[i] = (wqc - bqc) * PIECE_COUNT_SCALE; i += 1
    white_mat = wpc * PAWN_CP + wnc * KNIGHT_CP + wbc * BISHOP_CP + wrc * ROOK_CP + wqc * QUEEN_CP
    black_mat = bpc * PAWN_CP + bnc * KNIGHT_CP + bbc * BISHOP_CP + brc * ROOK_CP + bqc * QUEEN_CP
    out[i] = (white_mat - black_mat) * MATERIAL_SCALE; i += 1
    out[i] = (white_mat + black_mat) * MATERIAL_SCALE * 0.5; i += 1
    out[i] = 1.0 if wbc >= 2 else 0.0; i += 1
    out[i] = 1.0 if bbc >= 2 else 0.0; i += 1
    light_squares = 0x55AA55AA55AA55AA
    out[i] = (_bitcount(wb_ & light_squares) - _bitcount(bb_ & light_squares)) * PIECE_COUNT_SCALE; i += 1
    out[i] = (_bitcount(wb_ & (~light_squares & 0xFFFFFFFFFFFFFFFF))
              - _bitcount(bb_ & (~light_squares & 0xFFFFFFFFFFFFFFFF))) * PIECE_COUNT_SCALE; i += 1
    out[i] = ((wnc + wbc) - (bnc + bbc)) * PIECE_COUNT_SCALE; i += 1

    # Pawn structure (14)
    w_passed = _count_passed(wp, bp, True)
    b_passed = _count_passed(bp, wp, False)
    out[i] = (w_passed - b_passed) * COUNT_SCALE; i += 1
    out[i] = (_count_doubled(wp) - _count_doubled(bp)) * COUNT_SCALE; i += 1
    out[i] = (_count_isolated(wp) - _count_isolated(bp)) * COUNT_SCALE; i += 1
    out[i] = (_count_backward(wp, bp, True) - _count_backward(bp, wp, False)) * COUNT_SCALE; i += 1
    out[i] = (_count_islands(wp) - _count_islands(bp)) * COUNT_SCALE; i += 1
    w_adv = wp & (_rank_mask(4) | _rank_mask(5) | _rank_mask(6))
    b_adv = bp & (_rank_mask(1) | _rank_mask(2) | _rank_mask(3))
    out[i] = (_bitcount(w_adv) - _bitcount(b_adv)) * COUNT_SCALE; i += 1
    w_very_adv = wp & (_rank_mask(5) | _rank_mask(6))
    b_very_adv = bp & (_rank_mask(1) | _rank_mask(2))
    out[i] = (_bitcount(w_very_adv) - _bitcount(b_very_adv)) * COUNT_SCALE; i += 1
    out[i] = (_count_rooks_on_open_files(wr, wp | bp)
              - _count_rooks_on_open_files(br_, wp | bp)) * COUNT_SCALE; i += 1
    out[i] = (_count_rooks_on_half_open_files(wr, wp, bp)
              - _count_rooks_on_half_open_files(br_, bp, wp)) * COUNT_SCALE; i += 1
    out[i] = (1.0 if _has_doubled_rooks(wr) else 0.0) - (1.0 if _has_doubled_rooks(br_) else 0.0); i += 1
    out[i] = (1.0 if _has_connected_passed(wp, bp, True) else 0.0) \
             - (1.0 if _has_connected_passed(bp, wp, False) else 0.0); i += 1
    center_mask = int(CENTER_SQUARES.mask) if hasattr(CENTER_SQUARES, 'mask') else (1 << 27) | (1 << 28) | (1 << 35) | (1 << 36)
    out[i] = (_bitcount(wp & center_mask) - _bitcount(bp & center_mask)) * COUNT_SCALE; i += 1
    out[i] = (_count_protected_passers(wp, bp, True)
              - _count_protected_passers(bp, wp, False)) * COUNT_SCALE; i += 1
    out[i] = (wpc - bpc) * COUNT_SCALE; i += 1

    # Activity (14)
    out[i] = (_bitcount(w_attacks) - _bitcount(b_attacks)) * MOBILITY_SCALE; i += 1
    black_half = 0xFFFFFFFF00000000
    white_half = 0x00000000FFFFFFFF
    out[i] = (_bitcount(w_attacks & black_half) - _bitcount(b_attacks & white_half)) * MOBILITY_SCALE; i += 1
    out[i] = (_bitcount(w_attacks & center_mask) - _bitcount(b_attacks & center_mask)) * COUNT_SCALE; i += 1
    ext_center = 0x00003C3C3C3C0000
    out[i] = (_bitcount(w_attacks & ext_center) - _bitcount(b_attacks & ext_center)) * MOBILITY_SCALE; i += 1
    out[i] = (_count_knight_outposts(wn, wp, bp, True)
              - _count_knight_outposts(bn, bp, wp, False)) * COUNT_SCALE; i += 1
    long_diag = 0x8040201008040201 | 0x0102040810204080
    out[i] = (_bitcount(wb_ & long_diag) - _bitcount(bb_ & long_diag)) * COUNT_SCALE; i += 1
    out[i] = (_bitcount(wr & _rank_mask(6)) - _bitcount(br_ & _rank_mask(1))) * COUNT_SCALE; i += 1
    out[i] = (_queen_approx_mobility(wq, all_pieces)
              - _queen_approx_mobility(bq, all_pieces)) * MOBILITY_SCALE; i += 1
    w_back_rank = _rank_mask(0)
    b_back_rank = _rank_mask(7)
    out[i] = (_bitcount((wn | wb_) & w_back_rank)
              - _bitcount((bn | bb_) & b_back_rank)) * COUNT_SCALE; i += 1
    out[i] = (_bitcount(wn & ~w_back_rank & 0xFFFFFFFFFFFFFFFF)
              - _bitcount(bn & ~b_back_rank & 0xFFFFFFFFFFFFFFFF)) * COUNT_SCALE; i += 1
    out[i] = (_bitcount(wb_ & ~w_back_rank & 0xFFFFFFFFFFFFFFFF)
              - _bitcount(bb_ & ~b_back_rank & 0xFFFFFFFFFFFFFFFF)) * COUNT_SCALE; i += 1
    rim_files = _file_mask(0) | _file_mask(7)
    out[i] = (_bitcount(wn & rim_files) - _bitcount(bn & rim_files)) * COUNT_SCALE; i += 1
    wk_sq = (wk & -wk).bit_length() - 1 if wk else 0
    bk_sq = (bk & -bk).bit_length() - 1 if bk else 0
    w_zone = _king_zone(wk_sq)
    b_zone = _king_zone(bk_sq)
    out[i] = (_bitcount(w_attacks & b_zone) - _bitcount(b_attacks & w_zone)) * COUNT_SCALE; i += 1
    out[i] = (_bitcount(w_attacks) * 0.01) - (_bitcount(b_attacks) * 0.01); i += 1

    # King safety (12)
    out[i] = _pawn_shield(wp, wk_sq, True) * COUNT_SCALE; i += 1
    out[i] = _pawn_shield(bp, bk_sq, False) * COUNT_SCALE; i += 1
    all_pawns = wp | bp
    out[i] = 1.0 if (all_pawns & _file_mask(wk_sq & 7)) == 0 else 0.0; i += 1
    out[i] = 1.0 if (all_pawns & _file_mask(bk_sq & 7)) == 0 else 0.0; i += 1
    out[i] = 1.0 if ((wp & _file_mask(wk_sq & 7)) == 0 and (bp & _file_mask(wk_sq & 7)) != 0) else 0.0; i += 1
    out[i] = 1.0 if ((bp & _file_mask(bk_sq & 7)) == 0 and (wp & _file_mask(bk_sq & 7)) != 0) else 0.0; i += 1
    out[i] = _bitcount(b_attacks & w_zone) * COUNT_SCALE; i += 1
    out[i] = _bitcount(w_attacks & b_zone) * COUNT_SCALE; i += 1
    # Has-castled flags: python-chess doesn't track whether castling happened, only rights.
    # Approximate: king not on home square.
    out[i] = 1.0 if (wk_sq != chess.E1) else 0.0; i += 1
    out[i] = 1.0 if (bk_sq != chess.E8) else 0.0; i += 1
    out[i] = 1.0 if (wk_sq >> 3) == 0 else 0.0; i += 1
    out[i] = 1.0 if (bk_sq >> 3) == 7 else 0.0; i += 1

    # Threats (9)
    out[i] = _hanging_value(wp, b_attacks, w_attacks, PAWN_CP) * MATERIAL_SCALE; i += 1
    out[i] = _hanging_value(bp, w_attacks, b_attacks, PAWN_CP) * MATERIAL_SCALE; i += 1
    out[i] = _hanging_value(wn, b_attacks, w_attacks, KNIGHT_CP) * MATERIAL_SCALE; i += 1
    out[i] = _hanging_value(bn, w_attacks, b_attacks, KNIGHT_CP) * MATERIAL_SCALE; i += 1
    out[i] = _hanging_value(wb_, b_attacks, w_attacks, BISHOP_CP) * MATERIAL_SCALE; i += 1
    out[i] = _hanging_value(bb_, w_attacks, b_attacks, BISHOP_CP) * MATERIAL_SCALE; i += 1
    out[i] = _hanging_value(wr, b_attacks, w_attacks, ROOK_CP) * MATERIAL_SCALE; i += 1
    out[i] = _hanging_value(br_, w_attacks, b_attacks, ROOK_CP) * MATERIAL_SCALE; i += 1
    wq_attacked = 1 if (wq & b_attacks) != 0 else 0
    bq_attacked = 1 if (bq & w_attacks) != 0 else 0
    out[i] = wq_attacked - bq_attacked; i += 1

    # Position meta (9)
    out[i] = _phase(board) * PHASE_SCALE; i += 1
    out[i] = 1.0 if board.turn == chess.WHITE else -1.0; i += 1
    out[i] = 1.0 if board.has_kingside_castling_rights(chess.WHITE) else 0.0; i += 1
    out[i] = 1.0 if board.has_queenside_castling_rights(chess.WHITE) else 0.0; i += 1
    out[i] = 1.0 if board.has_kingside_castling_rights(chess.BLACK) else 0.0; i += 1
    out[i] = 1.0 if board.has_queenside_castling_rights(chess.BLACK) else 0.0; i += 1
    out[i] = min(50, board.halfmove_clock) * HALFMOVE_SCALE; i += 1
    non_kings = wpc + bpc + wnc + bnc + wbc + bbc + wrc + brc + wqc + bqc
    out[i] = non_kings * 0.03125; i += 1
    in_check = board.is_check()
    if in_check:
        out[i] = -1.0 if board.turn == chess.WHITE else 1.0
    else:
        out[i] = 0.0
    i += 1

    # Feature 70: classic eval prior (white-perspective centipawns, scaled).
    out[i] = classic_score_cp * MATERIAL_SCALE
    i += 1

    assert i == FEATURE_COUNT, f"Expected {FEATURE_COUNT} features but wrote {i}"
    return out


if __name__ == "__main__":
    # Quick sanity check
    b = chess.Board()
    f = extract_features(b)
    print(f"Starting position: {len(f)} features, non-zero count = {(f != 0).sum()}")
    print(f"Side to move feature: {f[55]:+.2f}")
    print(f"Phase feature: {f[54]:.3f}")
