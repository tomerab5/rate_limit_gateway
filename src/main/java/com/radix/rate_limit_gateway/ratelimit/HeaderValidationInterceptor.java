package com.radix.rate_limit_gateway.ratelimit;

import com.radix.rate_limit_gateway.api.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Component
public class HeaderValidationInterceptor implements HandlerInterceptor {

    @Override
    //middleare
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {

        String tenantId = request.getHeader("X-Tenant-Id");
        if (isBlank(tenantId)) {
            writeJson400(response, new ApiError("MISSING_HEADER", "X-Tenant-Id is required"));
            return false; // STOP request (controller will NOT run)
        }

        String apiKey = request.getHeader("X-Api-Key");
        if (isBlank(apiKey)) {
            writeJson400(response, new ApiError("MISSING_HEADER", "X-Api-Key is required"));
            return false; // STOP request
        }

        return true; // CONTINUE to controller. Like next() in Express
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();  //treats an empty string ("") ass missing
    }

    private void writeJson400(HttpServletResponse response, ApiError error) throws IOException {
        response.setStatus(400);
        response.setContentType("application/json");

        // We build the JSON string manually here to avoid extra complexity for now.
        String json = "{\"errorCode\":\"" + escape(error.getErrorCode())
                + "\",\"message\":\"" + escape(error.getMessage()) + "\"}";

        response.getWriter().write(json);
    }

    private String escape(String s) {
        // Very small escape to keep JSON valid (enough for our simple messages)
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
