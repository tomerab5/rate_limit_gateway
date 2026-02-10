package com.radix.rate_limit_gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.radix.rate_limit_gateway.api.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Instant;

@Component
public class HeaderValidationInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public HeaderValidationInterceptor(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    //middleare
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {

        /*
         * =========================
         * 1) Header validation
         * =========================
         * We require these headers for all /api/** requests:
         * - X-Tenant-Id
         * - X-Api-Key
         *
         * If missing, we return HTTP 400 and STOP (controller will not run).
         */

        String tenantId = request.getHeader("X-Tenant-Id");
        if (isBlank(tenantId)) {
            writeJson(response, 400, new ApiError("MISSING_HEADER", "X-Tenant-Id is required"));
            return false; // STOP request (controller will NOT run)
        }

        String apiKey = request.getHeader("X-Api-Key");
        if (isBlank(apiKey)) {
            writeJson(response, 400, new ApiError("MISSING_HEADER", "X-Api-Key is required"));
            return false; // STOP request
        }

        /*
         * =========================
         * 2) Rate limiting
         * =========================
         * We rate limit per (tenantId, apiKey, method, path).
         * That means different tenants/keys/endpoints/methods do NOT share counters.
         *
         * If rate limit exceeded, we return HTTP 429 and STOP.
         */

        String method = request.getMethod();       // e.g. GET, POST
        String path = request.getRequestURI();     // e.g. /api/data
        String clientIp = request.getRemoteAddr(); // best effort client ip
        long nowEpochSeconds = Instant.now().getEpochSecond();

        RateLimitService.Decision decision =
                rateLimitService.check(tenantId, apiKey, method, path, clientIp, nowEpochSeconds);

        if (!decision.allowed) {
            // Helpful standard header for clients/tools
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds));
            // 429 Too Many Requests
            writeRateLimitedJson(response, decision.ruleId, decision.retryAfterSeconds);

            return false; // STOP request
        }

        return true; // CONTINUE to controller. Like next() in Express
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();  //treats an empty string ("") ass missing
    }

    /*
     * Writes a JSON response with a given status.
     * (We used to have writeJson400 only, now we reuse it for 400 and 429.)
     */
    private void writeJson(HttpServletResponse response, int status, ApiError error) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), error);
    }

    private void writeRateLimitedJson(HttpServletResponse response, String ruleId, int retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), new RateLimitError(
                "RATE_LIMITED",
                ruleId,
                retryAfterSeconds,
                "Too many requests. Retry after " + retryAfterSeconds + " seconds."
        ));
    }

    private record RateLimitError(
            String errorCode,
            String ruleId,
            int retryAfterSeconds,
            String message
    ) {
    }
}
