package julius.game.chessengine.uci;

import julius.game.chessengine.ai.AI;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.utils.VersionInfo;

/**
 * Very small UCI protocol handler used for interacting with the engine
 * over standard input/output.
 */
public class UciHandler {

    private final Engine engine;
    private final AI ai;
    private Thread searchThread;

    public UciHandler() {
        this.engine = new Engine();
        this.ai = new AI(engine);
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
            case "isready" -> System.out.println("readyok");
            case "ucinewgame" -> newGame();
            case "position" -> setPosition(tokens);
            case "go" -> go(tokens);
            case "stop" -> stop();
            case "quit" -> {
                return false;
            }
            default -> {
                // Unknown commands are ignored for now
            }
        }
        return true;
    }

    private void sendId() {
        System.out.println("id name JuliusChessEngine " + VersionInfo.getVersion());
        System.out.println("id author Julius");
        System.out.println("uciok");
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

        MoveList legal = engine.getAllLegalMoves();
        for (int i = 0; i < legal.size(); i++) {
            int move = legal.getMove(i);
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
        for (int i = 1; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "wtime" -> wtime = Long.parseLong(tokens[++i]);
                case "btime" -> btime = Long.parseLong(tokens[++i]);
                case "winc" -> winc = Long.parseLong(tokens[++i]);
                case "binc" -> binc = Long.parseLong(tokens[++i]);
                case "movetime" -> movetime = Long.parseLong(tokens[++i]);
                case "depth" -> depth = Integer.parseInt(tokens[++i]);
                default -> { /* ignore */ }
            }
        }

        if (depth > 0) {
            ai.setMaxDepth(depth);
        }

        long limit = movetime;
        if (limit == 0) {
            if (engine.whitesTurn()) {
                limit = wtime;
                if (winc > 0) limit += winc;
            } else {
                limit = btime;
                if (binc > 0) limit += binc;
            }
        }
        if (limit <= 0) {
            limit = 1000; // default 1 second
        }
        ai.setTimeLimit(limit);

        searchThread = new Thread(() -> {
            ai.startAutoPlay(false, false); // start calculation without auto-move
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Integer bm = ai.getCurrentBestMoveInt();
                    if (bm != null && bm != -1) {
                        System.out.println("bestmove " + toUci(bm));
                        return;
                    }
                    Thread.sleep(50);
                }
            } catch (InterruptedException ignored) {
                // interrupted by stop()
            } finally {
                Integer bm = ai.getCurrentBestMoveInt();
                if (bm != null && bm != -1) {
                    System.out.println("bestmove " + toUci(bm));
                } else {
                    MoveList legal = engine.getAllLegalMoves();
                    if (legal.size() > 0) {
                        System.out.println("bestmove " + toUci(legal.getMove(0)));
                    } else {
                        System.out.println("bestmove (none)");
                    }
                }
                ai.stopCalculation();
                UciHandler.this.searchThread = null;
            }
        });
        searchThread.start();
    }

    private void stop() {
        ai.stopCalculation();
        if (searchThread != null && searchThread.isAlive()) {
            searchThread.interrupt();
            try {
                searchThread.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            searchThread = null;
        }
    }
}

