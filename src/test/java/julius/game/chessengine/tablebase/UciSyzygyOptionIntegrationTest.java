package julius.game.chessengine.tablebase;

import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.uci.UciHandler;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UciSyzygyOptionIntegrationTest {

    @Test
    void setoptionPropagatesSyzygyPathToService() throws Exception {
        List<String> output = new ArrayList<>();

        SyzygyTablebaseService originalScoreService = Score.getTablebaseService();

        UciHandler handler = new UciHandler(output::add, () -> true);

        Field serviceField = UciHandler.class.getDeclaredField("tablebaseService");
        serviceField.setAccessible(true);
        SyzygyTablebaseService originalHandlerService = (SyzygyTablebaseService) serviceField.get(handler);
        SyzygyTablebaseService mockService = mock(SyzygyTablebaseService.class);
        serviceField.set(handler, mockService);

        try {
            handler.handle("setoption name SyzygyPath value /var/syzygy");
            verify(mockService).configure("/var/syzygy");
        } finally {
            serviceField.set(handler, originalHandlerService);
            if (originalScoreService == null) {
                Score.clearTablebaseService();
            } else {
                Score.setTablebaseService(originalScoreService);
            }
        }
    }
}
