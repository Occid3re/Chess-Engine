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
            case "go" -> go();
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

    private void go() {
        MoveList legal = engine.getAllLegalMoves();
        if (legal.size() > 0) {
            int move = legal.getMove(0);
            System.out.println("bestmove " + toUci(move));
        } else {
            System.out.println("bestmove (none)");
        }
    }

    private void stop() {
        ai.stopCalculation();
    }
}

