package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AdminAuthService {
    public static final String COOKIE_NAME = "LINKPEEK_ADMIN_SESSION";

    private static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final int TOKEN_BYTES = 32;

    private final LinkPeekProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    public AdminAuthService(LinkPeekProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return StringUtils.hasText(properties.getStatsAdminPassword());
    }

    public LoginSession login(String password) {
        if (!isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Admin backend is not enabled.");
        }
        if (!properties.getStatsAdminPassword().equals(password)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid management password.");
        }

        cleanupExpiredSessions();
        String token = newToken();
        sessions.put(token, expiresAt());
        return new LoginSession(token, loginCookie(token).toString());
    }

    public void logout(HttpServletRequest request) {
        sessionToken(request).ifPresent(sessions::remove);
    }

    public boolean isAuthenticated(HttpServletRequest request) {
        Optional<String> token = sessionToken(request);
        if (token.isEmpty()) {
            return false;
        }
        Long expiresAt = sessions.get(token.get());
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt <= Instant.now(clock).toEpochMilli()) {
            sessions.remove(token.get());
            return false;
        }
        return true;
    }

    public void requireAuthenticated(HttpServletRequest request) {
        if (!isAuthenticated(request)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin login is required.");
        }
    }

    public String logoutCookieHeader() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .path("/")
                .sameSite("Lax")
                .maxAge(Duration.ZERO)
                .build()
                .toString();
    }

    private ResponseCookie loginCookie(String token) {
        return ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .path("/")
                .sameSite("Lax")
                .maxAge(SESSION_TTL)
                .build();
    }

    private Optional<String> sessionToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (var cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private long expiresAt() {
        return Instant.now(clock).plus(SESSION_TTL).toEpochMilli();
    }

    private void cleanupExpiredSessions() {
        long now = Instant.now(clock).toEpochMilli();
        Iterator<Map.Entry<String, Long>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
            }
        }
    }

    public record LoginSession(String token, String cookieHeader) {
    }
}
