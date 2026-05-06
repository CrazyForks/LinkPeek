package io.github.shigella520.linkpeek.server.ai;

import io.github.shigella520.linkpeek.core.model.PreviewKey;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.util.CardTextSanitizer;
import io.github.shigella520.linkpeek.server.admin.model.AdminPromptRecord;
import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.AdminPromptMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.AiProviderMapper;
import io.github.shigella520.linkpeek.server.admin.service.AiTitleConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@Service
public class AiTitleService {
    private static final Logger log = LoggerFactory.getLogger(AiTitleService.class);

    public static final String FREESTYLE_STYLE = "FREESTYLE";
    public static final String DEFAULT_TITLE_FORMAT_PROMPT = AiTitleConfigService.DEFAULT_TITLE_FORMAT_PROMPT;
    private static final Pattern STYLE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final int MAX_TITLE_CODE_POINTS = 120;

    private final AdminPromptMapper adminPromptMapper;
    private final AiProviderMapper aiProviderMapper;
    private final AiTitleClient aiTitleClient;
    private final AiTitleConfigService aiTitleConfigService;
    private final AiProviderDowngradeService aiProviderDowngradeService;

    public AiTitleService(
            AdminPromptMapper adminPromptMapper,
            AiProviderMapper aiProviderMapper,
            AiTitleClient aiTitleClient,
            AiTitleConfigService aiTitleConfigService,
            AiProviderDowngradeService aiProviderDowngradeService
    ) {
        this.adminPromptMapper = adminPromptMapper;
        this.aiProviderMapper = aiProviderMapper;
        this.aiTitleClient = aiTitleClient;
        this.aiTitleConfigService = aiTitleConfigService;
        this.aiProviderDowngradeService = aiProviderDowngradeService;
    }

    public Optional<StylePrompt> resolveStylePrompt(String style) {
        if (!StringUtils.hasText(style)) {
            return Optional.empty();
        }
        String strippedStyle = style.strip();
        if (!STYLE_PATTERN.matcher(strippedStyle).matches()) {
            return Optional.empty();
        }
        String normalizedStyle = normalizeStyleKey(strippedStyle);
        if (isFreestyleStyle(normalizedStyle)) {
            return resolveFreestylePrompt();
        }
        AdminPromptRecord prompt = adminPromptMapper.selectPrompt(normalizedStyle);
        return stylePrompt(prompt);
    }

    private Optional<StylePrompt> resolveFreestylePrompt() {
        List<AdminPromptRecord> prompts = adminPromptMapper.selectAllPrompts().stream()
                .filter(prompt -> prompt != null
                        && StringUtils.hasText(prompt.getStyle())
                        && STYLE_PATTERN.matcher(prompt.getStyle().strip()).matches()
                        && !isFreestyleStyle(prompt.getStyle())
                        && StringUtils.hasText(prompt.getPrompt()))
                .toList();
        if (prompts.isEmpty()) {
            return Optional.empty();
        }
        AdminPromptRecord prompt = prompts.get(ThreadLocalRandom.current().nextInt(prompts.size()));
        return stylePrompt(prompt);
    }

    private Optional<StylePrompt> stylePrompt(AdminPromptRecord prompt) {
        if (prompt == null || !StringUtils.hasText(prompt.getStyle()) || !StringUtils.hasText(prompt.getPrompt())) {
            return Optional.empty();
        }
        String normalizedStyle = normalizeStyleKey(prompt.getStyle());
        String promptText = prompt.getPrompt().strip();
        String titleFormatPrompt = titleFormatPrompt();
        return Optional.of(new StylePrompt(
                normalizedStyle,
                promptText,
                titleFormatPrompt,
                sha256(promptText + "\n\n" + titleFormatPrompt)
        ));
    }

    public static boolean isFreestyleStyle(String style) {
        return StringUtils.hasText(style) && FREESTYLE_STYLE.equals(normalizeStyleKey(style));
    }

    public static String normalizeStyleKey(String style) {
        return style.strip().toUpperCase(Locale.ROOT);
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

        AiTitlePrompt prompt = buildPrompt(stylePrompt.prompt(), metadata.rawContent(), stylePrompt.titleFormatPrompt());
        List<AiProviderRecord> providers = aiProviderMapper.selectEnabledProviders();
        for (AiProviderRecord provider : providers) {
            try {
                Optional<String> generated = aiTitleClient.generateTitle(provider, prompt)
                        .map(this::cleanTitle)
                        .filter(StringUtils::hasText);
                recordAiProviderSuccess(provider);
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
                if (exception instanceof HttpTimeoutException) {
                    recordAiProviderTimeout(provider, exception);
                }
            }
        }
        return Optional.empty();
    }

    public AiTitlePrompt buildPrompt(String stylePrompt, String rawContent) {
        return buildPrompt(stylePrompt, rawContent, titleFormatPrompt());
    }

    public AiTitlePrompt buildPrompt(String stylePrompt, String rawContent, String titleFormatPrompt) {
        return new AiTitlePrompt(titleFormatPrompt, stylePrompt, rawContent);
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

    private String titleFormatPrompt() {
        return aiTitleConfigService == null ? DEFAULT_TITLE_FORMAT_PROMPT : aiTitleConfigService.titleFormatPrompt();
    }

    private void recordAiProviderSuccess(AiProviderRecord provider) {
        if (aiProviderDowngradeService != null) {
            aiProviderDowngradeService.recordSuccess(provider);
        }
    }

    private void recordAiProviderTimeout(AiProviderRecord provider, Throwable exception) {
        if (aiProviderDowngradeService != null) {
            aiProviderDowngradeService.recordTimeout(provider, exception);
        }
    }

    public record StylePrompt(String style, String prompt, String titleFormatPrompt, String promptHash) {
    }
}
