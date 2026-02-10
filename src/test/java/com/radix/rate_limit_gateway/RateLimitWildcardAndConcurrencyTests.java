package com.radix.rate_limit_gateway;

import com.radix.rate_limit_gateway.admin.RateLimitRule;
import com.radix.rate_limit_gateway.admin.RateLimitRuleRepository;
import com.radix.rate_limit_gateway.ratelimit.RateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RateLimitWildcardAndConcurrencyTests {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private RateLimitRuleRepository ruleRepository;

    @Test
    void wildcardRuleAppliesAndExactRuleHasPriority() {
        String tenant = "wild-" + UUID.randomUUID();
        String exactKey = "k-exact-" + UUID.randomUUID();
        String wildcardKey1 = "k-any1-" + UUID.randomUUID();
        String wildcardKey2 = "k-any2-" + UUID.randomUUID();
        long now = 1_700_000_100L;

        RateLimitRule wildcard = new RateLimitRule();
        wildcard.setTenantId(tenant);
        wildcard.setApiKey("*");
        wildcard.setHttpMethod("*");
        wildcard.setPath("*");
        wildcard.setLimitValue(1);
        wildcard.setWindowSeconds(60);
        ruleRepository.save(wildcard);

        RateLimitRule exact = new RateLimitRule();
        exact.setTenantId(tenant);
        exact.setApiKey(exactKey);
        exact.setHttpMethod("GET");
        exact.setPath("/api/data");
        exact.setLimitValue(3);
        exact.setWindowSeconds(60);
        ruleRepository.save(exact);

        RateLimitService.Decision w1 = rateLimitService.check(
                tenant, wildcardKey1, "POST", "/api/anything", "127.0.0.1", now
        );
        RateLimitService.Decision w2 = rateLimitService.check(
                tenant, wildcardKey2, "GET", "/api/other", "127.0.0.1", now
        );
        assertTrue(w1.allowed);
        assertFalse(w2.allowed);

        RateLimitService.Decision e1 = rateLimitService.check(
                tenant, exactKey, "GET", "/api/data", "127.0.0.1", now
        );
        RateLimitService.Decision e2 = rateLimitService.check(
                tenant, exactKey, "GET", "/api/data", "127.0.0.1", now
        );
        RateLimitService.Decision e3 = rateLimitService.check(
                tenant, exactKey, "GET", "/api/data", "127.0.0.1", now
        );
        RateLimitService.Decision e4 = rateLimitService.check(
                tenant, exactKey, "GET", "/api/data", "127.0.0.1", now
        );

        assertTrue(e1.allowed);
        assertTrue(e2.allowed);
        assertTrue(e3.allowed);
        assertFalse(e4.allowed);
    }

    @Test
    void concurrentRequestsRespectConfiguredLimit() throws Exception {
        String tenant = "conc-" + UUID.randomUUID();
        String apiKey = "k-" + UUID.randomUUID();
        String method = "GET";
        String path = "/api/data";
        long now = 1_700_000_200L;

        RateLimitRule rule = new RateLimitRule();
        rule.setTenantId(tenant);
        rule.setApiKey(apiKey);
        rule.setHttpMethod(method);
        rule.setPath(path);
        rule.setLimitValue(5);
        rule.setWindowSeconds(60);
        ruleRepository.save(rule);

        int totalRequests = 30;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            tasks.add(() -> {
                startGate.await();
                RateLimitService.Decision d = rateLimitService.check(
                        tenant, apiKey, method, path, "127.0.0.1", now
                );
                return d.allowed;
            });
        }

        List<Future<Boolean>> futures = new ArrayList<>();
        for (Callable<Boolean> task : tasks) {
            futures.add(pool.submit(task));
        }

        startGate.countDown();

        int allowed = 0;
        int blocked = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) {
                allowed++;
            } else {
                blocked++;
            }
        }

        pool.shutdownNow();

        assertEquals(5, allowed, "Allowed count must equal configured limit");
        assertEquals(totalRequests - 5, blocked, "All excess concurrent requests must be blocked");
    }

    @Test
    void tenantWideWildcardRuleAllowsOnlyOneTotalAcrossDifferentMethodAndPath() {
        String tenant = "t2";
        long now = 1_700_000_300L;

        RateLimitRule wildcard = ruleRepository
                .findByTenantIdAndApiKeyAndHttpMethodAndPath(tenant, "*", "*", "*")
                .orElseGet(() -> {
                    RateLimitRule rule = new RateLimitRule();
                    rule.setTenantId(tenant);
                    rule.setApiKey("*");
                    rule.setHttpMethod("*");
                    rule.setPath("*");
                    return rule;
                });
        wildcard.setLimitValue(1);
        wildcard.setWindowSeconds(60);
        ruleRepository.save(wildcard);

        RateLimitService.Decision first = rateLimitService.check(
                tenant, "k-alpha", "GET", "/api/data", "127.0.0.1", now
        );
        RateLimitService.Decision second = rateLimitService.check(
                tenant, "k-beta", "POST", "/api/other", "127.0.0.1", now
        );

        assertTrue(first.allowed, "First request should consume the single wildcard quota");
        assertFalse(second.allowed, "Second request should be blocked even with different method/path/key");
    }
}
