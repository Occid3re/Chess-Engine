package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.pgn.OpeningPgnReader;
import julius.game.chessengine.pgn.OpeningPgnReader.GameConsumer;
import julius.game.chessengine.pgn.OpeningPgnReader.ParsedGame;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Command-line utility that imports PGN games and feeds the resulting move priorities into
 * {@link MoveOrderingPriority}. Games where the result is decisive ({@code 1-0} or {@code 0-1})
 * apply a +1 delta to the winner's moves and -1 to the loser. The importer validates on exit that
 * the persisted priority file reflects the accumulated deltas.
 */
@Log4j2
public final class MoveOrderingPriorityImporter {

    private record Config(Path pgnPath,
                          int limit,
                          boolean verify,
                          boolean dryRun,
                          boolean report,
                          boolean reset,
                          int minElo) { }

    private record Stats(int gamesProcessed,
                         int whiteWins,
                         int blackWins,
                         int movesProcessed,
                         int gamesSkippedByResult,
                         int gamesSkippedByElo,
                         int filesProcessed) { }

    private MoveOrderingPriorityImporter() {
    }

    public static void main(String[] args) throws Exception {
        Config config = parseArgs(args);
        if (config == null) {
            System.err.println("""
                    Usage: MoveOrderingPriorityImporter <pgnPath> [--limit=N] [--no-verify] [--dry-run] [--report] [--reset] [--min-elo=N]
                    
                      <pgnPath>   Path to the PGN file to import.
                      --limit=N   Process at most N decisive games (default: all).
                      --no-verify Skip stored priority verification.
                      --dry-run   Compute deltas without applying them to the store.
                      --report    Print the per-move delta report after processing.
                      --reset     Clear the priority store before importing.
                      --min-elo=N Only process games where max(WhiteElo, BlackElo) ≥ N (default: 0).
                    """.strip());
            System.exit(1);
            return;
        }

        MoveOrderingPriority priority = MoveOrderingPriority.getInstance();
        Path storePath = priority.getStoragePath();

        if (config.reset()) {
            resetStore(storePath);
            priority = MoveOrderingPriority.getInstance();
            storePath = priority.getStoragePath();
        }

        Int2IntOpenHashMap before = readPriorities(storePath);
        Int2IntOpenHashMap delta = new Int2IntOpenHashMap();
        delta.defaultReturnValue(0);

        Stats stats = processGames(config, priority, delta);

        if (!config.dryRun()) {
            // re-read after updates
            Int2IntOpenHashMap after = readPriorities(storePath);
            if (config.verify()) {
                verify(storePath, before, after, delta);
            }
            logSummary(config, stats, before, after, delta);
            if (config.report()) {
                printDeltaReport(delta);
            }
        } else {
            verifyDryRun(config, before, delta);
            logDryRunSummary(config, stats, before, delta);
            if (config.report()) {
                printDeltaReport(delta);
            }
        }
    }

    private static Config parseArgs(String[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Path pgnPath = null;
        int limit = 0;
        boolean verify = true;
        boolean dryRun = false;
        boolean report = false;
        boolean reset = false;
        int minElo = 0;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (arg.startsWith("--limit=")) {
                String raw = arg.substring("--limit=".length());
                try {
                    limit = Integer.parseInt(raw);
                    if (limit < 0) {
                        limit = 0;
                    }
                } catch (NumberFormatException ex) {
                    log.warn("Ignoring invalid limit '{}'", raw);
                }
            } else if (arg.equals("--no-verify")) {
                verify = false;
            } else if (arg.equals("--dry-run")) {
                dryRun = true;
            } else if (arg.equals("--report")) {
                report = true;
            } else if (arg.equals("--reset")) {
                reset = true;
            } else if (arg.startsWith("--min-elo=")) {
                String raw = arg.substring("--min-elo=".length());
                try {
                    minElo = Integer.parseInt(raw);
                    if (minElo < 0) {
                        minElo = 0;
                    }
                } catch (NumberFormatException ex) {
                    log.warn("Ignoring invalid min-elo '{}'", raw);
                }
            } else if (arg.startsWith("--")) {
                log.warn("Unknown argument {}", arg);
            } else {
                pgnPath = Paths.get(arg);
            }
        }
        if (pgnPath == null) {
            return null;
        }
        return new Config(pgnPath, limit, verify, dryRun, report, reset, minElo);
    }

    private static Stats processGames(Config config,
                                      MoveOrderingPriority priority,
                                      Int2IntOpenHashMap delta) throws IOException {
        if (!Files.exists(config.pgnPath())) {
            throw new IOException("PGN file not found: " + config.pgnPath());
        }
        List<Path> sources = collectSources(config.pgnPath());
        if (sources.isEmpty()) {
            throw new IOException("No PGN files discovered under " + config.pgnPath());
        }

        OpeningPgnReader reader = new OpeningPgnReader();
        Processor processor = new Processor(config, priority, delta);

        for (Path source : sources) {
            if (!processor.shouldContinue()) {
                break;
            }
            processor.beginFile(source);
            reader.stream(source, processor);
            if (!processor.shouldContinue()) {
                break;
            }
        }
        return processor.snapshot();
    }

    private static Int2IntOpenHashMap readPriorities(Path storePath) throws IOException {
        Int2IntOpenHashMap map = new Int2IntOpenHashMap();
        map.defaultReturnValue(0);
        if (storePath == null || !Files.exists(storePath)) {
            return map;
        }
        List<String> lines = Files.readAllLines(storePath, StandardCharsets.UTF_8);
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
                int value = Integer.parseInt(tokens[1]);
                map.put(move, value);
            } catch (NumberFormatException ex) {
                log.warn("Ignoring malformed priority entry '{}'", line);
            }
        }
        return map;
    }

    private static void verify(Path storePath,
                               Int2IntOpenHashMap before,
                               Int2IntOpenHashMap after,
                               Int2IntOpenHashMap delta) {
        Int2IntOpenHashMap expected = new Int2IntOpenHashMap(before);
        expected.defaultReturnValue(0);
        for (Int2IntMap.Entry entry : delta.int2IntEntrySet()) {
            expected.addTo(entry.getIntKey(), entry.getIntValue());
        }
        pruneZeroEntries(expected);

        compareMaps(storePath, expected, after);
    }

    private static void verifyDryRun(Config config,
                                     Int2IntOpenHashMap before,
                                     Int2IntOpenHashMap delta) throws IOException {
        Path storePath = MoveOrderingPriority.getInstance().getStoragePath();
        Int2IntOpenHashMap current = readPriorities(storePath);
        compareMaps(storePath, before, current);
        log.info("Dry-run verification: priority file unchanged ({} entries). Pending delta count: {}",
                before.size(), delta.size());
    }

    private static void compareMaps(Path storePath,
                                    Int2IntOpenHashMap expected,
                                    Int2IntOpenHashMap actual) {
        for (Int2IntMap.Entry entry : expected.int2IntEntrySet()) {
            int move = entry.getIntKey();
            int expectedValue = entry.getIntValue();
            int actualValue = actual.get(move);
            if (expectedValue != actualValue) {
                throw new IllegalStateException(
                        String.format(Locale.ROOT,
                                "Mismatch for move %d in %s: expected %d but found %d",
                                move, storePath, expectedValue, actualValue));
            }
        }
        for (Int2IntMap.Entry entry : actual.int2IntEntrySet()) {
            int move = entry.getIntKey();
            if (!expected.containsKey(move)) {
                throw new IllegalStateException(
                        String.format(Locale.ROOT,
                                "Unexpected priority entry for move %d with value %d in %s",
                                move, entry.getIntValue(), storePath));
            }
        }
    }

    private static void pruneZeroEntries(Int2IntOpenHashMap map) {
        IntArrayList toRemove = new IntArrayList();
        for (Int2IntMap.Entry entry : map.int2IntEntrySet()) {
            if (entry.getIntValue() == 0) {
                toRemove.add(entry.getIntKey());
            }
        }
        for (int i = 0; i < toRemove.size(); i++) {
            map.remove(toRemove.getInt(i));
        }
    }

    private static void logSummary(Config config,
                                   Stats stats,
                                   Int2IntOpenHashMap before,
                                   Int2IntOpenHashMap after,
                                   Int2IntOpenHashMap delta) {
        log.info("Imported {} decisive games (white wins: {}, black wins: {}) from {} (files processed: {}, skipped draws: {}, skipped by ELO: {})",
                stats.gamesProcessed, stats.whiteWins, stats.blackWins, config.pgnPath(),
                stats.filesProcessed, stats.gamesSkippedByResult, stats.gamesSkippedByElo);
        log.info("Processed {} moves. Priority entries before: {}, after: {}, delta: {}",
                stats.movesProcessed, before.size(), after.size(), delta.size());
        log.info("Priority file located at {}", MoveOrderingPriority.getInstance().getStoragePath());
    }

    private static void logDryRunSummary(Config config,
                                         Stats stats,
                                         Int2IntOpenHashMap before,
                                         Int2IntOpenHashMap delta) {
        log.info("Dry-run summary for {}: processed {} decisive games (white wins: {}, black wins: {}) (files processed: {}, skipped draws: {}, skipped by ELO: {})",
                config.pgnPath(), stats.gamesProcessed, stats.whiteWins, stats.blackWins,
                stats.filesProcessed, stats.gamesSkippedByResult, stats.gamesSkippedByElo);
        log.info("Encountered {} moves. Priorities untouched ({} entries). Pending delta entries: {}",
                stats.movesProcessed, before.size(), delta.size());
    }

    private static void printDeltaReport(Int2IntOpenHashMap delta) {
        int[] keys = delta.keySet().toIntArray();
        Arrays.sort(keys);
        System.out.println("---- Move priority delta report ----");
        for (int move : keys) {
            int value = delta.get(move);
            String from = MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(move));
            String to = MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(move));
            StringBuilder flags = new StringBuilder();
            if (MoveHelper.isPawnPromotionMove(move)) {
                flags.append(" promo");
            }
            if (MoveHelper.isCastlingMove(move)) {
                flags.append(" castle");
            }
            if (MoveHelper.isCapture(move)) {
                flags.append(" capture");
            }
            System.out.printf(Locale.ROOT, "%12d %+3d %s-%s%s%n", move, value, from, to, flags);
        }
        System.out.println("---- End of report ----");
    }

    private static void resetStore(Path storePath) throws IOException {
        Path target = storePath != null ? storePath : MoveOrderingPriority.defaultStoragePath();
        Files.deleteIfExists(target);
        MoveOrderingPriority.resetForTests(target);
        log.info("Reset move-ordering priority store at {}", target);
    }

    private static List<Path> collectSources(Path root) throws IOException {
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(MoveOrderingPriorityImporter::isPgnFile)
                        .sorted()
                        .collect(Collectors.toCollection(ArrayList::new));
            }
        }
        return isPgnFile(root) ? List.of(root) : List.of();
    }

    private static boolean isPgnFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pgn");
    }

    private static final class Processor implements GameConsumer {
        private final Config config;
        private final MoveOrderingPriority priority;
        private final Int2IntOpenHashMap delta;
        private Path currentFile;
        private int gamesProcessed;
        private int whiteWins;
        private int blackWins;
        private int movesProcessed;
        private int gamesSkippedByResult;
        private int gamesSkippedByElo;
        private int filesProcessed;

        private Processor(Config config,
                          MoveOrderingPriority priority,
                          Int2IntOpenHashMap delta) {
            this.config = Objects.requireNonNull(config, "config");
            this.priority = Objects.requireNonNull(priority, "priority");
            this.delta = Objects.requireNonNull(delta, "delta");
            this.delta.defaultReturnValue(0);
        }

        void beginFile(Path source) {
            this.currentFile = source;
            filesProcessed++;
        }

        boolean shouldContinue() {
            return config.limit() <= 0 || gamesProcessed < config.limit();
        }

        @Override
        public boolean onGame(ParsedGame game) {
            String result = game.headers().getOrDefault("Result", "*");
            if (!isDecisive(result)) {
                gamesSkippedByResult++;
                return shouldContinue();
            }

            if (!passesEloThreshold(game.headers())) {
                gamesSkippedByElo++;
                return shouldContinue();
            }
            gamesProcessed++;

            IntArrayList whiteMoves = new IntArrayList();
            IntArrayList blackMoves = new IntArrayList();
            List<Integer> moves = game.moves();
            for (int i = 0; i < moves.size(); i++) {
                int move = moves.get(i);
                if ((i & 1) == 0) {
                    whiteMoves.add(move);
                } else {
                    blackMoves.add(move);
                }
            }
            movesProcessed += moves.size();

            if ("1-0".equals(result)) {
                applyGame(whiteMoves, blackMoves, true);
                whiteWins++;
            } else if ("0-1".equals(result)) {
                applyGame(blackMoves, whiteMoves, false);
                blackWins++;
            }

            return shouldContinue();
        }

        private void applyGame(IntArrayList winnerMoves,
                               IntArrayList loserMoves,
                               boolean whiteWon) {
            accumulate(delta, winnerMoves, +1);
            accumulate(delta, loserMoves, -1);

            if (!config.dryRun()) {
                priority.applyGameResult(winnerMoves, true);
                priority.applyGameResult(loserMoves, false);
            }

            String side = whiteWon ? "White" : "Black";
            log.debug("{} won with {} moves; loser contributed {} moves (file: {})",
                    side, winnerMoves.size(), loserMoves.size(), currentFile);
        }

        private Stats snapshot() {
            return new Stats(gamesProcessed, whiteWins, blackWins, movesProcessed,
                    gamesSkippedByResult, gamesSkippedByElo, filesProcessed);
        }

        private boolean passesEloThreshold(Map<String, String> headers) {
            if (config.minElo() <= 0) {
                return true;
            }
            int whiteElo = parseElo(headers.get("WhiteElo"));
            int blackElo = parseElo(headers.get("BlackElo"));
            int max = Math.max(whiteElo, blackElo);
            if (max >= config.minElo()) {
                return true;
            }
            log.debug("Skipping game in {} due to ELO filter: white={} black={}", currentFile, whiteElo, blackElo);
            return false;
        }
    }

    private static boolean isDecisive(String result) {
        return "1-0".equals(result) || "0-1".equals(result);
    }

    private static void accumulate(Int2IntOpenHashMap map, IntArrayList moves, int delta) {
        for (int i = 0; i < moves.size(); i++) {
            map.addTo(moves.getInt(i), delta);
        }
    }

    private static int parseElo(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String digits = raw.chars()
                .filter(Character::isDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
