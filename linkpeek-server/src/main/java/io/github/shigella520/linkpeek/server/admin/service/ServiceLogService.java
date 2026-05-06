package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ServiceLogService {
    private static final int DEFAULT_LINES = 300;
    private static final int MAX_LINES = 2_000;
    private static final int READ_BUFFER_SIZE = 8_192;
    private static final Set<String> SUPPORTED_LEVELS = Set.of("ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    private final Path logPath;

    public ServiceLogService(LinkPeekProperties properties) {
        this.logPath = properties.getServiceLogPath() == null
                ? Path.of("/data/logs/linkpeek.log")
                : properties.getServiceLogPath();
    }

    public ServiceLogResponse readLogs(Integer requestedLines, String level, String query) {
        int lineLimit = normalizeLineLimit(requestedLines);
        LogFilter filter = normalizeFilter(level, query);
        Path normalizedPath = logPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedPath) || !Files.isReadable(normalizedPath)) {
            return missingResponse(normalizedPath);
        }

        try {
            long sizeBytes = Files.size(normalizedPath);
            Long modifiedAt = Files.getLastModifiedTime(normalizedPath).toMillis();
            TailReadResult result = tailMatchingLines(normalizedPath, lineLimit, filter);
            return new ServiceLogResponse(
                    normalizedPath.toString(),
                    true,
                    sizeBytes,
                    modifiedAt,
                    result.lines(),
                    result.truncated()
            );
        } catch (IOException exception) {
            return missingResponse(normalizedPath);
        }
    }

    private TailReadResult tailMatchingLines(Path path, int lineLimit, LogFilter filter) throws IOException {
        int targetMatches = lineLimit + 1;
        List<String> reverseMatches = new ArrayList<>(Math.min(targetMatches, 128));
        ByteArrayOutputStream reverseLine = new ByteArrayOutputStream(256);
        byte[] buffer = new byte[READ_BUFFER_SIZE];

        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long pointer = file.length();
            while (pointer > 0 && reverseMatches.size() < targetMatches) {
                int readSize = (int) Math.min(buffer.length, pointer);
                pointer -= readSize;
                file.seek(pointer);
                file.readFully(buffer, 0, readSize);

                for (int index = readSize - 1; index >= 0 && reverseMatches.size() < targetMatches; index--) {
                    byte value = buffer[index];
                    if (value == '\n') {
                        addMatchedLine(reverseLine, reverseMatches, filter);
                    } else {
                        reverseLine.write(value);
                    }
                }
            }
            if (reverseMatches.size() < targetMatches) {
                addMatchedLine(reverseLine, reverseMatches, filter);
            }
        }

        boolean truncated = reverseMatches.size() > lineLimit;
        if (truncated) {
            reverseMatches = new ArrayList<>(reverseMatches.subList(0, lineLimit));
        }
        Collections.reverse(reverseMatches);
        return new TailReadResult(reverseMatches, truncated);
    }

    private void addMatchedLine(ByteArrayOutputStream reverseLine, List<String> reverseMatches, LogFilter filter) {
        if (reverseLine.size() == 0) {
            return;
        }
        String line = decodeReverseLine(reverseLine);
        reverseLine.reset();
        if (matches(line, filter)) {
            reverseMatches.add(line);
        }
    }

    private String decodeReverseLine(ByteArrayOutputStream reverseLine) {
        byte[] bytes = reverseLine.toByteArray();
        for (int left = 0, right = bytes.length - 1; left < right; left++, right--) {
            byte temp = bytes[left];
            bytes[left] = bytes[right];
            bytes[right] = temp;
        }
        String line = new String(bytes, StandardCharsets.UTF_8);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private boolean matches(String line, LogFilter filter) {
        if (filter.level() != null && !line.contains(" " + filter.level() + " ")) {
            return false;
        }
        return filter.queryLowerCase() == null
                || line.toLowerCase(Locale.ROOT).contains(filter.queryLowerCase());
    }

    private int normalizeLineLimit(Integer requestedLines) {
        if (requestedLines == null) {
            return DEFAULT_LINES;
        }
        return Math.min(MAX_LINES, Math.max(1, requestedLines));
    }

    private LogFilter normalizeFilter(String level, String query) {
        String normalizedLevel = null;
        if (StringUtils.hasText(level)) {
            normalizedLevel = level.strip().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_LEVELS.contains(normalizedLevel)) {
                throw new IllegalArgumentException("Unsupported log level: " + level);
            }
        }
        String normalizedQuery = StringUtils.hasText(query) ? query.strip().toLowerCase(Locale.ROOT) : null;
        return new LogFilter(normalizedLevel, normalizedQuery);
    }

    private ServiceLogResponse missingResponse(Path normalizedPath) {
        return new ServiceLogResponse(normalizedPath.toString(), false, 0L, null, List.of(), false);
    }

    private record LogFilter(String level, String queryLowerCase) {
    }

    private record TailReadResult(List<String> lines, boolean truncated) {
    }

    public record ServiceLogResponse(
            String path,
            boolean exists,
            long sizeBytes,
            Long modifiedAt,
            List<String> lines,
            boolean truncated
    ) {
    }
}
