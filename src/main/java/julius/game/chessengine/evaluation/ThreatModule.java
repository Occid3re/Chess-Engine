package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.ImmutableBoardView;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.KingHelper;
import julius.game.chessengine.helper.KnightHelper;
import julius.game.chessengine.helper.RookHelper;
import julius.game.chessengine.tuning.Tuning;

import java.util.Objects;

import static julius.game.chessengine.helper.PawnMoveTables.PAWN_ATTACKS;

/**
 * Evaluates tactical vulnerabilities such as hanging pieces and pawn attacks on higher valued
 * pieces. The module recomputes its contribution when marked dirty rather than maintaining
 * incremental state.
 */
public final class ThreatModule implements EvaluationModule {

    private static final int WHITE = 0;
    private static final int BLACK = 1;

    private static final int PAWN = MoveHelper.pieceTypeToInt(PieceType.PAWN);
    private static final int KNIGHT = MoveHelper.pieceTypeToInt(PieceType.KNIGHT);
    private static final int BISHOP = MoveHelper.pieceTypeToInt(PieceType.BISHOP);
    private static final int ROOK = MoveHelper.pieceTypeToInt(PieceType.ROOK);
    private static final int QUEEN = MoveHelper.pieceTypeToInt(PieceType.QUEEN);
    private static final int KING = MoveHelper.pieceTypeToInt(PieceType.KING);

    private static final BishopHelper BISHOP_HELPER = BishopHelper.getInstance();
    private static final RookHelper ROOK_HELPER = RookHelper.getInstance();

    private final int[] hangingPenalties = new int[7];
    private final int[] pawnThreatPenalties = new int[7];

    public ThreatModule() {
        hangingPenalties[PAWN] = Tuning.hangingPawnPenalty();
        hangingPenalties[KNIGHT] = Tuning.hangingKnightPenalty();
        hangingPenalties[BISHOP] = Tuning.hangingBishopPenalty();
        hangingPenalties[ROOK] = Tuning.hangingRookPenalty();
        hangingPenalties[QUEEN] = Tuning.hangingQueenPenalty();

        pawnThreatPenalties[KNIGHT] = Tuning.pawnThreatKnightPenalty();
        pawnThreatPenalties[BISHOP] = Tuning.pawnThreatBishopPenalty();
        pawnThreatPenalties[ROOK] = Tuning.pawnThreatRookPenalty();
        pawnThreatPenalties[QUEEN] = Tuning.pawnThreatQueenPenalty();
    }

    private int midgameScoreCache;
    private int endgameScoreCache;
    private boolean dirty = true;

    @Override
    public void initialize(EvaluationContext context) {
        evaluate(context);
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) {
            return;
        }
        Objects.requireNonNull(context, "context");
        ImmutableBoardView board = context.getBoardView();
        if (board == null) {
            midgameScoreCache = 0;
            endgameScoreCache = 0;
            dirty = false;
            return;
        }

        long whiteAttacks = context.getWhiteAttackMap();
        long blackAttacks = context.getBlackAttackMap();

        int whitePenalty = evaluateSide(board, true, whiteAttacks, blackAttacks);
        int blackPenalty = evaluateSide(board, false, blackAttacks, whiteAttacks);

        midgameScoreCache = whitePenalty - blackPenalty;
        endgameScoreCache = midgameScoreCache;
        dirty = false;
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        dirty = true;
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        dirty = true;
    }

    @Override
    public int getMidgameScore() {
        return midgameScoreCache;
    }

    @Override
    public int getEndgameScore() {
        return endgameScoreCache;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void markDirty() {
        dirty = true;
    }

    private int evaluateSide(ImmutableBoardView board, boolean isWhite, long friendlyAttacks, long enemyAttacks) {
        long pieces = isWhite ? board.getWhitePieces() : board.getBlackPieces();
        long enemyPawns = isWhite ? board.getBlackPawns() : board.getWhitePawns();
        long friendlyPawns = isWhite ? board.getWhitePawns() : board.getBlackPawns();
        int enemyPawnColor = isWhite ? BLACK : WHITE;
        int friendlyPawnColor = isWhite ? WHITE : BLACK;
        long enemyPawnAttacks = computePawnAttackMask(enemyPawns, enemyPawnColor);
        long friendlyPawnAttacks = computePawnAttackMask(friendlyPawns, friendlyPawnColor);
        long occupancy = board.getAllPieces();

        int penalty = 0;
        long remaining = pieces;
        while (remaining != 0) {
            long bit = remaining & -remaining;
            int square = Long.numberOfTrailingZeros(bit);
            PieceType type = board.getPieceTypeAtIndex(square);
            if (type == null) {
                remaining ^= bit;
                continue;
            }
            int typeBits = MoveHelper.pieceTypeToInt(type);
            if (typeBits == KING) {
                remaining ^= bit;
                continue;
            }
            long mask = 1L << square;
            boolean attackedByEnemy = (enemyAttacks & mask) != 0;
            boolean attackedByPawn = (enemyPawnAttacks & mask) != 0;
            if (!attackedByEnemy && !attackedByPawn) {
                remaining ^= bit;
                continue;
            }

            boolean defended = (friendlyAttacks & mask) != 0;
            boolean pawnDefended = (friendlyPawnAttacks & mask) != 0;
            boolean attackedByKnight = attackedByEnemy && isAttackedByKnight(board, !isWhite, square);
            boolean attackedByMinorOrPawn = attackedByPawn
                    || attackedByKnight
                    || (attackedByEnemy && isAttackedBySlidingMinor(board, !isWhite, square, occupancy));

            if (attackedByEnemy && !defended) {
                penalty += hangingPenalties[typeBits];
            } else if (attackedByEnemy && attackedByMinorOrPawn && typeBits != PAWN && !pawnDefended) {
                penalty += Math.max(1, hangingPenalties[typeBits] / 3);
            }

            if (typeBits > PAWN && attackedByPawn) {
                penalty += pawnThreatPenalties[typeBits];
            }

            remaining ^= bit;
        }
        return penalty;
    }

    private static long computePawnAttackMask(long pawns, int pawnColor) {
        long mask = 0L;
        long remaining = pawns;
        while (remaining != 0) {
            long pawn = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(pawn);
            mask |= PAWN_ATTACKS[pawnColor][index];
            remaining ^= pawn;
        }
        return mask;
    }

    private boolean isAttackedByKnight(ImmutableBoardView board, boolean attackerWhite, int targetSquare) {
        long knights = attackerWhite ? board.getWhiteKnights() : board.getBlackKnights();
        long remaining = knights;
        long targetMask = 1L << targetSquare;
        while (remaining != 0) {
            long knight = remaining & -remaining;
            int from = Long.numberOfTrailingZeros(knight);
            if ((KnightHelper.knightMoveTable[from] & targetMask) != 0) {
                return true;
            }
            remaining ^= knight;
        }
        return false;
    }

    private boolean isAttackedBySlidingMinor(ImmutableBoardView board, boolean attackerWhite, int targetSquare,
                                             long occupancy) {
        long targetMask = 1L << targetSquare;

        long bishops = attackerWhite ? board.getWhiteBishops() : board.getBlackBishops();
        long queens = attackerWhite ? board.getWhiteQueens() : board.getBlackQueens();

        long bishopLike = bishops | queens;
        long remaining = bishopLike;
        while (remaining != 0) {
            long piece = remaining & -remaining;
            int from = Long.numberOfTrailingZeros(piece);
            long attacks = BISHOP_HELPER.calculateMovesUsingBishopMagic(from, occupancy);
            if ((attacks & targetMask) != 0) {
                return true;
            }
            remaining ^= piece;
        }

        long rooks = attackerWhite ? board.getWhiteRooks() : board.getBlackRooks();
        long rookLike = rooks | queens;
        remaining = rookLike;
        while (remaining != 0) {
            long piece = remaining & -remaining;
            int from = Long.numberOfTrailingZeros(piece);
            long attacks = ROOK_HELPER.calculateMovesUsingRookMagic(from, occupancy);
            if ((attacks & targetMask) != 0) {
                return true;
            }
            remaining ^= piece;
        }

        long king = attackerWhite ? board.getWhiteKing() : board.getBlackKing();
        if (king != 0) {
            int from = Long.numberOfTrailingZeros(king);
            if ((KingHelper.KING_ATTACKS[from] & targetMask) != 0) {
                return true;
            }
        }

        return false;
    }
}
