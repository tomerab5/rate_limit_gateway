package com.radix.rate_limit_gateway.admin;

import com.radix.rate_limit_gateway.audit.AuditEvent;
import com.radix.rate_limit_gateway.audit.AuditEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/admin/audit")
public class AdminAuditController {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final AuditEventRepository auditEventRepository;

    public AdminAuditController(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @GetMapping
    public List<AuditEvent> listAuditEvents(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) Integer limit
    ) {
        int resolvedLimit = resolveLimit(limit);
        PageRequest page = PageRequest.of(
                0,
                resolvedLimit,
                Sort.by(Sort.Direction.DESC, "timestampUtc")
        );

        if (tenantId == null || tenantId.trim().isEmpty()) {
            return auditEventRepository.findAll(page).getContent();
        }

        return auditEventRepository.findByTenantId(tenantId.trim(), page).getContent();
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT
            );
        }
        return limit;
    }
}
