package io.github.shigella520.linkpeek.server.ai;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public enum AiApiKind {
    RESPONSES("/responses"),
    CHAT_COMPLETIONS("/chat/completions");

    private final String endpointPath;

    AiApiKind(String endpointPath) {
        this.endpointPath = endpointPath;
    }

    public static AiApiKind fromValue(String value) {
        if (!isText(value)) {
            throw new IllegalArgumentException("API format must not be blank.");
        }
        String normalized = value.strip()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        if ("CHAT".equals(normalized) || "CHAT_COMPLETION".equals(normalized) || "CHAT_COMPLETIONS".equals(normalized)) {
            return CHAT_COMPLETIONS;
        }
        if ("RESPONSE".equals(normalized) || "RESPONSES".equals(normalized)) {
            return RESPONSES;
        }
        throw new IllegalArgumentException("API format must be chat or responses.");
    }

    public static String normalizeBaseUrl(String baseUrl) {
        URI uri = parseBaseUrl(baseUrl);
        String path = normalizedPath(uri);
        if (!path.endsWith("/v1")) {
            throw new IllegalArgumentException("BaseURL must end with /v1.");
        }
        return uriWithoutQueryOrFragment(uri, path);
    }

    public URI endpointUri(String baseUrl) {
        return URI.create(normalizeBaseUrl(baseUrl) + endpointPath);
    }

    private static URI parseBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl == null ? "" : baseUrl.strip());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("BaseURL must be a valid URL.", exception);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("BaseURL must include scheme and host.");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("BaseURL must not include query or fragment.");
        }
        return uri;
    }

    private static String normalizedPath(URI uri) {
        return stripTrailingSlash(uri.getPath() == null ? "" : uri.getPath()).toLowerCase(Locale.ROOT);
    }

    private static String uriWithoutQueryOrFragment(URI uri, String path) {
        try {
            return new URI(
                    uri.getScheme(),
                    uri.getRawUserInfo(),
                    uri.getHost(),
                    uri.getPort(),
                    path,
                    null,
                    null
            ).toString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("BaseURL must be a valid URL.", exception);
        }
    }

    private static boolean isText(String value) {
        return value != null && !value.isBlank();
    }

    private static String stripTrailingSlash(String value) {
        String current = value;
        while (current.endsWith("/") && current.length() > 1) {
            current = current.substring(0, current.length() - 1);
        }
        return current;
    }
}
