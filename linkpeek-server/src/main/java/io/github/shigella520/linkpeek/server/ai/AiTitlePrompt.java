package io.github.shigella520.linkpeek.server.ai;

public record AiTitlePrompt(
        String titleFormatPrompt,
        String stylePrompt,
        String rawContent
) {
    private static final String STYLE_LABEL = "Style Prompt";
    private static final String RAW_CONTENT_LABEL = "Raw Content";

    public AiTitlePrompt {
        titleFormatPrompt = normalize(titleFormatPrompt);
        stylePrompt = normalize(stylePrompt);
        rawContent = normalize(rawContent);
    }

    public boolean hasTitleFormatPrompt() {
        return !titleFormatPrompt.isBlank();
    }

    public String styleMessage() {
        return STYLE_LABEL + "\n" + stylePrompt;
    }

    public String rawContentMessage() {
        return RAW_CONTENT_LABEL + "\n" + rawContent;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
