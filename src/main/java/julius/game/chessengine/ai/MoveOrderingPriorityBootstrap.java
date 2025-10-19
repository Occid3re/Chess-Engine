package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.tuning.AiTuning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
    * Boots a sequence of self-play games so {@link MoveOrderingPriority} can seed its persistent store with
    * move integers that previously resulted in wins. Each match reuses identical tunings for both sides and
    * runs with conservative search limits so the generator can execute quickly on developer hardware.
    *
    * <p>The bootstrapper resets the priority store, records every move executed by the winning side, and
    * validates that the persisted priorities match the observed win/loss delta before exiting.</p>
    */
public final class MoveOrderingPriorityBootstrap {

    private static final int DEFAULT_GAME_COUNT = 100;
    private static final long DEFAULT_MOVE_TIME_MS = 10L;
    private static final int DEFAULT_MAX_PLIES = 160;

    private MoveOrderingPriorityBootstrap() {
    }

    public static void main(String[] args) throws Exception {
        int games = parseIntArg(args, 0, DEFAULT_GAME_COUNT);
        long moveTime = parseLongArg(args, 1, DEFAULT_MOVE_TIME_MS);
        int maxPlies = parseIntArg(args, 2, DEFAULT_MAX_PLIES);

        System.setProperty("chessengine.moveOrdering.reviseGame", "true");

        Path store = MoveOrderingPriority.defaultStoragePath();
        Files.deleteIfExists(store);
        MoveOrderingPriority.resetForTests(store);

        MoveOrderingPriority priority = MoveOrderingPriority.getInstance();
        Int2IntOpenHashMap delta = new Int2IntOpenHashMap();
        delta.defaultReturnValue(0);

        List<GameSummary> summaries = new ArrayList<>(games);

        for (int i = 0; i < games; i++) {
            GameOutcome outcome = playSelfMatch(moveTime, maxPlies);
            summaries.add(new GameSummary(i + 1, outcome.result(), outcome.plies()));

            if (outcome.result() == GameStateEnum.WHITE_WON) {
                accumulate(delta, outcome.whiteMoves(), +1);
                accumulate(delta, outcome.blackMoves(), -1);
            } else if (outcome.result() == GameStateEnum.BLACK_WON) {
                accumulate(delta, outcome.blackMoves(), +1);
                accumulate(delta, outcome.whiteMoves(), -1);
            }
        }

        Path persistedPath = priority.getStoragePath();
        List<String> lines = Files.exists(persistedPath)
                ? Files.readAllLines(persistedPath, StandardCharsets.UTF_8)
                : List.of();
        Int2IntOpenHashMap persisted = parse(lines);

        validate(delta, persisted);

        System.out.printf(Locale.ROOT, "Generated %d move priorities at %s%n", persisted.size(), persistedPath);
        for (GameSummary summary : summaries) {
            System.out.printf(Locale.ROOT, "Game %d: result=%s plies=%d%n",
                    summary.index(), summary.result(), summary.plies());
        }
    }

    private static GameOutcome playSelfMatch(long moveTime, int maxPlies) {
        Engine whiteEngine = new Engine();
        Engine blackEngine = new Engine();

        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
                .hashSizeMb(16)
                .timeLimitMillis(moveTime)
                .build();

        AI whiteAi = new AI(whiteEngine, tuning);
        AI blackAi = new AI(blackEngine, tuning);

        IntArrayList whiteMoves = new IntArrayList();
        IntArrayList blackMoves = new IntArrayList();

        int plies = 0;
        try {
            while (!whiteEngine.getGameState().isGameOver() && plies < maxPlies) {
                boolean whitesTurn = whiteEngine.whitesTurn();
                AI mover = whitesTurn ? whiteAi : blackAi;
                MoveAndScore ms = mover.searchBestMoveBlocking(moveTime);
                if (ms == null || ms.getMove() == -1) {
                    break;
                }
                int move = ms.getMove();
                whiteEngine.performMove(move);
                blackEngine.performMove(move);
                mover.onMoveExecuted(move);
                if (whitesTurn) {
                    whiteMoves.add(move);
                } else {
                    blackMoves.add(move);
                }
                plies++;
            }
        } finally {
            whiteAi.shutdown();
            blackAi.shutdown();
        }

        return new GameOutcome(whiteEngine.getGameState().getState(), whiteMoves, blackMoves, plies);
    }

    private static void accumulate(Int2IntOpenHashMap map, IntArrayList moves, int delta) {
        for (int i = 0; i < moves.size(); i++) {
            map.addTo(moves.getInt(i), delta);
        }
    }

    private static Int2IntOpenHashMap parse(List<String> lines) {
        Int2IntOpenHashMap result = new Int2IntOpenHashMap();
        result.defaultReturnValue(0);
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            if (tokens.length < 2) {
                continue;
            }
            try {
                int move = Integer.parseInt(tokens[0]);
                int score = Integer.parseInt(tokens[1]);
                result.put(move, score);
            } catch (NumberFormatException ignore) {
                // Skip malformed entries; they will be handled during validation
            }
        }
        return result;
    }

    private static void validate(Int2IntOpenHashMap delta, Int2IntOpenHashMap persisted) {
        Int2IntOpenHashMap expected = new Int2IntOpenHashMap(delta);
        expected.defaultReturnValue(0);

        for (Int2IntMap.Entry entry : persisted.int2IntEntrySet()) {
            int move = entry.getIntKey();
            int value = entry.getIntValue();
            int observed = expected.get(move);
            if (value != observed) {
                throw new IllegalStateException("Mismatch for move " + move + ": expected " + observed + " but found " + value);
            }
            if (value > 0 && observed <= 0) {
                throw new IllegalStateException("Positive priority for move " + move + " without a matching win record");
            }
            expected.remove(move);
        }

        for (Int2IntMap.Entry entry : expected.int2IntEntrySet()) {
            if (entry.getIntValue() != 0) {
                throw new IllegalStateException("Missing persisted priority for move " + entry.getIntKey());
            }
        }
    }

    private static int parseIntArg(String[] args, int index, int fallback) {
        if (args == null || index >= args.length) {
            return fallback;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long parseLongArg(String[] args, int index, long fallback) {
        if (args == null || index >= args.length) {
            return fallback;
        }
        try {
            return Long.parseLong(args[index]);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private record GameOutcome(GameStateEnum result,
                               IntArrayList whiteMoves,
                               IntArrayList blackMoves,
                               int plies) {
    }

    private record GameSummary(int index, GameStateEnum result, int plies) {
    }
}
