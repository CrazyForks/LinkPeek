package io.github.shigella520.linkpeek.core.util;

import java.util.ArrayList;
import java.util.List;

public final class PreviewRawContentFormatter {
    private static final String ELLIPSIS = "…";

    private PreviewRawContentFormatter() {
    }

    public static String format(String title, String body, List<String> replies, int maxLength) {
        List<String> sections = new ArrayList<>();
        String normalizedTitle = normalizeBlock(title);
        if (!normalizedTitle.isBlank()) {
            sections.add("原标题\n" + normalizedTitle);
        }

        String normalizedBody = normalizeBlock(body);
        if (!normalizedBody.isBlank()) {
            sections.add("正文\n" + normalizedBody);
        }

        List<String> normalizedReplies = normalizeReplies(replies);
        if (!normalizedReplies.isEmpty()) {
            StringBuilder replyBlock = new StringBuilder("回帖");
            for (int index = 0; index < normalizedReplies.size(); index++) {
                replyBlock.append('\n').append(index + 1).append(". ").append(normalizedReplies.get(index));
            }
            sections.add(replyBlock.toString());
        }

        return truncate(normalizeBlock(String.join("\n\n", sections)), maxLength);
    }

    private static List<String> normalizeReplies(List<String> replies) {
        if (replies == null || replies.isEmpty()) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        for (String reply : replies) {
            String value = normalizeBlock(reply);
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private static String truncate(String value, int maxLength) {
        if (maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1).stripTrailing() + ELLIPSIS;
    }

    private static String normalizeBlock(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', '\n')
                .replace('\t', ' ')
                .replace('\u00A0', ' ')
                .replaceAll("[ ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
