package io.github.shigella520.linkpeek.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsConfigurationTest {
    @Test
    void schemaInitializerAddsMissingAiStatsColumnsIdempotently() throws IOException {
        Path tempDir = Files.createTempDirectory("linkpeek-schema-test");
        try {
            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("stats.db"));
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.execute("""
                    CREATE TABLE stats_event (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        occurred_at INTEGER NOT NULL,
                        event_type TEXT NOT NULL,
                        preview_key TEXT,
                        provider_id TEXT,
                        http_status INTEGER NOT NULL,
                        cache_hit INTEGER NOT NULL,
                        duration_ms INTEGER NOT NULL,
                        client_type TEXT NOT NULL,
                        error_code TEXT
                    )
                    """);

            initializeSchema(dataSource);
            initializeSchema(dataSource);

            assertTrue(hasColumn(jdbcTemplate, "ai_requested"));
            assertTrue(hasColumn(jdbcTemplate, "ai_succeeded"));
            jdbcTemplate.update("""
                    INSERT INTO stats_event (
                        occurred_at,
                        event_type,
                        http_status,
                        cache_hit,
                        duration_ms,
                        client_type
                    )
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, 1L, "PREVIEW_CREATED", 200, 0, 10L, "CRAWLER");
            assertEquals(
                    1,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM stats_event WHERE ai_requested = 0 AND ai_succeeded = 0",
                            Integer.class
                    )
            );
        } finally {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        }
    }

    private void initializeSchema(SQLiteDataSource dataSource) {
        new StatisticsConfiguration.StatisticsSchemaInitializer(
                dataSource,
                new ResourceDatabasePopulator(new ClassPathResource("db/stats-schema.sql"))
        );
    }

    private boolean hasColumn(JdbcTemplate jdbcTemplate, String columnName) {
        return jdbcTemplate.queryForList("PRAGMA table_info(stats_event)")
                .stream()
                .map(row -> String.valueOf(row.get("name")).toLowerCase(Locale.ROOT))
                .anyMatch(columnName::equals);
    }
}
