package com.radix.rate_limit_gateway.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RateLimitStateRepository extends JpaRepository<RateLimitState, Long> {
    Optional<RateLimitState> findByTenantIdAndApiKeyAndHttpMethodAndPath(
            String tenantId,
            String apiKey,
            String httpMethod,
            String path
    );
}
