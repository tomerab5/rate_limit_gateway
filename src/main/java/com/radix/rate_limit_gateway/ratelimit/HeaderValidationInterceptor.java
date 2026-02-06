package com.radix.rate_limit_gateway.ratelimit;

import com.radix.rate_limit_gateway.api.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Instant;

@Component
public class HeaderValidationInterceptor implements HandlerInterceptor {

    /*
     * One RateLimitService instance for the whole app.
     * This means counters are shared across all requests (in-memory).
     * Later we can replace this with a DB-backed solution.
     */
    private final RateLimitService rateLimitService = new RateLimitService();

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
        long nowEpochSeconds = Instant.now().getEpochSecond();

        RateLimitService.Decision decision =
                rateLimitService.check(tenantId, apiKey, method, path, nowEpochSeconds);

        if (!decision.allowed) {
            // 429 Too Many Requests
            writeJson(response, 429,
                    new ApiError("RATE_LIMITED",
                            "Too many requests. Retry after " + decision.retryAfterSeconds + " seconds."));

            // Helpful standard header for clients/tools
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds));

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
        response.setContentType("application/json");

        // We build the JSON string manually here to avoid extra complexity for now.
        String json = "{\"errorCode\":\"" + escape(error.getErrorCode())
                + "\",\"message\":\"" + escape(error.getMessage()) + "\"}";

        response.getWriter().write(json);  //Like res.status(400).json(...) in Express
    }

    private String escape(String s) {
        // Very small escape to keep JSON valid (enough for our simple messages)
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
