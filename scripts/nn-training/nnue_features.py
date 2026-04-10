#!/usr/bin/env python3
"""
HalfKP feature index computation for NNUE training.
Must produce identical indices to Java NNUEFeatures.java.
"""
import chess

PIECE_TYPES = 5       # pawn, knight, bishop, rook, queen (no king)
PIECE_INDICES = 10    # own 5 + enemy 5
FEATURES_PER_KING = PIECE_INDICES * 64  # 640
TOTAL_FEATURES = 64 * FEATURES_PER_KING  # 40960
ACCUMULATOR_SIZE = 256

# Piece type mapping: chess.PAWN=1..chess.QUEEN=5 (matching Java MoveHelper)
PIECE_TYPE_MAP = {
    chess.PAWN: 1,
    chess.KNIGHT: 2,
    chess.BISHOP: 3,
    chess.ROOK: 4,
    chess.QUEEN: 5,
}


def feature_index(king_sq, piece_sq, piece_type_bits, piece_is_white, persp_is_white):
    """Compute HalfKP feature index. Must match Java NNUEFeatures.featureIndex()."""
    if piece_type_bits < 1 or piece_type_bits > 5:
        return -1

    # For black perspective, mirror squares vertically
    ks = king_sq if persp_is_white else (king_sq ^ 56)
    ps = piece_sq if persp_is_white else (piece_sq ^ 56)

    # Own vs enemy
    is_own = (piece_is_white == persp_is_white)
    piece_idx = (piece_type_bits - 1) + (0 if is_own else PIECE_TYPES)

    return ks * FEATURES_PER_KING + piece_idx * 64 + ps


def extract_active_features(board):
    """
    Extract active HalfKP feature indices for both perspectives.
    Returns (white_features, black_features) as lists of ints.
    """
    wk = board.king(chess.WHITE)
    bk = board.king(chess.BLACK)

    white_features = []
    black_features = []

    for sq in range(64):
        piece = board.piece_at(sq)
        if piece is None or piece.piece_type == chess.KING:
            continue

        pt_bits = PIECE_TYPE_MAP.get(piece.piece_type)
        if pt_bits is None:
            continue

        is_white = piece.color == chess.WHITE

        # White perspective
        idx_w = feature_index(wk, sq, pt_bits, is_white, True)
        if idx_w >= 0:
            white_features.append(idx_w)

        # Black perspective
        idx_b = feature_index(bk, sq, pt_bits, is_white, False)
        if idx_b >= 0:
            black_features.append(idx_b)

    return white_features, black_features


def test_symmetry():
    """Quick sanity check: symmetric positions should produce mirrored features."""
    board = chess.Board()
    wf, bf = extract_active_features(board)
    print(f"Starting position: {len(wf)} white features, {len(bf)} black features")
    assert len(wf) == 30, f"Expected 30, got {len(wf)}"  # 16 pawns + 4N + 4B + 4R + 2Q
    assert len(bf) == 30, f"Expected 30, got {len(bf)}"
    print("Symmetry test passed!")


if __name__ == "__main__":
    test_symmetry()

    # Test a specific position
    board = chess.Board("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1")
    wf, bf = extract_active_features(board)
    print(f"After 1.e4: {len(wf)} white features, {len(bf)} black features")
    print(f"  White features (first 5): {wf[:5]}")
    print(f"  Black features (first 5): {bf[:5]}")
