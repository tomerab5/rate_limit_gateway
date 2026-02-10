package com.radix.rate_limit_gateway.ratelimit;

import com.radix.rate_limit_gateway.admin.RateLimitRule;
import com.radix.rate_limit_gateway.admin.RateLimitRuleRepository;
import com.radix.rate_limit_gateway.admin.RateLimitState;
import com.radix.rate_limit_gateway.admin.RateLimitStateRepository;
import com.radix.rate_limit_gateway.audit.AuditEvent;
import com.radix.rate_limit_gateway.audit.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiter (DB-backed state).
 * Fallback default rule: 5 requests per 60 seconds.
 */
@Service
public class RateLimitService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int DEFAULT_WINDOW_SECONDS = 60;
    private static final String DEFAULT_RULE_ID = "default-fixed-window";

    private final RateLimitRuleRepository ruleRepository;
    private final RateLimitStateRepository stateRepository;
    private final AuditEventRepository auditEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    public RateLimitService(
            RateLimitRuleRepository ruleRepository,
            RateLimitStateRepository stateRepository,
            AuditEventRepository auditEventRepository,
            TransactionTemplate transactionTemplate
    ) {
        this.ruleRepository = ruleRepository;
        this.stateRepository = stateRepository;
        this.auditEventRepository = auditEventRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public Decision check(
            String tenantId,
            String apiKey,
            String method,
            String path,
            String clientIp,
            long nowEpochSeconds
    ) {
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        String normalizedPath = normalizePath(path);
        AppliedRule appliedRule = resolveRule(tenantId, apiKey, normalizedMethod, normalizedPath);
        String lockKey = tenantId + "|" + appliedRule.stateApiKey + "|" + appliedRule.stateMethod + "|" + appliedRule.statePath;

        synchronized (keyLocks.computeIfAbsent(lockKey, k -> new Object())) {
            return transactionTemplate.execute(status -> {
                RateLimitState state = stateRepository
                        .findByTenantIdAndApiKeyAndHttpMethodAndPath(
                                tenantId,
                                appliedRule.stateApiKey,
                                appliedRule.stateMethod,
                                appliedRule.statePath
                        )
                        .orElseGet(() -> newState(
                                tenantId,
                                appliedRule.stateApiKey,
                                appliedRule.stateMethod,
                                appliedRule.statePath,
                                nowEpochSeconds
                        ));

                long windowStart = state.getWindowStartEpochSeconds();
                long windowEnd = windowStart + appliedRule.windowSeconds;

                if (nowEpochSeconds >= windowEnd) {
                    state.setWindowStartEpochSeconds(nowEpochSeconds);
                    state.setRequestCount(1);
                } else {
                    state.setRequestCount(state.getRequestCount() + 1);
                }

                stateRepository.save(state);

                windowEnd = state.getWindowStartEpochSeconds() + appliedRule.windowSeconds;

                if (state.getRequestCount() <= appliedRule.limitValue) {
                    return new Decision(true, 0, appliedRule.ruleId, appliedRule.windowSeconds);
                }

                int retryAfter = (int) Math.max(0, windowEnd - nowEpochSeconds);
                writeBlockedAudit(
                        nowEpochSeconds,
                        tenantId,
                        apiKey,
                        normalizedMethod,
                        normalizedPath,
                        appliedRule.ruleId,
                        clientIp,
                        retryAfter,
                        appliedRule.windowSeconds,
                        state.getRequestCount()
                );
                return new Decision(false, retryAfter, appliedRule.ruleId, appliedRule.windowSeconds);
            });
        }
    }

    private void writeBlockedAudit(
            long nowEpochSeconds,
            String tenantId,
            String apiKey,
            String method,
            String path,
            String ruleId,
            String clientIp,
            int retryAfterSeconds,
            int windowSeconds,
            int requestCount
    ) {
        AuditEvent event = new AuditEvent();
        event.setTimestampUtc(Instant.ofEpochSecond(nowEpochSeconds));
        event.setTenantId(tenantId);
        event.setApiKey(apiKey);
        event.setMethod(method);
        event.setPath(path);
        event.setRuleId(ruleId);
        event.setClientIp((clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp);
        event.setDecision("BLOCKED");
        event.setRetryAfterSeconds(retryAfterSeconds);
        event.setWindowSeconds(windowSeconds);
        event.setRequestCount(requestCount);
        auditEventRepository.save(event);
    }

    private AppliedRule resolveRule(String tenantId, String apiKey, String method, String path) {
        List<RateLimitRule> candidates = ruleRepository.findByTenantId(tenantId).stream()
                .filter(rule -> matches(rule.getApiKey(), apiKey))
                .filter(rule -> matches(rule.getHttpMethod(), method))
                .filter(rule -> matches(rule.getPath(), path))
                .sorted(Comparator.comparingInt((RateLimitRule r) -> specificityScore(r, apiKey, method, path)).reversed())
                .toList();

        Optional<RateLimitRule> configured = candidates.stream().findFirst();
        if (configured.isPresent()) {
            RateLimitRule rule = configured.get();
            return new AppliedRule(
                    String.valueOf(rule.getId()),
                    rule.getLimitValue(),
                    rule.getWindowSeconds(),
                    rule.getApiKey(),
                    rule.getHttpMethod(),
                    rule.getPath()
            );
        }
        return new AppliedRule(
                DEFAULT_RULE_ID,
                DEFAULT_LIMIT,
                DEFAULT_WINDOW_SECONDS,
                apiKey,
                method,
                path
        );
    }

    private boolean matches(String ruleValue, String requestValue) {
        return "*".equals(ruleValue) || ruleValue.equals(requestValue);
    }

    private int specificityScore(RateLimitRule rule, String apiKey, String method, String path) {
        int score = 0;
        if (rule.getApiKey().equals(apiKey)) {
            score++;
        }
        if (rule.getHttpMethod().equals(method)) {
            score++;
        }
        if (rule.getPath().equals(path)) {
            score++;
        }
        return score;
    }

    private RateLimitState newState(String tenantId, String apiKey, String method, String path, long nowEpochSeconds) {
        RateLimitState state = new RateLimitState();
        state.setTenantId(tenantId);
        state.setApiKey(apiKey);
        state.setHttpMethod(method);
        state.setPath(path);
        state.setWindowStartEpochSeconds(nowEpochSeconds);
        state.setRequestCount(0);
        return state;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    /** Output of a rate-limit decision. */
    public static final class Decision {
        public final boolean allowed;
        public final int retryAfterSeconds;
        public final String ruleId;
        public final int windowSeconds;

        public Decision(boolean allowed, int retryAfterSeconds, String ruleId, int windowSeconds) {
            this.allowed = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
            this.ruleId = ruleId;
            this.windowSeconds = windowSeconds;
        }
    }

    private static final class AppliedRule {
        private final String ruleId;
        private final int limitValue;
        private final int windowSeconds;
        private final String stateApiKey;
        private final String stateMethod;
        private final String statePath;

        private AppliedRule(
                String ruleId,
                int limitValue,
                int windowSeconds,
                String stateApiKey,
                String stateMethod,
                String statePath
        ) {
            this.ruleId = ruleId;
            this.limitValue = limitValue;
            this.windowSeconds = windowSeconds;
            this.stateApiKey = stateApiKey;
            this.stateMethod = stateMethod;
            this.statePath = statePath;
        }
    }
}
