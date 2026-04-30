package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.ProviderConfigMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class AiTitleConfigService {
    public static final String PROVIDER_AI_TITLE = "ai_title";
    public static final String TITLE_FORMAT_PROMPT_KEY = "title_format_prompt";
    public static final String DEFAULT_TITLE_FORMAT_PROMPT = "你将收到两段输入：第一段是标题风格要求，第二段是待总结原文。请严格依据标题风格要求，总结待总结原文。"
            + "以此为标准，生成一段大于15中文字符，小于30个中文字符，客观，辩证的标题。"
            + "输出格式要求：只返回一行中文标题文本，不要解释、不要JSON、不要Markdown、不要引号、不要换行。";

    private final ProviderConfigMapper providerConfigMapper;
    private final Clock clock;

    public AiTitleConfigService(ProviderConfigMapper providerConfigMapper, Clock clock) {
        this.providerConfigMapper = providerConfigMapper;
        this.clock = clock;
    }

    public AiTitleConfigResponse config() {
        ProviderConfigRecord record = providerConfigMapper.selectConfig(PROVIDER_AI_TITLE, TITLE_FORMAT_PROMPT_KEY);
        if (record == null) {
            return new AiTitleConfigResponse(DEFAULT_TITLE_FORMAT_PROMPT, DEFAULT_TITLE_FORMAT_PROMPT, null);
        }
        return new AiTitleConfigResponse(record.getConfigValue(), DEFAULT_TITLE_FORMAT_PROMPT, record.getUpdatedAt());
    }

    public String titleFormatPrompt() {
        return config().titleFormatPrompt();
    }

    public AiTitleConfigResponse saveTitleFormatPrompt(String titleFormatPrompt) {
        ProviderConfigRecord record = new ProviderConfigRecord();
        record.setProviderId(PROVIDER_AI_TITLE);
        record.setConfigKey(TITLE_FORMAT_PROMPT_KEY);
        record.setConfigValue(titleFormatPrompt == null ? "" : titleFormatPrompt.strip());
        record.setUpdatedAt(Instant.now(clock).toEpochMilli());
        providerConfigMapper.upsertConfig(record);
        return config();
    }

    public record AiTitleConfigResponse(
            String titleFormatPrompt,
            String defaultTitleFormatPrompt,
            Long updatedAt
    ) {
    }
}
