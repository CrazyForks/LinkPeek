package io.github.shigella520.linkpeek.server.ai;

import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.AiProviderMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ProviderConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AiProviderDowngradeService {
    private static final Logger log = LoggerFactory.getLogger(AiProviderDowngradeService.class);

    public static final String PROVIDER_AI_PROVIDER = "ai_provider";
    public static final String AUTO_DOWNGRADE_ENABLED_KEY = "auto_downgrade_enabled";
    public static final String AUTO_DOWNGRADE_TIMEOUT_THRESHOLD_KEY = "auto_downgrade_timeout_threshold";
    public static final int DEFAULT_AUTO_DOWNGRADE_TIMEOUT_THRESHOLD = 3;
    public static final int MIN_AUTO_DOWNGRADE_TIMEOUT_THRESHOLD = 1;
    public static final int MAX_AUTO_DOWNGRADE_TIMEOUT_THRESHOLD = 100;

    private static final int SORT_STEP = 100;

    private final ProviderConfigMapper providerConfigMapper;
    private final AiProviderMapper aiProviderMapper;
    private final Clock clock;
    private final ConcurrentHashMap<Long, AtomicInteger> timeoutCounts = new ConcurrentHashMap<>();

    public AiProviderDowngradeService(
            ProviderConfigMapper providerConfigMapper,
            AiProviderMapper aiProviderMapper,
            Clock clock
    ) {
        this.providerConfigMapper = providerConfigMapper;
        this.aiProviderMapper = aiProviderMapper;
        this.clock = clock;
    }

    public ConfigResponse config() {
        ProviderConfigRecord enabled = providerConfigMapper.selectConfig(PROVIDER_AI_PROVIDER, AUTO_DOWNGRADE_ENABLED_KEY);
        ProviderConfigRecord threshold = providerConfigMapper.selectConfig(PROVIDER_AI_PROVIDER, AUTO_DOWNGRADE_TIMEOUT_THRESHOLD_KEY);
        Long updatedAt = updatedAt(enabled, threshold);
        return new ConfigResponse(
                parseBoolean(enabled, false),
                parseThreshold(threshold),
                DEFAULT_AUTO_DOWNGRADE_TIMEOUT_THRESHOLD,
                updatedAt
        );
    }

    @Transactional
    public ConfigResponse saveConfig(Boolean autoDowngradeEnabled, Integer autoDowngradeTimeoutThreshold) {
        if (autoDowngradeEnabled == null) {
            throw new IllegalArgumentException("Auto downgrade enabled value is required.");
        }
        int threshold = normalizeThreshold(autoDowngradeTimeoutThreshold);
        long updatedAt = Instant.now(clock).toEpochMilli();
        upsert(AUTO_DOWNGRADE_ENABLED_KEY, Boolean.toString(autoDowngradeEnabled), updatedAt);
        upsert(AUTO_DOWNGRADE_TIMEOUT_THRESHOLD_KEY, Integer.toString(threshold), updatedAt);
        if (!autoDowngradeEnabled) {
            timeoutCounts.clear();
        }
        return config();
    }

    public void recordSuccess(AiProviderRecord provider) {
        if (provider != null && provider.getId() != null) {
            timeoutCounts.remove(provider.getId());
        }
    }

    @Transactional
    public synchronized void recordTimeout(AiProviderRecord provider, Throwable exception) {
        if (provider == null || provider.getId() == null) {
            return;
        }
        ConfigResponse config = config();
        if (!config.autoDowngradeEnabled()) {
            return;
        }

        AtomicInteger counter = timeoutCounts.computeIfAbsent(provider.getId(), ignored -> new AtomicInteger());
        int timeoutCount = counter.incrementAndGet();
        log.warn(
                "ai_provider_auto_downgrade_timeout_count providerId={} providerName={} timeoutCount={} threshold={} timeoutSeconds={} baseUrl={} message={}",
                provider.getId(),
                provider.getName(),
                timeoutCount,
                config.autoDowngradeTimeoutThreshold(),
                provider.getRequestTimeoutSeconds(),
                provider.getBaseUrl(),
                exception == null ? "" : exception.getMessage()
        );
        if (timeoutCount < config.autoDowngradeTimeoutThreshold()) {
            return;
        }

        timeoutCounts.remove(provider.getId());
        moveProviderToBottom(provider, timeoutCount, config.autoDowngradeTimeoutThreshold());
    }

    private void moveProviderToBottom(AiProviderRecord provider, int timeoutCount, int threshold) {
        List<AiProviderRecord> providers = aiProviderMapper.selectAllProviders();
        boolean exists = providers.stream()
                .anyMatch(candidate -> provider.getId().equals(candidate.getId()));
        if (!exists) {
            log.warn(
                    "AI_PROVIDER_AUTO_DOWNGRADE_SKIPPED providerId={} providerName={} reason=provider_not_found timeoutCount={} threshold={}",
                    provider.getId(),
                    provider.getName(),
                    timeoutCount,
                    threshold
            );
            return;
        }

        AiProviderRecord lastProvider = providers.stream()
                .max(Comparator.comparingInt(AiProviderRecord::getSortOrder).thenComparing(AiProviderRecord::getId))
                .orElse(null);
        boolean alreadyLowest = lastProvider != null && provider.getId().equals(lastProvider.getId());
        int oldSortOrder = provider.getSortOrder();
        List<Long> reorderedIds = new ArrayList<>(providers.stream()
                .map(AiProviderRecord::getId)
                .filter(id -> !provider.getId().equals(id))
                .toList());
        reorderedIds.add(provider.getId());

        long updatedAt = Instant.now(clock).toEpochMilli();
        int sortOrder = SORT_STEP;
        int newSortOrder = sortOrder;
        for (Long providerId : reorderedIds) {
            aiProviderMapper.updateProviderSortOrder(providerId, sortOrder, updatedAt);
            if (provider.getId().equals(providerId)) {
                newSortOrder = sortOrder;
            }
            sortOrder += SORT_STEP;
        }

        log.warn(
                "AI_PROVIDER_AUTO_DOWNGRADE_TRIGGERED providerId={} providerName={} timeoutCount={} threshold={} oldSortOrder={} newSortOrder={} alreadyLowest={} timeoutSeconds={} baseUrl={}",
                provider.getId(),
                provider.getName(),
                timeoutCount,
                threshold,
                oldSortOrder,
                newSortOrder,
                alreadyLowest,
                provider.getRequestTimeoutSeconds(),
                provider.getBaseUrl()
        );
    }

    private void upsert(String configKey, String configValue, long updatedAt) {
        ProviderConfigRecord record = new ProviderConfigRecord();
        record.setProviderId(PROVIDER_AI_PROVIDER);
        record.setConfigKey(configKey);
        record.setConfigValue(configValue);
        record.setUpdatedAt(updatedAt);
        providerConfigMapper.upsertConfig(record);
    }

    private boolean parseBoolean(ProviderConfigRecord record, boolean defaultValue) {
        if (record == null || !StringUtils.hasText(record.getConfigValue())) {
            return defaultValue;
        }
        return Boolean.parseBoolean(record.getConfigValue().strip());
    }

    private int parseThreshold(ProviderConfigRecord record) {
        if (record == null || !StringUtils.hasText(record.getConfigValue())) {
            return DEFAULT_AUTO_DOWNGRADE_TIMEOUT_THRESHOLD;
        }
        try {
            return normalizeThreshold(Integer.parseInt(record.getConfigValue().strip()));
        } catch (NumberFormatException exception) {
            return DEFAULT_AUTO_DOWNGRADE_TIMEOUT_THRESHOLD;
        }
    }

    private int normalizeThreshold(Integer threshold) {
        int value = threshold == null ? DEFAULT_AUTO_DOWNGRADE_TIMEOUT_THRESHOLD : threshold;
        if (value < MIN_AUTO_DOWNGRADE_TIMEOUT_THRESHOLD || value > MAX_AUTO_DOWNGRADE_TIMEOUT_THRESHOLD) {
            throw new IllegalArgumentException("Auto downgrade timeout threshold must be between 1 and 100.");
        }
        return value;
    }

    private Long updatedAt(ProviderConfigRecord left, ProviderConfigRecord right) {
        long value = Math.max(left == null ? 0L : left.getUpdatedAt(), right == null ? 0L : right.getUpdatedAt());
        return value == 0L ? null : value;
    }

    public record ConfigResponse(
            boolean autoDowngradeEnabled,
            int autoDowngradeTimeoutThreshold,
            int defaultAutoDowngradeTimeoutThreshold,
            Long updatedAt
    ) {
    }
}
