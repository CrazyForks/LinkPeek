CREATE TABLE IF NOT EXISTS stats_link (
    preview_key TEXT PRIMARY KEY,
    provider_id TEXT,
    canonical_url TEXT NOT NULL,
    title TEXT NOT NULL,
    site_name TEXT NOT NULL,
    first_seen_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS stats_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    preview_key TEXT,
    provider_id TEXT,
    http_status INTEGER NOT NULL,
    cache_hit INTEGER NOT NULL,
    ai_requested INTEGER NOT NULL DEFAULT 0,
    ai_succeeded INTEGER NOT NULL DEFAULT 0,
    source_url TEXT,
    requested_style TEXT,
    actual_style TEXT,
    ai_provider_names TEXT,
    ai_duration_ms INTEGER NOT NULL DEFAULT 0,
    crawl_duration_ms INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL,
    client_type TEXT NOT NULL,
    error_code TEXT
);

CREATE INDEX IF NOT EXISTS idx_stats_event_occurred_at ON stats_event (occurred_at);
CREATE INDEX IF NOT EXISTS idx_stats_event_type_occurred_at ON stats_event (event_type, occurred_at);
CREATE INDEX IF NOT EXISTS idx_stats_event_preview_key ON stats_event (preview_key);
CREATE INDEX IF NOT EXISTS idx_stats_link_last_seen_at ON stats_link (last_seen_at);

CREATE TABLE IF NOT EXISTS admin_prompt (
    style TEXT PRIMARY KEY,
    prompt TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS provider_config (
    provider_id TEXT NOT NULL,
    config_key TEXT NOT NULL,
    config_value TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (provider_id, config_key)
);

CREATE TABLE IF NOT EXISTS ai_provider (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL,
    sort_order INTEGER NOT NULL,
    base_url TEXT NOT NULL,
    api_kind TEXT NOT NULL DEFAULT 'CHAT_COMPLETIONS',
    model TEXT NOT NULL,
    effort TEXT,
    request_timeout_seconds INTEGER NOT NULL DEFAULT 45,
    api_key TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_provider_enabled_sort ON ai_provider (enabled, sort_order, id);
