package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TestSyzygyTablebaseService;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.tuning.Tuning;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AITablebaseTieBreakTest {

    private static final String ROOT_FEN = "8/8/3k4/1P6/3P4/6K1/8/7B w - - 1 57";

    @BeforeEach
    void refreshTuning() {
        Tuning.refresh();
    }

    @Test
    void winningTiePrefersLowerDtz() throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen(ROOT_FEN);
        IntArrayList legalMoves = engine.getAllLegalMoves();

        int matePush = findMove(legalMoves, "b5", "b6");
        int quietMove = findFirstNonZeroing(legalMoves, matePush);

        Map<String, SyzygyProbeResult> responses = new HashMap<>();
        responses.put(fenAfterMove(engine, matePush), winningProbe(4));
        responses.put(fenAfterMove(engine, quietMove), winningProbe(8));

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(responses);

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            AI ai = new AI(engine, AiTuning.defaults(), service);
            try {
                Engine simulation = engine.createSimulation();
                boolean preferred = invokeTieBreak(ai, simulation, matePush, quietMove,
                        isZeroing(matePush), isZeroing(quietMove));
                assertThat(preferred).isTrue();
            } finally {
                ai.shutdown();
            }
        }
    }

    @Test
    void winningTiePrefersZeroingWhenDistancesMatch() throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen(ROOT_FEN);
        IntArrayList legalMoves = engine.getAllLegalMoves();

        int matePush = findMove(legalMoves, "b5", "b6");
        int quietMove = findFirstNonZeroing(legalMoves, matePush);

        Map<String, SyzygyProbeResult> responses = new HashMap<>();
        responses.put(fenAfterMove(engine, matePush), winningProbe(6));
        responses.put(fenAfterMove(engine, quietMove), winningProbe(6));

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(responses);

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            AI ai = new AI(engine, AiTuning.defaults(), service);
            try {
                Engine simulation = engine.createSimulation();
                boolean preferred = invokeTieBreak(ai, simulation, matePush, quietMove,
                        isZeroing(matePush), isZeroing(quietMove));
                assertThat(preferred).isTrue();
            } finally {
                ai.shutdown();
            }
        }
    }

    @Test
    void losingTiePrefersLargerDtz() throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen(ROOT_FEN);
        IntArrayList legalMoves = engine.getAllLegalMoves();

        int matePush = findMove(legalMoves, "b5", "b6");
        int quietMove = findFirstNonZeroing(legalMoves, matePush);

        Map<String, SyzygyProbeResult> responses = new HashMap<>();
        responses.put(fenAfterMove(engine, quietMove), losingProbe(12));
        responses.put(fenAfterMove(engine, matePush), losingProbe(6));

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(responses);

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            AI ai = new AI(engine, AiTuning.defaults(), service);
            try {
                Engine simulation = engine.createSimulation();
                boolean preferred = invokeTieBreak(ai, simulation, quietMove, matePush,
                        isZeroing(quietMove), isZeroing(matePush));
                assertThat(preferred).isTrue();
            } finally {
                ai.shutdown();
            }
        }
    }

    @Test
    void losingTieAvoidsZeroingWhenDistancesMatch() throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen(ROOT_FEN);
        IntArrayList legalMoves = engine.getAllLegalMoves();

        int matePush = findMove(legalMoves, "b5", "b6");
        int quietMove = findFirstNonZeroing(legalMoves, matePush);

        Map<String, SyzygyProbeResult> responses = new HashMap<>();
        responses.put(fenAfterMove(engine, quietMove), losingProbe(8));
        responses.put(fenAfterMove(engine, matePush), losingProbe(8));

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(responses);

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            AI ai = new AI(engine, AiTuning.defaults(), service);
            try {
                Engine simulation = engine.createSimulation();
                boolean preferred = invokeTieBreak(ai, simulation, quietMove, matePush,
                        isZeroing(quietMove), isZeroing(matePush));
                assertThat(preferred).isTrue();
            } finally {
                ai.shutdown();
            }
        }
    }

    private static AutoCloseable overrideScoreTablebase(SyzygyTablebaseService service) {
        SyzygyTablebaseService previous = Score.getTablebaseService();
        Score.setTablebaseService(service);
        return () -> {
            if (previous == null) {
                Score.clearTablebaseService();
            } else {
                Score.setTablebaseService(previous);
            }
        };
    }

    private static boolean invokeTieBreak(AI ai, Engine simulation,
                                          int candidateMove, int bestMove,
                                          boolean candidateZeroing, boolean bestZeroing) throws Exception {
        Method method = AI.class.getDeclaredMethod("preferCandidateByTablebase", Engine.class, int.class,
                double.class, boolean.class, int.class, boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(ai, simulation, candidateMove, 0.0,
                candidateZeroing, bestMove, bestZeroing);
    }

    private static SyzygyProbeResult winningProbe(int dtz) {
        return new SyzygyProbeResult(SyzygyWdl.LOSS, OptionalInt.of(dtz), OptionalInt.empty(), Optional.empty());
    }

    private static SyzygyProbeResult losingProbe(int dtz) {
        return new SyzygyProbeResult(SyzygyWdl.WIN, OptionalInt.of(dtz), OptionalInt.empty(), Optional.empty());
    }

    private static int findMove(IntArrayList moves, String from, String to) {
        int fromIndex = MoveHelper.convertStringToIndex(from);
        int toIndex = MoveHelper.convertStringToIndex(to);
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getInt(i);
            if (MoveHelper.deriveFromIndex(move) == fromIndex && MoveHelper.deriveToIndex(move) == toIndex) {
                return move;
            }
        }
        throw new IllegalStateException("No legal move " + from + to + " found");
    }

    private static int findFirstNonZeroing(IntArrayList moves, int... excluded) {
        Set<Integer> skip = new HashSet<>();
        for (int value : excluded) {
            skip.add(value);
        }
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getInt(i);
            if (skip.contains(move)) {
                continue;
            }
            if (!isZeroing(move)) {
                return move;
            }
        }
        throw new IllegalStateException("Expected at least one non-zeroing move");
    }

    private static boolean isZeroing(int move) {
        return MoveHelper.isCapture(move) || MoveHelper.derivePieceTypeBits(move) == MoveHelper.pieceTypeToInt(PieceType.PAWN);
    }

    private static String fenAfterMove(Engine engine, int move) {
        engine.performMove(move);
        try {
            return FEN.translateBoardToFEN(engine.getBitBoard(), engine.getGameState()).getRenderBoard();
        } finally {
            engine.undoLastMove();
        }
    }
}
