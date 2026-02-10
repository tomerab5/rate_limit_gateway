package com.radix.rate_limit_gateway.admin;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Component
@ConditionalOnProperty(
        name = "app.seed-default-rules.enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class DefaultRateLimitRulesInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DefaultRateLimitRulesInitializer.class);
    private final RateLimitRuleRepository ruleRepository;

    public DefaultRateLimitRulesInitializer(RateLimitRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("Seeding default rate limit rules...");
        List<SeedRule> defaults = List.of(
                new SeedRule("t1", "k1", "GET", "/api/data", 6, 60),
                new SeedRule("t1", "k1", "POST", "/api/data", 4, 60),
                new SeedRule("t1", "k2", "GET", "/api/data", 10, 60),
                new SeedRule("t1", "k2", "POST", "/api/data", 10, 60),
                new SeedRule("t2", "*", "*", "*", 1, 100)
        );

        for (SeedRule seed : defaults) {
            RateLimitRule rule = ruleRepository
                    .findByTenantIdAndApiKeyAndHttpMethodAndPath(
                            seed.tenantId,
                            seed.apiKey,
                            seed.method,
                            seed.path
                    )
                    .orElseGet(RateLimitRule::new);

            rule.setTenantId(seed.tenantId);
            rule.setApiKey(seed.apiKey);
            rule.setHttpMethod(seed.method);
            rule.setPath(seed.path);
            rule.setLimitValue(seed.limit);
            rule.setWindowSeconds(seed.windowSeconds);
            ruleRepository.save(rule);
        }
        log.info("Default rate limit rules in DB: {}", ruleRepository.count());
    }

    private record SeedRule(
            String tenantId,
            String apiKey,
            String method,
            String path,
            int limit,
            int windowSeconds
    ) {
    }
}
