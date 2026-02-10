package com.radix.rate_limit_gateway;

import com.radix.rate_limit_gateway.admin.RateLimitRule;
import com.radix.rate_limit_gateway.admin.RateLimitRuleRepository;
import com.radix.rate_limit_gateway.audit.AuditEventRepository;
import com.radix.rate_limit_gateway.ratelimit.RateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RateLimitAuditIntegrationTests {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private RateLimitRuleRepository ruleRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void blockedRequestIsPersistedAsAuditEvent() throws Exception {
        String tenantId = "audit-" + UUID.randomUUID();
        String apiKey = "k-" + UUID.randomUUID();
        String method = "GET";
        String path = "/api/data";

        RateLimitRule rule = new RateLimitRule();
        rule.setTenantId(tenantId);
        rule.setApiKey(apiKey);
        rule.setHttpMethod(method);
        rule.setPath(path);
        rule.setLimitValue(1);
        rule.setWindowSeconds(60);
        ruleRepository.save(rule);

        long now = 1_700_000_000L;

        RateLimitService.Decision first = rateLimitService.check(
                tenantId,
                apiKey,
                method,
                path,
                "127.0.0.1",
                now
        );
        assertTrue(first.allowed);

        RateLimitService.Decision second = rateLimitService.check(
                tenantId,
                apiKey,
                method,
                path,
                "127.0.0.1",
                now
        );
        assertFalse(second.allowed);

        long count = auditEventRepository.countByTenantIdAndDecision(tenantId, "BLOCKED");
        assertEquals(1L, count);
    }
}
