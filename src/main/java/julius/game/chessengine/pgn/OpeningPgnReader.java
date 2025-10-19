package julius.game.chessengine.pgn;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.figures.PieceType;
import lombok.extern.log4j.Log4j2;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight PGN reader tailored for opening book generation.  The reader
 * supports multiple games per file, extracts tag pairs and converts SAN tokens
 * into engine moves, recording the Zobrist hash for each ply.
 */
@Log4j2
public class OpeningPgnReader {

    private static final Pattern TAG_PATTERN = Pattern.compile("\\[(\\w+)\\s+\"([^\"]*)\"\\]");
    private static final List<String> RESULT_TOKENS = List.of("1-0", "0-1", "1/2-1/2", "*");

    public record ParsedGame(Map<String, String> headers, List<Integer> moves, List<Long> hashes) {}

    @FunctionalInterface
    public interface GameConsumer {
        /**
         * @return {@code true} to continue parsing, {@code false} to stop.
         */
        boolean onGame(ParsedGame game);
    }

    public List<ParsedGame> parse(byte[] data) {
        Objects.requireNonNull(data, "data");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            return parseInternal(reader, null);
        } catch (IOException ex) {
            log.warn("Failed reading PGN stream", ex);
            return List.of();
        }
    }

    public List<ParsedGame> parse(String rawPgn) {
        Objects.requireNonNull(rawPgn, "rawPgn");
        try (BufferedReader reader = new BufferedReader(new StringReader(rawPgn))) {
            return parseInternal(reader, null);
        } catch (IOException ex) {
            log.warn("Failed reading PGN stream", ex);
            return List.of();
        }
    }

    public List<ParsedGame> parse(Path path) {
        Objects.requireNonNull(path, "path");
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parseInternal(reader, null);
        } catch (IOException ex) {
            log.warn("Failed reading PGN file {}", path, ex);
            return List.of();
        }
    }

    public void stream(Path path, GameConsumer consumer) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(consumer, "consumer");
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            parseInternal(reader, consumer);
        }
    }

    private List<ParsedGame> parseInternal(BufferedReader reader, GameConsumer consumer) throws IOException {
        List<ParsedGame> games = consumer == null ? new ArrayList<>() : null;
        while (true) {
            Map<String, String> headers = readHeaders(reader);
            if (headers == null) {
                break;
            }
            List<String> tokens = readMoves(reader);
            if (tokens.isEmpty()) {
                continue;
            }
            ParsedGame game = convertToGame(headers, tokens);
            if (consumer != null) {
                boolean keep = consumer.onGame(game);
                if (!keep) {
                    break;
                }
            } else {
                games.add(game);
            }
        }
        return games == null ? List.of() : List.copyOf(games);
    }

    private Map<String, String> readHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        reader.mark(1);
        int ch = reader.read();
        while (ch != -1) {
            if (Character.isWhitespace(ch)) {
                reader.mark(1);
                ch = reader.read();
                continue;
            }
            if (ch != '[') {
                reader.reset();
                return headers.isEmpty() ? null : headers;
            }
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            Matcher matcher = TAG_PATTERN.matcher("[" + line);
            if (matcher.matches()) {
                headers.put(matcher.group(1), matcher.group(2));
            }
            reader.mark(1);
            ch = reader.read();
        }
        return headers.isEmpty() ? null : headers;
    }

    private List<String> readMoves(BufferedReader reader) throws IOException {
        List<String> tokens = new LinkedList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inBraceComment = false;
        boolean inSemicolonComment = false;
        int parenthesesDepth = 0;
        int intCh;
        while (true) {
            reader.mark(1);
            intCh = reader.read();
            if (intCh == -1) {
                break;
            }
            char ch = (char) intCh;
            if (inBraceComment) {
                if (ch == '}') {
                    inBraceComment = false;
                }
                continue;
            }
            if (inSemicolonComment) {
                if (ch == '\n') {
                    inSemicolonComment = false;
                }
                continue;
            }
            if (ch == '{') {
                inBraceComment = true;
                continue;
            }
            if (ch == ';') {
                inSemicolonComment = true;
                continue;
            }
            if (ch == '(') {
                parenthesesDepth++;
                continue;
            }
            if (ch == ')') {
                if (parenthesesDepth > 0) {
                    parenthesesDepth--;
                }
                continue;
            }
            if (parenthesesDepth > 0) {
                continue;
            }
            if (ch == '[') {
                reader.reset();
                break;
            }
            if (Character.isWhitespace(ch)) {
                addTokenIfPresent(tokens, currentToken);
                if (ch == '\n') {
                    reader.mark(1);
                }
                if (!tokens.isEmpty() && RESULT_TOKENS.contains(tokens.get(tokens.size() - 1))) {
                    break;
                }
                continue;
            }
            currentToken.append(ch);
        }
        addTokenIfPresent(tokens, currentToken);
        tokens.removeIf(token -> token.isBlank() || token.equals("...") || token.matches("\\d+\\.+"));
        if (!tokens.isEmpty() && RESULT_TOKENS.contains(tokens.get(tokens.size() - 1))) {
            tokens.remove(tokens.size() - 1);
        }
        return tokens;
    }

    private void addTokenIfPresent(List<String> tokens, StringBuilder currentToken) {
        if (currentToken.length() > 0) {
            tokens.add(currentToken.toString());
            currentToken.setLength(0);
        }
    }

    private ParsedGame convertToGame(Map<String, String> headers, List<String> tokens) {
        Engine engine = new Engine();
        engine.startNewGame();
        List<Integer> moves = new ArrayList<>();
        List<Long> hashes = new ArrayList<>();
        for (String token : tokens) {
            if (engine.getGameState().isGameOver()) {
                log.debug("Ignoring trailing token '{}' because game already reached a terminal state", token);
                break;
            }
            hashes.add(engine.getBoardStateHash());
            int move = translateSanToMove(engine, token);
            moves.add(move);
            engine.performMove(move);
        }
        return new ParsedGame(headers, List.copyOf(moves), List.copyOf(hashes));
    }

    private int translateSanToMove(Engine engine, String sanToken) {
        String san = normaliseSan(sanToken);
        if (san.equals("O-O") || san.equals("0-0")) {
            return locateCastling(engine, true);
        }
        if (san.equals("O-O-O") || san.equals("0-0-0")) {
            return locateCastling(engine, false);
        }

        String promotionPart = null;
        int promotionIndex = san.indexOf('=');
        if (promotionIndex != -1) {
            promotionPart = san.substring(promotionIndex + 1);
            san = san.substring(0, promotionIndex);
        }
        if (san.length() < 2) {
            throw new IllegalArgumentException("Invalid SAN token: " + sanToken);
        }
        String destination = san.substring(san.length() - 2);
        String prefix = san.substring(0, san.length() - 2);
        boolean capture = prefix.contains("x");
        prefix = prefix.replace("x", "");

        PieceType pieceType = PieceType.PAWN;
        if (!prefix.isEmpty() && Character.isUpperCase(prefix.charAt(0))) {
            pieceType = sanPiece(prefix.charAt(0));
            prefix = prefix.substring(1);
        }
        Character disambiguationFile = null;
        Character disambiguationRank = null;
        if (!prefix.isEmpty()) {
            if (prefix.length() == 2) {
                disambiguationFile = fileChar(prefix.charAt(0));
                disambiguationRank = rankChar(prefix.charAt(1));
            } else if (prefix.length() == 1) {
                disambiguationFile = fileChar(prefix.charAt(0));
                disambiguationRank = rankChar(prefix.charAt(0));
                if (disambiguationFile != null && disambiguationRank != null) {
                    disambiguationRank = null; // prefer file when ambiguous
                }
            }
        }

        PieceType promotionPiece = promotionPart == null ? null : sanPiece(promotionPart.charAt(0));
        int destinationIndex = MoveHelper.convertStringToIndex(destination);
        Integer fromFile = disambiguationFile == null ? null : disambiguationFile - 'a';
        Integer fromRank = disambiguationRank == null ? null : Character.getNumericValue(disambiguationRank) - 1;

        IntArrayList legalMoves = engine.getAllLegalMoves();
        for (int i = 0; i < legalMoves.size(); i++) {
            int move = legalMoves.getInt(i);
            if (!matchesMove(move, pieceType, destinationIndex, capture, fromFile, fromRank, promotionPiece)) {
                continue;
            }
            return move;
        }
        throw new IllegalArgumentException("Unable to resolve SAN token: " + sanToken);
    }

    private PieceType sanPiece(char pieceChar) {
        return switch (Character.toUpperCase(pieceChar)) {
            case 'K' -> PieceType.KING;
            case 'Q' -> PieceType.QUEEN;
            case 'R' -> PieceType.ROOK;
            case 'B' -> PieceType.BISHOP;
            case 'N' -> PieceType.KNIGHT;
            default -> PieceType.PAWN;
        };
    }

    private Character fileChar(char value) {
        char lower = Character.toLowerCase(value);
        if (lower >= 'a' && lower <= 'h') {
            return lower;
        }
        return null;
    }

    private Character rankChar(char value) {
        if (value >= '1' && value <= '8') {
            return value;
        }
        return null;
    }

    private int locateCastling(Engine engine, boolean kingSide) {
        IntArrayList legal = engine.getAllLegalMoves();
        for (int i = 0; i < legal.size(); i++) {
            int move = legal.getInt(i);
            if (!MoveHelper.isCastlingMove(move)) {
                continue;
            }
            int from = MoveHelper.deriveFromIndex(move);
            int to = MoveHelper.deriveToIndex(move);
            if (kingSide && to > from) {
                return move;
            }
            if (!kingSide && to < from) {
                return move;
            }
        }
        throw new IllegalArgumentException("Castling move not legal in current position");
    }

    private boolean matchesMove(int move, PieceType expectedPiece, int destinationIndex, boolean capture,
                                Integer fromFile, Integer fromRank, PieceType promotionPiece) {
        if (MoveHelper.deriveToIndex(move) != destinationIndex) {
            return false;
        }
        PieceType actualPiece = MoveHelper.intToPieceType(MoveHelper.derivePieceTypeBits(move));
        if (actualPiece != expectedPiece) {
            return false;
        }
        if (capture != MoveHelper.isCapture(move)) {
            return false;
        }
        if (promotionPiece != null) {
            if (MoveHelper.derivePromotionPieceTypeBits(move) == 0) {
                return false;
            }
            PieceType actualPromotion = MoveHelper.intToPieceType(MoveHelper.derivePromotionPieceTypeBits(move));
            if (actualPromotion != promotionPiece) {
                return false;
            }
        } else if (MoveHelper.derivePromotionPieceTypeBits(move) != 0) {
            return false;
        }
        int fromIndex = MoveHelper.deriveFromIndex(move);
        if (fromFile != null && fromFile != fromIndex % 8) {
            return false;
        }
        if (fromRank != null && fromRank != fromIndex / 8) {
            return false;
        }
        return true;
    }

    private String normaliseSan(String sanToken) {
        String san = sanToken.trim();
        while (!san.isEmpty() && "+#!?".indexOf(san.charAt(san.length() - 1)) >= 0) {
            san = san.substring(0, san.length() - 1);
        }
        return san.replace('0', 'O');
    }

    public String fingerprint(List<byte[]> resources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] resource : resources) {
                digest.update(resource);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Missing SHA-256 algorithm", ex);
        }
    }
}
