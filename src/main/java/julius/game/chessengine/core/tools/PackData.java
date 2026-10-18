package julius.game.chessengine.core.tools;

import julius.game.chessengine.core.Position;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Packs {@code fen;whiteScore;wdl} training files into fixed 32-byte records for the GPU trainer
 * (scripts/nnue/train_gpu.py), so a billion positions load as one array instead of being parsed as text.
 *
 * <p>Record (little endian): long occupancy; 16 bytes = 32 piece nibbles (piece code 0-11 = colour * 6 + type)
 * in occupancy order, lowest square first, low nibble first; short score (centipawns, White's view, clamped
 * to +-32000); byte wdl (White's view: 0 loss, 1 draw, 2 win); byte side to move (0 White, 1 Black);
 * 4 bytes reserved (0).</p>
 *
 * <p>Usage: {@code PackData <out.bin> <in1.txt> [in2.txt ...]}; an input may be {@code path@times} to write each
 * of its positions several times (e.g. endgames counted twice).</p>
 */
public final class PackData {

    public static final int RECORD = 32;

    private PackData() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: PackData <out.bin> <in.txt[@times]> ...");
            System.exit(2);
        }
        long written = 0, skipped = 0;
        ByteBuffer rec = ByteBuffer.allocate(RECORD).order(ByteOrder.LITTLE_ENDIAN);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(Path.of(args[0])), 1 << 20)) {
            for (int a = 1; a < args.length; a++) {
                String spec = args[a];
                int at = spec.lastIndexOf('@');
                int times = 1;
                if (at > 1 && spec.substring(at + 1).matches("\\d+")) {
                    times = Integer.parseInt(spec.substring(at + 1));
                    spec = spec.substring(0, at);
                }
                long before = written;
                try (BufferedReader r = Files.newBufferedReader(Path.of(spec), StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (!pack(line, rec)) {
                            skipped++;
                            continue;
                        }
                        for (int t = 0; t < times; t++) out.write(rec.array());
                        written += times;
                    }
                }
                System.out.printf("%s x%d: %d records%n", spec, times, written - before);
            }
        }
        System.out.printf("wrote %d records (%d MB), skipped %d lines%n", written, written * RECORD >> 20, skipped);
    }

    /** Fills rec from one {@code fen;whiteScore;wdl} line; false for malformed lines. */
    static boolean pack(String line, ByteBuffer rec) {
        int s1 = line.indexOf(';');
        int s2 = s1 < 0 ? -1 : line.indexOf(';', s1 + 1);
        if (s2 < 0) return false;
        Position p;
        int score, wdl;
        try {
            p = new Position(line.substring(0, s1));
            score = Integer.parseInt(line.substring(s1 + 1, s2).trim());
            wdl = Integer.parseInt(line.substring(s2 + 1).trim());
        } catch (RuntimeException e) {
            return false;
        }
        if (wdl < 0 || wdl > 2 || Long.bitCount(p.all) > 32) return false;
        rec.clear();
        rec.putLong(p.all);
        byte[] nibbles = new byte[16];
        long occ = p.all;
        int i = 0;
        while (occ != 0L) {
            int sq = Long.numberOfTrailingZeros(occ);
            occ &= occ - 1;
            int pc = p.board[sq];
            nibbles[i >> 1] |= (byte) ((i & 1) == 0 ? pc : pc << 4);
            i++;
        }
        rec.put(nibbles);
        rec.putShort((short) Math.max(-32000, Math.min(32000, score)));
        rec.put((byte) wdl);
        rec.put((byte) p.side);
        rec.putInt(0);
        return true;
    }
}
