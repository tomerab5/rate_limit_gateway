package com.radix.rate_limit_gateway.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    long countByTenantIdAndDecision(String tenantId, String decision);

    Page<AuditEvent> findByTenantId(String tenantId, Pageable pageable);
}
