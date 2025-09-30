package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.tuning.AiTuning;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testsupport.InstrumentedTranspositionTable;
import testsupport.TestLoggingExtension;
import testsupport.TestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Log4j2
@ExtendWith(TestLoggingExtension.class)
class AITest_PVReconstruction {

    @Test
    @DisplayName("PV reconstruction stops at the first non-EXACT entry")
    void pvStopsAtNonExactEntry() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine, AiTuning.defaults());

        InstrumentedTranspositionTable<TranspositionTableEntry> tt = new InstrumentedTranspositionTable<>();
        TestUtils.writeField(ai, "transpositionTable", tt);

        IntArrayList rootMoves = engine.getAllLegalMoves();
        assertTrue(rootMoves.size() >= 2, "Opening position must expose enough moves for PV test");
        int rootMove = rootMoves.getInt(0);
        Engine afterRoot = engine.createSimulation();
        afterRoot.performMove(rootMove);

        IntArrayList nextMoves = afterRoot.getAllLegalMoves();
        assertFalse(nextMoves.isEmpty(), "Second ply must have at least one legal move");
        int childMove = nextMoves.getInt(0);
        Engine afterChild = afterRoot.createSimulation();
        afterChild.performMove(childMove);

        IntArrayList thirdMoves = afterChild.getAllLegalMoves();
        int terminatingMove = thirdMoves.isEmpty() ? -1 : thirdMoves.getInt(0);

        long rootHash = engine.getBoardStateHash();
        long childHash = afterRoot.getBoardStateHash();
        long thirdHash = afterChild.getBoardStateHash();

        tt.put(rootHash, new TranspositionTableEntry(0.75, 4, NodeType.EXACT, rootMove), 4);
        tt.put(childHash, new TranspositionTableEntry(0.60, 3, NodeType.EXACT, childMove), 3);
        if (terminatingMove != -1) {
            tt.put(thirdHash, new TranspositionTableEntry(0.25, 2, NodeType.LOWERBOUND, terminatingMove), 2);
        }

        Method fill = AI.class.getDeclaredMethod("fillCalculatedLine", Engine.class);
        fill.setAccessible(true);
        fill.invoke(ai, engine.createSimulation());

        @SuppressWarnings("unchecked")
        List<MoveAndScore> pv = (List<MoveAndScore>) TestUtils.readField(ai, "calculatedLine");
        log.info("Reconstructed PV: {}", pv);

        assertEquals(2, pv.size(), "PV should include root and first child but stop at non-EXACT entry");
        assertEquals(rootMove, pv.get(0).move);
        assertEquals(childMove, pv.get(1).move);
    }

    @Test
    @DisplayName("Terminal roots skip PV reconstruction and avoid performing moves")
    void terminalRootSkipsPv() throws Exception {
        TrackingEngine engine = new TrackingEngine();
        engine.getGameState().setState(GameStateEnum.DRAW);

        AI ai = new AI(engine, AiTuning.defaults());
        TestUtils.writeField(ai, "transpositionTable", new InstrumentedTranspositionTable<>());

        Method fill = AI.class.getDeclaredMethod("fillCalculatedLine", Engine.class);
        fill.setAccessible(true);
        fill.invoke(ai, engine);

        @SuppressWarnings("unchecked")
        List<MoveAndScore> pv = (List<MoveAndScore>) TestUtils.readField(ai, "calculatedLine");
        assertTrue(pv.isEmpty(), "Terminal nodes should yield an empty PV");
        assertEquals(0, engine.performCount, "Terminal guard must avoid applying moves");
    }

    private static class TrackingEngine extends Engine {
        private int performCount;

        TrackingEngine() {
            super();
        }

        TrackingEngine(Engine other) {
            super(other);
        }

        @Override
        public void performMove(int move) {
            performCount++;
            super.performMove(move);
        }
    }
}

