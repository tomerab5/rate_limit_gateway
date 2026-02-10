package com.radix.rate_limit_gateway.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@RestController
@RequestMapping("/admin/limits")
public class AdminLimitsController {
    private final RateLimitRuleRepository ruleRepository;

    public AdminLimitsController(RateLimitRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @PutMapping
    public RateLimitRule putRule(@RequestBody RateLimitRuleUpsertRequest request) {
        validateRequest(request);
        String method = normalizeMethod(request.getMethod());
        String path = normalizePath(request.getPath());

        RateLimitRule rule = resolveRuleTarget(request, method, path);
        rule.setTenantId(request.getTenantId().trim());
        rule.setApiKey(request.getApiKey().trim());
        rule.setHttpMethod(method);
        rule.setPath(path);
        rule.setLimitValue(request.getLimit());
        rule.setWindowSeconds(request.getWindowSeconds());

        return ruleRepository.save(rule);
    }

    @GetMapping
    public List<RateLimitRule> getRules(@RequestParam String tenantId) {
        if (isBlank(tenantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId is required");
        }
        return ruleRepository.findByTenantId(tenantId.trim());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable Long id) {
        if (!ruleRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "rule not found");
        }
        ruleRepository.deleteById(id);
    }

    private RateLimitRule resolveRuleTarget(RateLimitRuleUpsertRequest request, String method, String path) {
        if (request.getId() != null) {
            return ruleRepository.findById(request.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rule not found"));
        }

        Optional<RateLimitRule> existing = ruleRepository.findByTenantIdAndApiKeyAndHttpMethodAndPath(
                request.getTenantId().trim(),
                request.getApiKey().trim(),
                method,
                path
        );
        return existing.orElseGet(RateLimitRule::new);
    }

    private void validateRequest(RateLimitRuleUpsertRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (isBlank(request.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId is required");
        }
        if (isBlank(request.getApiKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "apiKey is required");
        }
        if (isBlank(request.getMethod())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "method is required");
        }
        if (isBlank(request.getPath())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        if (request.getLimit() == null || request.getLimit() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be > 0");
        }
        if (request.getWindowSeconds() == null || request.getWindowSeconds() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "windowSeconds must be > 0");
        }
    }

    private String normalizePath(String path) {
        String trimmed = path.trim();
        if ("*".equals(trimmed)) {
            return "*";
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private String normalizeMethod(String method) {
        String trimmed = method.trim();
        if ("*".equals(trimmed)) {
            return "*";
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
