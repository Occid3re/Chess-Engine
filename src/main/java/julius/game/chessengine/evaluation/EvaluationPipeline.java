package julius.game.chessengine.evaluation;

import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.utils.Score;
import julius.game.chessengine.tuning.Tuning;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates a set of {@link EvaluationModule modules} and provides tapered evaluation blending.
 * Each module can signal when its cached contribution is stale so that the pipeline avoids
 * recomputing unrelated features.
 */
public final class EvaluationPipeline {

    private static final class ModuleState {
        private final EvaluationModule module;
        private final EvaluationWeights.ModuleWeight weight;
        private int midgameCache;
        private int endgameCache;

        private ModuleState(EvaluationModule module, EvaluationWeights.ModuleWeight weight) {
            this.module = module;
            this.weight = weight;
            this.module.markDirty();
        }
    }

    private final List<ModuleState> modules;
    private final int blendScale;
    @Getter
    private EvaluationContext context;
    @Getter
    private boolean initialized;
    private boolean aggregateDirty = true;
    private int midgameTotal;
    private int endgameTotal;

    public EvaluationPipeline(List<? extends EvaluationModule> modules) {
        this(modules, EvaluationWeights.identity());
    }

    public EvaluationPipeline(List<? extends EvaluationModule> modules, EvaluationWeights weights) {
        if (modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("At least one evaluation module is required");
        }
        EvaluationWeights weights1 = (weights != null ? weights : EvaluationWeights.identity());
        this.blendScale = Tuning.evaluationBlendScale();
        List<ModuleState> moduleStates = new ArrayList<>(modules.size());
        for (EvaluationModule module : modules) {
            EvaluationWeights.ModuleWeight weight = weights1.weightFor(module.getClass());
            moduleStates.add(new ModuleState(module, weight));
        }
        this.modules = Collections.unmodifiableList(moduleStates);
    }

    public void initialize(EvaluationContext context) {
        this.context = Objects.requireNonNull(context, "context");
        for (ModuleState state : modules) {
            state.module.initialize(context);
            state.module.markDirty();
        }
        initialized = true;
        aggregateDirty = true;
        refreshTotals();
    }

    public void updateContext(EvaluationContext context) {
        if (!initialized) {
            initialize(context);
            return;
        }
        this.context = Objects.requireNonNull(context, "context");
        markAllDirty();
    }

    public void applyMove(MoveContext moveContext) {
        ensureInitialized();
        for (ModuleState state : modules) {
            state.module.applyMove(moveContext);
        }
        aggregateDirty = true;
    }

    public void undoMove(MoveContext moveContext) {
        ensureInitialized();
        for (ModuleState state : modules) {
            state.module.undoMove(moveContext);
        }
        aggregateDirty = true;
    }

    public int getMidgameScore() {
        refreshTotals();
        return midgameTotal;
    }

    public int getEndgameScore() {
        refreshTotals();
        return endgameTotal;
    }

    // A/B test knob: disable endgame scaling and mop-up via -Dchessengine.endgameFeatures=0
    private static final boolean ENDGAME_FEATURES = !"0".equals(
            System.getProperty("chessengine.endgameFeatures", "1"));

    public int getBlendedScore() {
        refreshTotals();
        if (context == null) {
            return 0;
        }
        int phase = clamp(context.getPhase());
        int midgameWeight = blendScale - phase;
        int endgameWeight = phase;
        long blended = (long) midgameTotal * midgameWeight + (long) endgameTotal * endgameWeight;
        int score = (int) (blended / blendScale);

        // Tempo bonus
        if (context.isWhiteToMove()) {
            score += 10;
        } else {
            score -= 10;
        }

        if (ENDGAME_FEATURES) {
            // Endgame scaling for drawish positions
            score = applyEndgameScaling(score);

            // Mop-up evaluation: drive losing king to corner in won endgames
            if (phase > 200 && Math.abs(score) >= 200) {
                score += computeMopUpBonus(score);
            }
        }

        return score;
    }

    private int applyEndgameScaling(int score) {
        if (context == null || context.getBoardView() == null) return score;
        var board = context.getBoardView();

        int wPawns = Long.bitCount(board.getWhitePawns());
        int bPawns = Long.bitCount(board.getBlackPawns());
        int wKnights = Long.bitCount(board.getWhiteKnights());
        int bKnights = Long.bitCount(board.getBlackKnights());
        int wBishops = Long.bitCount(board.getWhiteBishops());
        int bBishops = Long.bitCount(board.getBlackBishops());
        int wRooks = Long.bitCount(board.getWhiteRooks());
        int bRooks = Long.bitCount(board.getBlackRooks());
        int wQueens = Long.bitCount(board.getWhiteQueens());
        int bQueens = Long.bitCount(board.getBlackQueens());

        int wMinors = wKnights + wBishops;
        int bMinors = bKnights + bBishops;
        int wMajors = wRooks + wQueens;
        int bMajors = bRooks + bQueens;

        // Insufficient material
        if (wPawns == 0 && wMajors == 0 && wMinors <= 1
                && bPawns == 0 && bMajors == 0 && bMinors <= 1) {
            return score / 16;
        }

        // Lone minor piece advantage without pawns
        if (wPawns == 0 && bPawns == 0 && wMajors == 0 && bMajors == 0
                && Math.abs(wMinors - bMinors) <= 1) {
            return score / 4;
        }

        // Opposite-colored bishops only
        if (wBishops == 1 && bBishops == 1 && wRooks == 0 && bRooks == 0
                && wQueens == 0 && bQueens == 0 && wKnights == 0 && bKnights == 0) {
            long wb = board.getWhiteBishops();
            long bb = board.getBlackBishops();
            int wbSq = Long.numberOfTrailingZeros(wb);
            int bbSq = Long.numberOfTrailingZeros(bb);
            boolean wbLight = ((wbSq >> 3) + (wbSq & 7)) % 2 == 0;
            boolean bbLight = ((bbSq >> 3) + (bbSq & 7)) % 2 == 0;
            if (wbLight != bbLight) {
                score = score / 2;
            }
        }

        // No pawns for winning side with only minor pieces
        boolean whiteWinning = score > 0;
        if (whiteWinning && wPawns == 0 && wMajors == 0 && wMinors <= 2) {
            score = score * 3 / 4;
        } else if (!whiteWinning && bPawns == 0 && bMajors == 0 && bMinors <= 2) {
            score = score * 3 / 4;
        }

        // Rook vs minor piece — rook is usually winning but less so without pawns
        boolean wRookVsMinor = wRooks == 1 && wQueens == 0 && wMinors == 0
                && bRooks == 0 && bQueens == 0 && bMinors == 1;
        boolean bRookVsMinor = bRooks == 1 && bQueens == 0 && bMinors == 0
                && wRooks == 0 && wQueens == 0 && wMinors == 1;
        if (wRookVsMinor || bRookVsMinor) {
            int totalPawns = wPawns + bPawns;
            if (totalPawns == 0) {
                score = score / 2; // rook vs minor without pawns is very drawish
            } else if (totalPawns <= 2) {
                score = score * 3 / 4; // slight scaling with few pawns
            }
        }

        // Passed pawn bonus in endgame — king proximity to passed pawns matters
        if (wPawns + bPawns > 0 && wQueens == 0 && bQueens == 0) {
            score += computePassedPawnKingProximity(board, whiteWinning);
        }

        return score;
    }

    /**
     * In queenless endgames, bonus for the winning king being close to passed pawns
     * and penalty for the losing king being close to enemy passed pawns.
     */
    private int computePassedPawnKingProximity(julius.game.chessengine.board.ImmutableBoardView board,
                                                boolean whiteWinning) {
        long whiteKing = board.getWhiteKing();
        long blackKing = board.getBlackKing();
        if (whiteKing == 0 || blackKing == 0) return 0;

        int wkSq = Long.numberOfTrailingZeros(whiteKing);
        int bkSq = Long.numberOfTrailingZeros(blackKing);
        int bonus = 0;

        // Check for advanced passed pawns (rank 6+) and reward king proximity
        long wPawns = board.getWhitePawns();
        long bPawns = board.getBlackPawns();

        // White advanced pawns (rank 6 = bits 40-47, rank 7 = bits 48-55)
        long wAdvanced = wPawns & 0x00FFFF0000000000L; // rank 6-7
        while (wAdvanced != 0) {
            int sq = Long.numberOfTrailingZeros(wAdvanced);
            wAdvanced &= wAdvanced - 1;
            // Bonus for white king near its own advanced pawn
            int wkDist = Math.max(Math.abs((wkSq & 7) - (sq & 7)), Math.abs((wkSq >> 3) - (sq >> 3)));
            int bkDist = Math.max(Math.abs((bkSq & 7) - (sq & 7)), Math.abs((bkSq >> 3) - (sq >> 3)));
            bonus += (7 - wkDist) * 3;  // reward our king being close
            bonus -= (7 - bkDist) * 5;  // penalize if enemy king is close (blocking)
        }

        // Black advanced pawns (rank 3 = bits 16-23, rank 2 = bits 8-15)
        long bAdvanced = bPawns & 0x0000000000FFFF00L; // rank 2-3
        while (bAdvanced != 0) {
            int sq = Long.numberOfTrailingZeros(bAdvanced);
            bAdvanced &= bAdvanced - 1;
            int wkDist = Math.max(Math.abs((wkSq & 7) - (sq & 7)), Math.abs((wkSq >> 3) - (sq >> 3)));
            int bkDist = Math.max(Math.abs((bkSq & 7) - (sq & 7)), Math.abs((bkSq >> 3) - (sq >> 3)));
            bonus -= (7 - bkDist) * 3;  // reward black king near its pawn
            bonus += (7 - wkDist) * 5;  // penalize if our king is close (blocking)
        }

        return bonus;
    }

    private int computeMopUpBonus(int currentScore) {
        var board = context.getBoardView();
        long whiteKing = board.getWhiteKing();
        long blackKing = board.getBlackKing();
        if (whiteKing == 0 || blackKing == 0) return 0;

        int wkSq = Long.numberOfTrailingZeros(whiteKing);
        int bkSq = Long.numberOfTrailingZeros(blackKing);
        boolean whiteWinning = currentScore > 0;
        int losingKingSq = whiteWinning ? bkSq : wkSq;

        int losingFile = losingKingSq & 7;
        int losingRank = losingKingSq >> 3;
        int fileDist = Math.max(3 - losingFile, losingFile - 4);
        int rankDist = Math.max(3 - losingRank, losingRank - 4);
        int cornerDistance = fileDist + rankDist;

        int kingFileDiff = Math.abs((wkSq & 7) - (bkSq & 7));
        int kingRankDiff = Math.abs((wkSq >> 3) - (bkSq >> 3));
        int kingDist = Math.max(kingFileDiff, kingRankDiff);
        int closenessBonus = Math.max(0, 7 - kingDist);

        int mopUp = cornerDistance * 5 + closenessBonus * 3;
        return whiteWinning ? mopUp : -mopUp;
    }

    public double getScoreDifference() {
        return getBlendedScore() / 100.0;
    }

    private void refreshTotals() {
        ensureInitialized();
        if (!aggregateDirty) {
            return;
        }
        double midgame = 0.0;
        double endgame = 0.0;
        for (ModuleState state : modules) {
            if (state.module.isDirty()) {
                state.module.evaluate(context);
            }
            state.midgameCache = state.module.getMidgameScore();
            state.endgameCache = state.module.getEndgameScore();
            midgame += state.midgameCache * state.weight.midgame();
            endgame += state.endgameCache * state.weight.endgame();
        }
        int checkAdjustment = computeCheckAdjustment();
        midgame += checkAdjustment;
        endgame += checkAdjustment;
        midgameTotal = (int) Math.round(midgame);
        endgameTotal = (int) Math.round(endgame);
        aggregateDirty = false;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Pipeline has not been initialized yet");
        }
    }

    private void markAllDirty() {
        for (ModuleState state : modules) {
            state.module.markDirty();
        }
        aggregateDirty = true;
    }

    private int clamp(int phase) {
        if (phase < 0) {
            return 0;
        }
        return Math.min(phase, blendScale);
    }

    private int computeCheckAdjustment() {
        if (context == null) {
            return 0;
        }
        GameStateEnum state = context.getGameState();
        if (state == null) {
            return 0;
        }
        return switch (state) {
            case BLACK_IN_CHECK -> Score.CHECK;
            case WHITE_IN_CHECK -> -Score.CHECK;
            default -> 0;
        };
    }
}
