package io.github.shigella520.linkpeek.server.ai;

import io.github.shigella520.linkpeek.core.model.PreviewKey;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.util.CardTextSanitizer;
import io.github.shigella520.linkpeek.server.admin.model.AdminPromptRecord;
import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.AdminPromptMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.AiProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class AiTitleService {
    private static final Logger log = LoggerFactory.getLogger(AiTitleService.class);

    public static final String RAW_CONTENT_PLACEHOLDER = "{raw_content}";
    public static final String OUTPUT_CONSTRAINT = "输出格式要求：只返回一行中文标题文本，不要解释、不要 JSON、不要 Markdown、不要引号、不要换行。";
    private static final Pattern STYLE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final int MAX_TITLE_CODE_POINTS = 120;

    private final AdminPromptMapper adminPromptMapper;
    private final AiProviderMapper aiProviderMapper;
    private final AiTitleClient aiTitleClient;

    public AiTitleService(
            AdminPromptMapper adminPromptMapper,
            AiProviderMapper aiProviderMapper,
            AiTitleClient aiTitleClient
    ) {
        this.adminPromptMapper = adminPromptMapper;
        this.aiProviderMapper = aiProviderMapper;
        this.aiTitleClient = aiTitleClient;
    }

    public Optional<StylePrompt> resolveStylePrompt(String style) {
        if (!StringUtils.hasText(style)) {
            return Optional.empty();
        }
        String normalizedStyle = style.strip();
        if (!STYLE_PATTERN.matcher(normalizedStyle).matches()) {
            return Optional.empty();
        }
        AdminPromptRecord prompt = adminPromptMapper.selectPrompt(normalizedStyle);
        if (prompt == null || !StringUtils.hasText(prompt.getPrompt())) {
            return Optional.empty();
        }
        return Optional.of(new StylePrompt(normalizedStyle, prompt.getPrompt().strip(), sha256(prompt.getPrompt())));
    }

    public PreviewKey styledPreviewKey(URI canonicalUrl, StylePrompt stylePrompt) {
        return PreviewKey.fromStableValue(canonicalUrl.toString()
                + "\nlinkpeek-style=" + stylePrompt.style()
                + "\nprompt-sha256=" + stylePrompt.promptHash());
    }

    public boolean supportsAiTitle(PreviewMetadata metadata) {
        return metadata != null
                && metadata.thumbnailUrl() != null
                && metadata.thumbnailUrl().startsWith("generated://")
                && StringUtils.hasText(metadata.rawContent());
    }

    public Optional<PreviewMetadata> generateStyledMetadata(PreviewMetadata metadata, StylePrompt stylePrompt) {
        if (!supportsAiTitle(metadata)) {
            return Optional.empty();
        }

        String prompt = buildPrompt(stylePrompt.prompt(), metadata.rawContent());
        List<AiProviderRecord> providers = aiProviderMapper.selectEnabledProviders();
        for (AiProviderRecord provider : providers) {
            try {
                Optional<String> generated = aiTitleClient.generateTitle(provider, prompt)
                        .map(this::cleanTitle)
                        .filter(StringUtils::hasText);
                if (generated.isPresent()) {
                    return Optional.of(withTitle(metadata, generated.get()));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.warn("ai_title_request_interrupted providerId={} style={}", provider.getId(), stylePrompt.style(), exception);
                return Optional.empty();
            } catch (RuntimeException | java.io.IOException exception) {
                log.warn(
                        "ai_title_request_failed providerId={} style={} baseUrl={} message={}",
                        provider.getId(),
                        stylePrompt.style(),
                        provider.getBaseUrl(),
                        exception.getMessage()
                );
            }
        }
        return Optional.empty();
    }

    public String buildPrompt(String promptTemplate, String rawContent) {
        String content = rawContent == null ? "" : rawContent.strip();
        String body;
        if (promptTemplate.contains(RAW_CONTENT_PLACEHOLDER)) {
            body = promptTemplate.replace(RAW_CONTENT_PLACEHOLDER, content);
        } else {
            body = promptTemplate.stripTrailing() + "\n\n原文内容：\n" + content;
        }
        return body.stripTrailing() + "\n\n" + OUTPUT_CONSTRAINT;
    }

    public String cleanTitle(String rawTitle) {
        if (rawTitle == null) {
            return "";
        }
        String cleaned = rawTitle
                .replace("```text", "")
                .replace("```markdown", "")
                .replace("```json", "")
                .replace("```", "")
                .replace('\r', '\n')
                .strip();
        String firstLine = cleaned.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("");
        firstLine = removePrefix(firstLine, "- ");
        firstLine = removePrefix(firstLine, "标题：");
        firstLine = removePrefix(firstLine, "标题:");
        firstLine = stripMatchingQuotes(firstLine.strip());
        return truncate(CardTextSanitizer.sanitize(firstLine), MAX_TITLE_CODE_POINTS);
    }

    private PreviewMetadata withTitle(PreviewMetadata metadata, String title) {
        return new PreviewMetadata(
                metadata.sourceUrl(),
                metadata.canonicalUrl(),
                metadata.providerId(),
                title,
                metadata.description(),
                metadata.siteName(),
                metadata.thumbnailUrl(),
                metadata.imageWidth(),
                metadata.imageHeight(),
                metadata.contentType(),
                metadata.rawContent()
        );
    }

    private String removePrefix(String value, String prefix) {
        return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))
                ? value.substring(prefix.length()).stripLeading()
                : value;
    }

    private String stripMatchingQuotes(String value) {
        String current = value;
        while (current.length() >= 2 && isQuote(current.charAt(0)) && matchingQuote(current.charAt(0)) == current.charAt(current.length() - 1)) {
            current = current.substring(1, current.length() - 1).strip();
        }
        return current;
    }

    private boolean isQuote(char value) {
        return value == '"' || value == '\'' || value == '`' || value == '“' || value == '‘';
    }

    private char matchingQuote(char value) {
        return switch (value) {
            case '“' -> '”';
            case '‘' -> '’';
            default -> value;
        };
    }

    private String truncate(String value, int maxCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end).stripTrailing();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record StylePrompt(String style, String prompt, String promptHash) {
    }
}
