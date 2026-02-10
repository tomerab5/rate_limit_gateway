package com.radix.rate_limit_gateway.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "rate_limit_states",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_state_key",
                columnNames = {"tenant_id", "api_key", "http_method", "path"}
        )
)
public class RateLimitState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "api_key", nullable = false)
    private String apiKey;

    @Column(name = "http_method", nullable = false)
    private String httpMethod;

    @Column(name = "path", nullable = false)
    private String path;

    @Column(name = "window_start_epoch_seconds", nullable = false)
    private long windowStartEpochSeconds;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getWindowStartEpochSeconds() {
        return windowStartEpochSeconds;
    }

    public void setWindowStartEpochSeconds(long windowStartEpochSeconds) {
        this.windowStartEpochSeconds = windowStartEpochSeconds;
    }

    public int getRequestCount() {
        return requestCount;
    }

    public void setRequestCount(int requestCount) {
        this.requestCount = requestCount;
    }
}
