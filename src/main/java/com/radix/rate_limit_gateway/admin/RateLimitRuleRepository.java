package com.radix.rate_limit_gateway.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RateLimitRuleRepository extends JpaRepository<RateLimitRule, Long> {
    List<RateLimitRule> findByTenantId(String tenantId);

    Optional<RateLimitRule> findByTenantIdAndApiKeyAndHttpMethodAndPath(
            String tenantId,
            String apiKey,
            String httpMethod,
            String path
    );
}
