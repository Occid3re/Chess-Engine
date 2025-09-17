package julius.game.chessengine.utils.eval;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.helper.BitHelper;
import julius.game.chessengine.helper.PawnHelper;
import julius.game.chessengine.helper.RookHelper;
import julius.game.chessengine.utils.Color;

import java.util.EnumMap;

import static julius.game.chessengine.helper.PawnHelper.BLACK_PAWN_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.PawnHelper.WHITE_PAWN_POSITIONAL_VALUES;

/**
 * Maintains pawn structure evaluation caches for both colours.
 */
public class PawnStructureModule {

    static final int DOUBLED_PAWN_PENALTY = -20;
    static final int ISOLATED_PAWN_PENALTY = -10;
    static final int CENTER_PAWN_BONUS = 20;
    static final int PASSED_PAWN_MIDGAME_BONUS = 60;
    static final int PASSED_PAWN_ENDGAME_BONUS = 120;
    static final int ROOK_HALF_OPEN_FILE_BONUS = 25;
    static final int ROOK_OPEN_FILE_BONUS = 12;

    private final EnumMap<Color, PhaseScore> cachedScores;

    public PawnStructureModule() {
        this.cachedScores = new EnumMap<>(Color.class);
        reset();
    }

    public PawnStructureModule(PawnStructureModule other) {
        this();
        for (Color color : Color.values()) {
            PhaseScore otherScore = other.cachedScores.get(color);
            cachedScores.put(color, otherScore == null ? new PhaseScore(0, 0)
                    : new PhaseScore(otherScore.midgame(), otherScore.endgame()));
        }
    }

    /**
     * Clears all cached values.
     */
    public void reset() {
        for (Color color : Color.values()) {
            cachedScores.put(color, new PhaseScore(0, 0));
        }
    }

    /**
     * Initialises the module from scratch for the supplied board position.
     */
    public void initialize(BitBoard bitBoard) {
        refresh(bitBoard);
    }

    /**
     * Recomputes the cached values using the current board state.
     */
    public void refresh(BitBoard bitBoard) {
        cachedScores.put(Color.WHITE, computeScore(bitBoard, Color.WHITE));
        cachedScores.put(Color.BLACK, computeScore(bitBoard, Color.BLACK));
    }

    public int getMidgameContribution(Color color) {
        return cachedScores.get(color).midgame();
    }

    public int getEndgameContribution(Color color) {
        return cachedScores.get(color).endgame();
    }

    private PhaseScore computeScore(BitBoard bitBoard, Color color) {
        long pawns = color == Color.WHITE ? bitBoard.getWhitePawns() : bitBoard.getBlackPawns();
        long opponentPawns = color == Color.WHITE ? bitBoard.getBlackPawns() : bitBoard.getWhitePawns();
        long rooks = color == Color.WHITE ? bitBoard.getWhiteRooks() : bitBoard.getBlackRooks();
        long allPawns = bitBoard.getWhitePawns() | bitBoard.getBlackPawns();

        int midgame = 0;
        int endgame = 0;

        int centerControl = PawnHelper.countCenterPawns(pawns) * CENTER_PAWN_BONUS;
        midgame += centerControl;
        endgame += centerControl;

        int doubledPenalty = PawnHelper.countDoubledPawns(pawns) * DOUBLED_PAWN_PENALTY;
        midgame += doubledPenalty;
        endgame += doubledPenalty;

        int isolatedPenalty = PawnHelper.countIsolatedPawns(pawns) * ISOLATED_PAWN_PENALTY;
        midgame += isolatedPenalty;
        endgame += isolatedPenalty;

        int positional = applyPositionalValues(pawns,
                color == Color.WHITE ? WHITE_PAWN_POSITIONAL_VALUES : BLACK_PAWN_POSITIONAL_VALUES);
        midgame += positional;
        endgame += positional;

        int halfOpenFileBonus = RookHelper.countRooksOnHalfOpenFiles(rooks, pawns, opponentPawns)
                * ROOK_HALF_OPEN_FILE_BONUS;
        midgame += halfOpenFileBonus;
        endgame += halfOpenFileBonus;

        int openFileBonus = RookHelper.countRooksOnOpenFiles(rooks, allPawns) * ROOK_OPEN_FILE_BONUS;
        midgame += openFileBonus;
        endgame += openFileBonus;

        int passedPawns = countPassedPawns(pawns, opponentPawns, color);
        midgame += passedPawns * PASSED_PAWN_MIDGAME_BONUS;
        endgame += passedPawns * PASSED_PAWN_ENDGAME_BONUS;

        return new PhaseScore(midgame, endgame);
    }

    private int applyPositionalValues(long bitboard, int[] positionalValues) {
        if (bitboard == 0L) {
            return 0;
        }

        int score = 0;
        int start = Long.numberOfTrailingZeros(bitboard);
        int end = 64 - Long.numberOfLeadingZeros(bitboard);
        for (int i = start; i < end; i++) {
            long mask = 1L << i;
            if ((bitboard & mask) != 0) {
                score += positionalValues[i];
            }
        }
        return score;
    }

    private int countPassedPawns(long pawns, long opponentPawns, Color color) {
        int passed = 0;
        long remaining = pawns;
        while (remaining != 0) {
            int square = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            if (isPassedPawn(square, opponentPawns, color)) {
                passed++;
            }
        }
        return passed;
    }

    private boolean isPassedPawn(int square, long opponentPawns, Color color) {
        int file = square % 8;
        int rank = square / 8;
        long mask = 0L;

        if (color == Color.WHITE) {
            for (int r = rank + 1; r < 8; r++) {
                mask |= BitHelper.RankMasks[r] & BitHelper.FileMasks[file];
                if (file > 0) {
                    mask |= BitHelper.RankMasks[r] & BitHelper.FileMasks[file - 1];
                }
                if (file < 7) {
                    mask |= BitHelper.RankMasks[r] & BitHelper.FileMasks[file + 1];
                }
            }
        } else {
            for (int r = rank - 1; r >= 0; r--) {
                mask |= BitHelper.RankMasks[r] & BitHelper.FileMasks[file];
                if (file > 0) {
                    mask |= BitHelper.RankMasks[r] & BitHelper.FileMasks[file - 1];
                }
                if (file < 7) {
                    mask |= BitHelper.RankMasks[r] & BitHelper.FileMasks[file + 1];
                }
            }
        }

        return (opponentPawns & mask) == 0;
    }

    public record PhaseScore(int midgame, int endgame) { }
}
