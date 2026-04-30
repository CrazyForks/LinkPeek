package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.ProviderConfigMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class AiTitleConfigService {
    public static final String PROVIDER_AI_TITLE = "ai_title";
    public static final String OUTPUT_CONSTRAINT_KEY = "output_constraint";
    public static final String DEFAULT_OUTPUT_CONSTRAINT = "以此为标准，生成一段大于15中文字符，小于30个中文字符，客观，辩证的标题。"
            + "\n\n输出格式要求：只返回一行中文标题文本，不要解释、不要 JSON、不要 Markdown、不要引号、不要换行。";

    private final ProviderConfigMapper providerConfigMapper;
    private final Clock clock;

    public AiTitleConfigService(ProviderConfigMapper providerConfigMapper, Clock clock) {
        this.providerConfigMapper = providerConfigMapper;
        this.clock = clock;
    }

    public AiTitleConfigResponse config() {
        ProviderConfigRecord record = providerConfigMapper.selectConfig(PROVIDER_AI_TITLE, OUTPUT_CONSTRAINT_KEY);
        if (record == null) {
            return new AiTitleConfigResponse(DEFAULT_OUTPUT_CONSTRAINT, DEFAULT_OUTPUT_CONSTRAINT, null);
        }
        return new AiTitleConfigResponse(record.getConfigValue(), DEFAULT_OUTPUT_CONSTRAINT, record.getUpdatedAt());
    }

    public String outputConstraint() {
        return config().outputConstraint();
    }

    public AiTitleConfigResponse saveOutputConstraint(String outputConstraint) {
        ProviderConfigRecord record = new ProviderConfigRecord();
        record.setProviderId(PROVIDER_AI_TITLE);
        record.setConfigKey(OUTPUT_CONSTRAINT_KEY);
        record.setConfigValue(outputConstraint == null ? "" : outputConstraint.strip());
        record.setUpdatedAt(Instant.now(clock).toEpochMilli());
        providerConfigMapper.upsertConfig(record);
        return config();
    }

    public record AiTitleConfigResponse(
            String outputConstraint,
            String defaultOutputConstraint,
            Long updatedAt
    ) {
    }
}
