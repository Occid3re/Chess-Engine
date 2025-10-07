package julius.game.chessengine.uci;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.ai.AI;
import julius.game.chessengine.ai.MoveAndScore;
import julius.game.chessengine.ai.time.TimeManager;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.utils.Score;
import julius.game.chessengine.utils.VersionInfo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Very small UCI protocol handler used for interacting with the engine
 * over standard input/output.
 */
public class UciHandler {

    private static final long INFO_INTERVAL_NANOS = Duration.ofMillis(200).toNanos();

    private final Engine engine;
    private final AI ai;
    private final Consumer<String> output;
    private final Supplier<Boolean> running;
    private Thread searchThread;
    private final Map<String, UciOption> options = new LinkedHashMap<>();
    private int moveOverheadMs = 0;
    private final AtomicLong lastInfoNanos = new AtomicLong(0L);

    public UciHandler() {
        this(System.out::println, () -> true);
    }

    public UciHandler(Consumer<String> output, Supplier<Boolean> running) {
        this.engine = new Engine();
        this.ai = new AI(engine);
        this.output = Objects.requireNonNull(output, "output");
        this.running = Objects.requireNonNull(running, "running");
        registerOptions();
    }

    /**
     * Handle a single command line. Returns {@code false} when the caller
     * should terminate (i.e. on "quit").
     */
    public boolean handle(String line) {
        if (line == null || line.isEmpty()) {
            return true;
        }
        String[] tokens = line.trim().split("\\s+");
        String cmd = tokens[0];
        switch (cmd) {
            case "uci" -> sendId();
            case "isready" -> {
                stop();
                output.accept("readyok");
            }
            case "ucinewgame" -> newGame();
            case "position" -> setPosition(tokens);
            case "go" -> go(tokens);
            case "stop" -> stop();
            case "ponderhit" -> {
                ai.promotePonderHit();
                lastInfoNanos.set(0L);
            }
            case "setoption" -> setOption(tokens);
            case "quit" -> {
                stop();
                return false;
            }
            default -> {
                // Unknown commands are ignored for now
            }
        }
        return true;
    }

    private void sendId() {
        output.accept("id name Alieknek " + VersionInfo.getVersion());
        output.accept("id author Julius");
        for (UciOption opt : options.values()) {
            output.accept(String.format("option name %s type %s default %s min %d max %d",
                    opt.name, opt.type, opt.defaultValue, opt.min, opt.max));
        }
        output.accept("uciok");
    }

    private void registerOptions() {
        options.put("Threads", new UciOption("Threads", "spin", "1", 1, 128,
                v -> ai.setSearchThreads(Integer.parseInt(v))));
        options.put("Hash", new UciOption("Hash", "spin", "16",
                AI.MIN_HASH_SIZE_MB, AI.MAX_HASH_SIZE_MB,
                v -> ai.setHashSizeMb(Integer.parseInt(v))));
        options.put("Move Overhead", new UciOption("Move Overhead", "spin", "0", 0, 5000,
                v -> moveOverheadMs = Integer.parseInt(v)));
    }

    private void setOption(String[] tokens) {
        String name = null;
        StringBuilder value = new StringBuilder();
        for (int i = 1; i < tokens.length; i++) {
            if ("name".equals(tokens[i]) && i + 1 < tokens.length) {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < tokens.length && !"value".equals(tokens[i])) {
                    sb.append(tokens[i]).append(' ');
                    i++;
                }
                name = sb.toString().trim();
            }
            if (i < tokens.length && "value".equals(tokens[i]) && i + 1 < tokens.length) {
                i++;
                while (i < tokens.length) {
                    value.append(tokens[i]).append(' ');
                    i++;
                }
            }
        }
        if (name != null) {
            UciOption opt = options.get(name);
            if (opt != null) {
                opt.setter.accept(value.toString().trim());
            }
        }
    }

    private static class UciOption {
        final String name;
        final String type;
        final String defaultValue;
        final int min;
        final int max;
        final Consumer<String> setter;

        UciOption(String name, String type, String defaultValue, int min, int max, Consumer<String> setter) {
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.setter = setter;
        }
    }

    private void newGame() {
        // ucinewgame is used to clear the search state of the engine/AI.
        // The board position will be set with a subsequent "position" command,
        // therefore we only reset the AI/engine state here and leave the board
        // untouched.
        ai.reset();
    }

    private void setPosition(String[] tokens) {
        int idx = 1;
        if (idx >= tokens.length) {
            return;
        }
        if ("startpos".equals(tokens[idx])) {
            engine.startNewGame();
            idx++;
        } else if ("fen".equals(tokens[idx])) {
            StringBuilder fen = new StringBuilder();
            idx++;
            while (idx < tokens.length && !"moves".equals(tokens[idx])) {
                fen.append(tokens[idx]).append(' ');
                idx++;
            }
            engine.importBoardFromFen(fen.toString().trim());
        }
        if (idx < tokens.length && "moves".equals(tokens[idx])) {
            idx++;
            while (idx < tokens.length) {
                applyMove(tokens[idx]);
                idx++;
            }
        }
    }

    private void applyMove(String moveStr) {
        if (moveStr == null || moveStr.length() < 4) {
            return; // malformed move string
        }

        int from = MoveHelper.convertStringToIndex(moveStr.substring(0, 2));
        int to = MoveHelper.convertStringToIndex(moveStr.substring(2, 4));

        int promo = 0;
        if (moveStr.length() > 4) {
            char p = moveStr.charAt(4);
            promo = switch (p) {
                case 'n' -> 2;
                case 'b' -> 3;
                case 'r' -> 4;
                case 'q' -> 5;
                default -> 0;
            };
        }

        IntArrayList legal = engine.getAllLegalMoves();
        for (int i = 0; i < legal.size(); i++) {
            int move = legal.getInt(i);
            if (from == MoveHelper.deriveFromIndex(move)
                    && to == MoveHelper.deriveToIndex(move)
                    && promo == MoveHelper.derivePromotionPieceTypeBits(move)) {
                engine.performMove(move);
                return;
            }
        }
    }

    private String toUci(int move) {
        String from = MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(move));
        String to = MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(move));
        int promo = MoveHelper.derivePromotionPieceTypeBits(move);
        if (promo != 0) {
            char promoChar = switch (promo) {
                case 2 -> 'n';
                case 3 -> 'b';
                case 4 -> 'r';
                case 5 -> 'q';
                default -> '?';
            };
            return from + to + promoChar;
        }
        return from + to;
    }

    private void go(String[] tokens) {
        // Stop any previous search thread
        stop();

        long wtime = 0, btime = 0, winc = 0, binc = 0, movetime = 0;
        int depth = 0;
        int movestogo = 0;
        boolean ponder = false;
        for (int i = 1; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "ponder" -> ponder = true;
                case "wtime" -> wtime = Long.parseLong(tokens[++i]);
                case "btime" -> btime = Long.parseLong(tokens[++i]);
                case "winc" -> winc = Long.parseLong(tokens[++i]);
                case "binc" -> binc = Long.parseLong(tokens[++i]);
                case "movetime" -> movetime = Long.parseLong(tokens[++i]);
                case "depth" -> depth = Integer.parseInt(tokens[++i]);
                case "movestogo" -> movestogo = Integer.parseInt(tokens[++i]);
                default -> { /* ignore */ }
            }
        }

        if (depth > 0) {
            ai.setMaxDepth(depth);
        }

        boolean whitesTurn = engine.whitesTurn();
        long timeLeft = whitesTurn ? wtime : btime;
        long inc = whitesTurn ? winc : binc;

        boolean hasTimeSpec = movetime > 0 || wtime > 0 || btime > 0 || winc > 0 || binc > 0 || movestogo > 0;
        if (hasTimeSpec || ponder) {
            TimeManager.Request request = new TimeManager.Request(
                    timeLeft,
                    inc,
                    movetime,
                    movestogo,
                    moveOverheadMs,
                    ponder
            );
            ai.submitTimeRequest(request);
        }

        lastInfoNanos.set(0L);
        searchThread = new Thread(() -> {
            ai.startAutoPlay(false, false); // start calculation without auto-move
            try {
                long nextInfo = System.nanoTime();
                while (!Thread.currentThread().isInterrupted()) {
                    if (!Boolean.TRUE.equals(running.get())) {
                        break;
                    }
                    long now = System.nanoTime();
                    if (now >= nextInfo) {
                        publishSearchInfo();
                        nextInfo = now + INFO_INTERVAL_NANOS;
                    }
                    Integer bm = ai.getCurrentBestMoveInt();
                    if (bm != null && bm != -1) {
                        break;
                    }
                    Thread.sleep(50);
                }
            } catch (InterruptedException ignored) {
                // interrupted by stop()
            } finally {
                publishSearchInfo();
                Integer bm = ai.getCurrentBestMoveInt();
                if (bm != null && bm != -1) {
                    respondWithMoveOrTerminal(bm, "search result");
                } else {
                    IntArrayList legal = engine.getAllLegalMoves();
                    if (!legal.isEmpty()) {
                        respondWithMoveOrTerminal(legal.getInt(0), "fallback move");
                    } else {
                        output.accept("bestmove (none)");
                    }
                }
                ai.stopCalculation();
                UciHandler.this.searchThread = null;
            }
        });
        searchThread.start();
    }

    public void stop() {
        ai.stopCalculation();
        Thread runningThread = searchThread;
        if (runningThread != null) {
            if (runningThread != Thread.currentThread()) {
                if (runningThread.isAlive()) {
                    runningThread.interrupt();
                    try {
                        runningThread.join();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else {
                runningThread.interrupt();
            }
            if (!runningThread.isAlive() || runningThread == Thread.currentThread()) {
                searchThread = null;
            }
        }
    }

    private void publishSearchInfo() {
        if (!Boolean.TRUE.equals(running.get())) {
            return;
        }
        long now = System.nanoTime();
        long previous = lastInfoNanos.get();
        if (previous != 0 && now - previous < INFO_INTERVAL_NANOS / 2) {
            return;
        }
        lastInfoNanos.set(now);

        List<MoveAndScore> line = new ArrayList<>(ai.getCalculatedLine());
        long nodes = ai.getNodesVisited();
        StringBuilder builder = new StringBuilder("info");
        if (!line.isEmpty()) {
            builder.append(" depth ").append(Math.max(1, line.size()));
            builder.append(' ').append(formatScore(line.get(0).getScore()));
            builder.append(" nodes ").append(nodes);
            String pv = buildPv(line);
            if (!pv.isEmpty()) {
                builder.append(" pv ").append(pv);
            }
        } else {
            builder.append(" nodes ").append(nodes);
        }
        output.accept(builder.toString());

        GameState gameState = ai.getMainEngine().getGameState();
        if (gameState != null && gameState.getState() != null) {
            output.accept("info string gamestate " + gameState.getState());
        }
    }

    private void respondWithMoveOrTerminal(int move, String reason) {
        if (engine.getGameState().isTerminal()) {
            output.accept("info string Terminal position detected during " + reason + "; responding with bestmove (none)");
            output.accept("bestmove (none)");
        } else {
            engine.performMove(move);
            output.accept("bestmove " + toUci(move));
        }
    }

    private String formatScore(double score) {
        double abs = Math.abs(score);
        if (abs >= Score.CHECKMATE - 1000) {
            int mate = (int) Math.max(1, Math.round((Score.CHECKMATE - abs) / 100.0));
            if (score < 0) {
                mate = -mate;
            }
            return "score mate " + mate;
        }
        int cp = (int) Math.round(score * 100.0);
        return "score cp " + cp;
    }

    private String buildPv(List<MoveAndScore> line) {
        StringBuilder pv = new StringBuilder();
        for (MoveAndScore moveAndScore : line) {
            if (moveAndScore == null) {
                continue;
            }
            if (pv.length() > 0) {
                pv.append(' ');
            }
            pv.append(toUci(moveAndScore.getMove()));
        }
        return pv.toString();
    }
}

