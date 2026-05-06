package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceLogServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void returnsEmptyResponseWhenLogFileIsMissing() {
        ServiceLogService.ServiceLogResponse response = service(tempDir.resolve("missing.log")).readLogs(100, null, null);

        assertFalse(response.exists());
        assertEquals(0, response.sizeBytes());
        assertEquals(0, response.lines().size());
    }

    @Test
    void readsLastLinesInOriginalOrder() throws IOException {
        Path logFile = writeLog("""
                2026-04-30 INFO first
                2026-04-30 WARN second
                2026-04-30 ERROR third
                2026-04-30 INFO fourth
                """);

        ServiceLogService.ServiceLogResponse response = service(logFile).readLogs(2, null, null);

        assertTrue(response.exists());
        assertTrue(response.truncated());
        assertEquals(2, response.lines().size());
        assertEquals("2026-04-30 ERROR third", response.lines().get(0));
        assertEquals("2026-04-30 INFO fourth", response.lines().get(1));
    }

    @Test
    void clampsRequestedLinesToMaximum() throws IOException {
        String content = IntStream.rangeClosed(1, 2_105)
                .mapToObj(index -> "2026-04-30 INFO line-" + index)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        Path logFile = writeLog(content + "\n");

        ServiceLogService.ServiceLogResponse response = service(logFile).readLogs(5_000, null, null);

        assertTrue(response.truncated());
        assertEquals(2_000, response.lines().size());
        assertEquals("2026-04-30 INFO line-106", response.lines().get(0));
        assertEquals("2026-04-30 INFO line-2105", response.lines().get(1_999));
    }

    @Test
    void filtersByLevelAndKeywordCaseInsensitively() throws IOException {
        Path logFile = writeLog("""
                2026-04-30 INFO preview served
                2026-04-30 WARN cache miss for PreviewKey
                2026-04-30 WARN upstream timeout
                2026-04-30 ERROR cache failed for previewkey
                """);

        ServiceLogService.ServiceLogResponse response = service(logFile).readLogs(20, "warn", "previewkey");

        assertFalse(response.truncated());
        assertEquals(1, response.lines().size());
        assertEquals("2026-04-30 WARN cache miss for PreviewKey", response.lines().get(0));
    }

    @Test
    void rejectsUnsupportedLevel() {
        assertThrows(IllegalArgumentException.class, () -> service(tempDir.resolve("app.log")).readLogs(100, "NOTICE", null));
    }

    private Path writeLog(String content) throws IOException {
        Path logFile = tempDir.resolve("linkpeek.log");
        Files.writeString(logFile, content, StandardCharsets.UTF_8);
        return logFile;
    }

    private ServiceLogService service(Path logFile) {
        LinkPeekProperties properties = new LinkPeekProperties();
        properties.setServiceLogPath(logFile);
        return new ServiceLogService(properties);
    }
}
