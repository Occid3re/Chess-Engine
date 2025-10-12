package julius.game.chessengine.syzygy;

import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reflection-driven bridge to the optional {@code chesslib} Syzygy implementation.
 *
 * <p>The engine does not take a hard compile-time dependency on the library so downstream
 * builds can choose the concrete probing backend. When the classes are available on the
 * classpath the adapter provides real results; otherwise the service will remain disabled.</p>
 */
@Log4j2
final class ChesslibSyzygyClient implements TablebaseClient {

    static Optional<ChesslibSyzygyClient> tryCreate(String directories, int maxPieces) {
        try {
            return Optional.of(new ChesslibSyzygyClient(directories, maxPieces));
        } catch (ReflectiveOperationException | IllegalArgumentException ex) {
            log.warn("Syzygy tablebases unavailable: {}", ex.getMessage());
            log.debug("Failed to initialise chesslib Syzygy probe", ex);
            return Optional.empty();
        }
    }

    private final Object tablebase;
    private final Constructor<?> boardConstructor;
    private final Method loadFromFen;
    private final Method probeWdl;
    private final Method probeDtz;
    private final Method probeDtm;

    private ChesslibSyzygyClient(String directories, int maxPieces)
            throws ReflectiveOperationException {
        Class<?> boardClass = Class.forName("io.github.bhlangonijr.chesslib.Board");
        Class<?> tablebaseClass = Class.forName("io.github.bhlangonijr.chesslib.tablebase.SyzygyTablebase");

        this.boardConstructor = boardClass.getConstructor();
        this.loadFromFen = boardClass.getMethod("loadFromFen", String.class);
        this.probeWdl = tablebaseClass.getMethod("probeWDL", boardClass);
        this.probeDtz = tablebaseClass.getMethod("probeDTZ", boardClass);
        this.probeDtm = tablebaseClass.getMethod("probeDTM", boardClass);

        Method getInstance = tablebaseClass.getMethod("getInstance");
        this.tablebase = getInstance.invoke(null);

        Method addDirectory = tablebaseClass.getMethod("addDirectory", String.class);
        Method loadNecessary = tablebaseClass.getMethod("loadNecessaryTables", int.class);

        List<String> resolved = resolveDirectories(directories);
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("No Syzygy directories configured");
        }
        for (String path : resolved) {
            addDirectory.invoke(tablebase, path);
        }
        loadNecessary.invoke(tablebase, maxPieces);
    }

    @Override
    public Optional<SyzygyProbeResult> probe(String fen) {
        try {
            Object board = boardConstructor.newInstance();
            loadFromFen.invoke(board, fen);

            Object wdlEnum = probeWdl.invoke(tablebase, board);
            SyzygyWdl wdl = translateWdl(wdlEnum);

            OptionalIntLike dtz = translateScore(probeDtz.invoke(tablebase, board));
            OptionalIntLike dtm = translateScore(probeDtm.invoke(tablebase, board));

            return Optional.of(new SyzygyProbeResult(wdl, dtz.toOptionalInt(), dtm.toOptionalInt()));
        } catch (InvocationTargetException targetEx) {
            Throwable cause = targetEx.getCause();
            if (cause != null) {
                log.debug("Syzygy probe failed: {}", cause.getMessage(), cause);
            } else {
                log.debug("Syzygy probe failed", targetEx);
            }
        } catch (ReflectiveOperationException ex) {
            log.debug("Syzygy probe failed", ex);
        }
        return Optional.empty();
    }

    private SyzygyWdl translateWdl(Object wdlEnum) {
        if (wdlEnum instanceof Enum<?> enumValue) {
            return SyzygyWdl.fromName(enumValue.name());
        }
        if (wdlEnum != null) {
            return SyzygyWdl.fromName(wdlEnum.toString().toUpperCase(Locale.ROOT));
        }
        return SyzygyWdl.UNKNOWN;
    }

    private OptionalIntLike translateScore(Object value) {
        if (value == null) {
            return OptionalIntLike.empty();
        }
        if (value instanceof Integer intValue) {
            return OptionalIntLike.of(intValue);
        }
        if (value instanceof Long longValue) {
            return OptionalIntLike.of(Math.toIntExact(longValue));
        }
        return OptionalIntLike.parse(value.toString());
    }

    private static List<String> resolveDirectories(String directories) {
        List<String> resolved = new ArrayList<>();
        if (directories == null || directories.isBlank()) {
            return resolved;
        }
        String normalised = directories.replace(',', File.pathSeparatorChar);
        for (String candidate : normalised.split(File.pathSeparator)) {
            if (candidate == null) {
                continue;
            }
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            resolved.add(trimmed);
        }
        return resolved;
    }

    private record OptionalIntLike(Integer value) {

        static OptionalIntLike of(int value) {
            return new OptionalIntLike(value);
        }

        static OptionalIntLike empty() {
            return new OptionalIntLike(null);
        }

        static OptionalIntLike parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return empty();
            }
            try {
                return of(Integer.parseInt(raw.trim()));
            } catch (NumberFormatException ex) {
                return empty();
            }
        }

        java.util.OptionalInt toOptionalInt() {
            return value == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(value);
        }
    }
}
