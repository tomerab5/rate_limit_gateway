package com.radix.rate_limit_gateway.api;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class DataController {

    @GetMapping("/data")
    public Map<String, Object> getData() {
        return Map.of(
                "ok", true,
                "method", "GET",
                "message", "dummy response"
        );
    }

    @PostMapping("/data")
    public Map<String, Object> postData() {
        return Map.of(
                "ok", true,
                "method", "POST",
                "message", "dummy response"
        );
    }
}
