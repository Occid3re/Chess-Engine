package julius.game.chessengine.syzygy;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;

class SyzygyTablebaseServiceTest {

    @Test
    void forwardsBitBoardStateToClient() {
        BitBoard board = FEN.translateFENtoBitBoard("8/8/8/8/8/8/8/8 w - - 0 1");
        RecordingClient client = new RecordingClient();
        client.response = Optional.of(SyzygyProbeResult.unavailable());

        SyzygyTablebaseService service = new SyzygyTablebaseService(client, 16);
        service.probe(board);

        assertThat(client.lastBoard).isSameAs(board);
    }

    @Test
    void cachesProbesUsingZobristAndClocks() {
        BitBoard board = FEN.translateFENtoBitBoard("8/8/8/8/8/8/8/8 w - - 0 1");
        RecordingClient client = new RecordingClient();
        client.response = Optional.of(new SyzygyProbeResult(SyzygyWdl.DRAW, OptionalInt.of(0), OptionalInt.empty()));

        SyzygyTablebaseService service = new SyzygyTablebaseService(client, 16);
        Optional<SyzygyProbeResult> first = service.probe(board);
        Optional<SyzygyProbeResult> second = service.probe(board);

        assertThat(first).containsInstanceOf(SyzygyProbeResult.class);
        assertThat(second).isEqualTo(first);
        assertThat(client.invocations).isEqualTo(1);

        board.setHalfmoveClock(1);
        Optional<SyzygyProbeResult> third = service.probe(board);
        assertThat(third).isEqualTo(first);
        assertThat(client.invocations).isEqualTo(2);
    }

    @Test
    void counterRemainsAccurateUnderConcurrentMisses() throws Exception {
        RecordingClient client = new RecordingClient();
        SyzygyTablebaseService service = new SyzygyTablebaseService(client, 32);
        int capacity = extractMaxEntries(service);
        int tasks = capacity * 2;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);

        for (int i = 0; i < tasks; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    start.await();
                    BitBoard board = FEN.translateFENtoBitBoard("K7/8/8/8/8/8/8/k7 w - - 0 1");
                    board.setHalfmoveClock(index % 100);
                    board.setFullmoveNumber(index + 1);
                    service.probe(board);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        executor.shutdown();
        start.countDown();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        AtomicInteger entryCount = extractEntryCount(service);
        ConcurrentMap<?, ?> cache = extractCache(service);

        assertThat(entryCount.get()).isEqualTo(cache.size());
        assertThat(entryCount.get()).isLessThanOrEqualTo(capacity);
    }

    @Test
    void counterRemainsAccurateWhenMultipleThreadsMissSameKey() throws Exception {
        RecordingClient client = new RecordingClient();
        SyzygyTablebaseService service = new SyzygyTablebaseService(client, 16);

        int tasks = 64;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);

        for (int i = 0; i < tasks; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    BitBoard board = FEN.translateFENtoBitBoard("K7/8/8/8/8/8/8/k7 w - - 0 1");
                    service.probe(board);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        executor.shutdown();
        start.countDown();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        AtomicInteger entryCount = extractEntryCount(service);
        ConcurrentMap<?, ?> cache = extractCache(service);

        assertThat(entryCount.get()).isEqualTo(cache.size());
        assertThat(entryCount.get()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static AtomicInteger extractEntryCount(SyzygyTablebaseService service) throws Exception {
        Field field = SyzygyTablebaseService.class.getDeclaredField("entryCount");
        field.setAccessible(true);
        return (AtomicInteger) field.get(service);
    }

    private static ConcurrentMap<?, ?> extractCache(SyzygyTablebaseService service) throws Exception {
        Field field = SyzygyTablebaseService.class.getDeclaredField("cache");
        field.setAccessible(true);
        return (ConcurrentMap<?, ?>) field.get(service);
    }

    private static int extractMaxEntries(SyzygyTablebaseService service) throws Exception {
        Field field = SyzygyTablebaseService.class.getDeclaredField("maxEntries");
        field.setAccessible(true);
        return field.getInt(service);
    }

    private static final class RecordingClient implements TablebaseClient {
        Optional<SyzygyProbeResult> response = Optional.empty();
        BitBoard lastBoard;
        int invocations = 0;

        @Override
        public Optional<SyzygyProbeResult> probe(BitBoard board) {
            invocations++;
            lastBoard = board;
            return response;
        }
    }
}
