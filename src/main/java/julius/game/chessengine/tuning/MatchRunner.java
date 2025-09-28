package julius.game.chessengine.tuning;

import julius.game.chessengine.ai.AI;
import julius.game.chessengine.ai.MoveAndScore;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.utils.Score;

import java.util.Objects;

/**
 * Utility capable of staging self-play games between two {@link EngineTuning} configurations. The
 * runner keeps the board state of both engines synchronised and returns a lightweight summary of
 * the encounter.
 */
public final class MatchRunner {

    public MatchResult playMatch(EngineTuning whiteTuning, EngineTuning blackTuning, MatchOptions options) {
        Objects.requireNonNull(whiteTuning, "whiteTuning");
        Objects.requireNonNull(blackTuning, "blackTuning");
        Objects.requireNonNull(options, "options");

        ensureThreadParity(whiteTuning, blackTuning);

        Engine whiteEngine = createEngineForTuning(whiteTuning);
        Engine blackEngine = createEngineForTuning(blackTuning);

        AI whiteAi = new AI(whiteEngine, whiteTuning.ai());
        AI blackAi = new AI(blackEngine, blackTuning.ai());

        try {
            int maxPlies = options.maxPlies() > 0 ? options.maxPlies() : 512;
            long moveTime = options.moveTimeMillis() > 0 ? options.moveTimeMillis() : whiteTuning.ai().timeLimitMillis();

            int plies = 0;
            while (!whiteEngine.getGameState().isGameOver() && plies < maxPlies) {
                boolean whiteToMove = whiteEngine.whitesTurn();
                AI mover = whiteToMove ? whiteAi : blackAi;
                MoveAndScore ms = mover.searchBestMoveBlocking(moveTime);
                if (ms == null || ms.getMove() == -1) {
                    break;
                }
                int move = ms.getMove();
                whiteEngine.performMove(move);
                blackEngine.performMove(move);
                plies++;
            }

            GameStateEnum finalState = whiteEngine.getGameState().getState();
            double whiteScore;
            double blackScore;
            switch (finalState) {
                case WHITE_WON -> {
                    whiteScore = 1.0;
                    blackScore = 0.0;
                }
                case BLACK_WON -> {
                    whiteScore = 0.0;
                    blackScore = 1.0;
                }
                case DRAW -> {
                    whiteScore = 0.5;
                    blackScore = 0.5;
                }
                default -> {
                    whiteScore = 0.5;
                    blackScore = 0.5;
                }
            }

            return new MatchResult(whiteTuning, blackTuning, whiteScore, blackScore, finalState, plies);
        } finally {
            whiteAi.shutdown();
            blackAi.shutdown();
        }
    }

    private void ensureThreadParity(EngineTuning white, EngineTuning black) {
        int whiteThreads = white.ai().searchThreads();
        int blackThreads = black.ai().searchThreads();
        if (whiteThreads != blackThreads) {
            throw new IllegalArgumentException("All participants must use the same number of search threads");
        }
    }

    private Engine createEngineForTuning(EngineTuning tuning) {
        try (AutoCloseable ignored = Score.useEvaluationConfiguration(tuning.evaluationWeights(), tuning.evaluationParameters())) {
            return new Engine();
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Failed to initialise engine for tuning " + tuning.name(), e);
        }
    }

    public record MatchOptions(int maxPlies, long moveTimeMillis) {
        public static MatchOptions defaults() {
            return new MatchOptions(512, 50L);
        }
    }

    public record MatchResult(EngineTuning whiteTuning,
                              EngineTuning blackTuning,
                              double whiteScore,
                              double blackScore,
                              GameStateEnum finalState,
                              int plies) {
    }
}
