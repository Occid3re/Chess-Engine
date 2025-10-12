package julius.game.chessengine.syzygy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SyzygyPathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsEmptyWhenNoDirectoriesAreValid() {
        assertThat(SyzygyPathResolver.sanitize(null)).isEmpty();
        assertThat(SyzygyPathResolver.sanitize("   ")).isEmpty();
        assertThat(SyzygyPathResolver.sanitize("non-existent" + File.pathSeparator + "also-missing")).isEmpty();
    }

    @Test
    void discoversNestedTablebaseDirectories() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("syzygy"));
        Path wdl = Files.createDirectories(root.resolve("3-4-5-wdl"));
        Files.createFile(wdl.resolve("KRRvK.rtbw"));
        Path dtz = Files.createDirectories(root.resolve("3-4-5-dtz"));
        Files.createFile(dtz.resolve("KRRvK.rtbz"));

        Optional<String> sanitized = SyzygyPathResolver.sanitize(root.toString());

        assertThat(sanitized).isPresent();
        List<String> directories = splitPaths(sanitized.get());
        assertThat(directories)
                .containsExactlyInAnyOrder(wdl.toAbsolutePath().toString(), dtz.toAbsolutePath().toString());
        assertThat(directories).doesNotContain(root.toAbsolutePath().toString());
    }

    @Test
    void keepsExplicitDirectoriesAndAvoidsDuplicates() throws IOException {
        Path base = Files.createDirectory(tempDir.resolve("syzygy"));
        Path wdl = Files.createDirectories(base.resolve("3-4-5-wdl"));
        Files.createFile(wdl.resolve("table.rtbw"));
        Path dtz = Files.createDirectories(base.resolve("3-4-5-dtz"));
        Files.createFile(dtz.resolve("table.rtbz"));

        String configured = wdl + File.pathSeparator + base;
        Optional<String> sanitized = SyzygyPathResolver.sanitize(configured);

        assertThat(sanitized).isPresent();
        List<String> directories = splitPaths(sanitized.get());
        assertThat(directories)
                .containsExactlyInAnyOrder(wdl.toAbsolutePath().toString(), dtz.toAbsolutePath().toString());
    }

    private static List<String> splitPaths(String directories) {
        return Arrays.asList(directories.split(Pattern.quote(File.pathSeparator)));
    }
}
