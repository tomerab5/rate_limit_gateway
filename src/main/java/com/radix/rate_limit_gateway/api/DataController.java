package com.radix.rate_limit_gateway.api;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController         //This class handles HTTP requests  “convert return value to JSON automatically.”
@RequestMapping("/api")      //app.use("/api", router) in Express (Every endpoint in this class starts with /api.)
public class DataController {

    @GetMapping("/data")    //app.get("/api/data", ...) in Express
    public Map<String, Object> getData() {
        return Map.of(
                "ok", true,
                "method", "GET",
                "message", "dummy response"
        );  //Spring automatically converts  Map to JSON
    }

    @PostMapping("/data")  //app.post("/api/data", ...) in Express
    public Map<String, Object> postData() {
        return Map.of(
                "ok", true,
                "method", "POST",
                "message", "dummy response"
        );
    }
}
