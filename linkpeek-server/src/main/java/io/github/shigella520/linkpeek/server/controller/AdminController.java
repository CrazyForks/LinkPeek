package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.server.admin.model.AdminPromptRecord;
import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.AdminPromptMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.AiProviderMapper;
import io.github.shigella520.linkpeek.server.admin.service.AdminAuthService;
import io.github.shigella520.linkpeek.server.admin.service.ProviderConfigService;
import io.github.shigella520.linkpeek.server.ai.AiApiKind;
import io.github.shigella520.linkpeek.server.stats.service.StatisticsMaintenanceService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin")
@Hidden
public class AdminController {
    private static final Pattern STYLE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final AdminAuthService adminAuthService;
    private final AdminPromptMapper adminPromptMapper;
    private final ProviderConfigService providerConfigService;
    private final AiProviderMapper aiProviderMapper;
    private final StatisticsMaintenanceService statisticsMaintenanceService;
    private final Clock clock;

    public AdminController(
            AdminAuthService adminAuthService,
            AdminPromptMapper adminPromptMapper,
            ProviderConfigService providerConfigService,
            AiProviderMapper aiProviderMapper,
            StatisticsMaintenanceService statisticsMaintenanceService,
            Clock clock
    ) {
        this.adminAuthService = adminAuthService;
        this.adminPromptMapper = adminPromptMapper;
        this.providerConfigService = providerConfigService;
        this.aiProviderMapper = aiProviderMapper;
        this.statisticsMaintenanceService = statisticsMaintenanceService;
        this.clock = clock;
    }

    @GetMapping("/session")
    public SessionResponse session(HttpServletRequest request) {
        return new SessionResponse(adminAuthService.isEnabled(), adminAuthService.isAuthenticated(request));
    }

    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(@RequestBody LoginRequest request) {
        AdminAuthService.LoginSession session = adminAuthService.login(request == null ? null : request.password());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, session.cookieHeader())
                .body(new SessionResponse(true, true));
    }

    @PostMapping("/logout")
    public ResponseEntity<SessionResponse> logout(HttpServletRequest request) {
        adminAuthService.logout(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, adminAuthService.logoutCookieHeader())
                .body(new SessionResponse(adminAuthService.isEnabled(), false));
    }

    @PostMapping("/stats/purge-all")
    public StatisticsMaintenanceService.PurgeResult purgeAllStats(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return statisticsMaintenanceService.purgeAllData();
    }

    @GetMapping("/prompts")
    public List<AdminPromptRecord> prompts(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return adminPromptMapper.selectAllPrompts();
    }

    @PutMapping("/prompts/{style}")
    @Transactional
    public AdminPromptRecord savePrompt(
            HttpServletRequest request,
            @PathVariable String style,
            @RequestBody PromptRequest promptRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        String normalizedStyle = normalizeStyle(style);
        if (promptRequest == null || !StringUtils.hasText(promptRequest.prompt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prompt must not be blank.");
        }
        AdminPromptRecord record = new AdminPromptRecord();
        record.setStyle(normalizedStyle);
        record.setPrompt(promptRequest.prompt().strip());
        record.setUpdatedAt(now());
        adminPromptMapper.upsertPrompt(record);
        return adminPromptMapper.selectPrompt(normalizedStyle);
    }

    @DeleteMapping("/prompts/{style}")
    public DeleteResponse deletePrompt(HttpServletRequest request, @PathVariable String style) {
        adminAuthService.requireAuthenticated(request);
        return new DeleteResponse(adminPromptMapper.deletePrompt(normalizeStyle(style)));
    }

    @GetMapping("/provider-config")
    public ProviderConfigResponse providerConfig(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return new ProviderConfigResponse(providerConfigService.allProviderConfigs());
    }

    @PutMapping("/provider-config/{providerId}")
    public ProviderConfigResponse saveProviderConfig(
            HttpServletRequest request,
            @PathVariable String providerId,
            @RequestBody ProviderConfigRequest configRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        if (configRequest == null || configRequest.values() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provider config values are required.");
        }
        providerConfigService.saveProviderConfigs(providerId, configRequest.values());
        return new ProviderConfigResponse(providerConfigService.allProviderConfigs());
    }

    @GetMapping("/ai-providers")
    public List<AiProviderRecord> aiProviders(HttpServletRequest request) {
        adminAuthService.requireAuthenticated(request);
        return aiProviderMapper.selectAllProviders();
    }

    @PostMapping("/ai-providers")
    @Transactional
    public AiProviderRecord createAiProvider(
            HttpServletRequest request,
            @RequestBody AiProviderRequest providerRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        AiProviderRecord record = normalizeAiProvider(null, providerRequest);
        aiProviderMapper.insertProvider(record);
        return aiProviderMapper.selectProvider(record.getId());
    }

    @PutMapping("/ai-providers/{id}")
    @Transactional
    public AiProviderRecord updateAiProvider(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestBody AiProviderRequest providerRequest
    ) {
        adminAuthService.requireAuthenticated(request);
        if (aiProviderMapper.selectProvider(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI provider was not found.");
        }
        AiProviderRecord record = normalizeAiProvider(id, providerRequest);
        aiProviderMapper.updateProvider(record);
        return aiProviderMapper.selectProvider(id);
    }

    @DeleteMapping("/ai-providers/{id}")
    public DeleteResponse deleteAiProvider(HttpServletRequest request, @PathVariable long id) {
        adminAuthService.requireAuthenticated(request);
        return new DeleteResponse(aiProviderMapper.deleteProvider(id));
    }

    private AiProviderRecord normalizeAiProvider(Long id, AiProviderRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI provider payload is required.");
        }
        String name = required(request.name(), "name");
        String inputBaseUrl = required(request.baseUrl(), "BaseURL");
        String model = required(request.model(), "model");
        AiApiKind apiKind;
        String baseUrl;
        try {
            apiKind = AiApiKind.fromValue(request.apiKind());
            baseUrl = AiApiKind.normalizeBaseUrl(inputBaseUrl);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }

        AiProviderRecord record = new AiProviderRecord();
        record.setId(id);
        record.setName(name);
        record.setEnabled(request.enabled());
        record.setSortOrder(request.sortOrder());
        record.setBaseUrl(baseUrl);
        record.setApiKind(apiKind.name());
        record.setModel(model);
        record.setEffort(StringUtils.hasText(request.effort()) ? request.effort().strip() : null);
        record.setApiKey(request.apiKey() == null ? "" : request.apiKey().strip());
        record.setUpdatedAt(now());
        return record;
    }

    private String normalizeStyle(String style) {
        if (!StringUtils.hasText(style) || !STYLE_PATTERN.matcher(style.strip()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Style must be 1-64 chars and only contain letters, numbers, dot, underscore, or dash.");
        }
        return style.strip();
    }

    private String required(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " must not be blank.");
        }
        return value.strip();
    }

    private long now() {
        return Instant.now(clock).toEpochMilli();
    }

    public record SessionResponse(boolean enabled, boolean authenticated) {
    }

    public record LoginRequest(String password) {
    }

    public record PromptRequest(String prompt) {
    }

    public record ProviderConfigRequest(Map<String, String> values) {
    }

    public record ProviderConfigResponse(Map<String, Map<String, String>> configs) {
    }

    public record AiProviderRequest(
            String name,
            boolean enabled,
            int sortOrder,
            String baseUrl,
            String apiKind,
            String model,
            String effort,
            String apiKey
    ) {
    }

    public record DeleteResponse(int deleted) {
    }
}
