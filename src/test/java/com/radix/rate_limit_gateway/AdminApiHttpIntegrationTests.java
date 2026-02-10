package com.radix.rate_limit_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminApiHttpIntegrationTests {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void adminCrudAndRuleEnforcementWorkOverHttp() throws Exception {
        String tenantId = "http-" + UUID.randomUUID();
        String apiKey = "k-" + UUID.randomUUID();
        String base = "http://localhost:" + port;

        String payload = """
                {
                  "tenantId":"%s",
                  "apiKey":"%s",
                  "method":"GET",
                  "path":"/api/data",
                  "limit":2,
                  "windowSeconds":60
                }
                """.formatted(tenantId, apiKey);

        HttpResponse<String> putResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(base + "/admin/limits"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(payload))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, putResp.statusCode());
        assertTrue(putResp.body().contains("\"tenantId\":\"" + tenantId + "\""));

        HttpResponse<String> listResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(base + "/admin/limits?tenantId=" + tenantId))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, listResp.statusCode());
        assertTrue(listResp.body().contains("\"apiKey\":\"" + apiKey + "\""));

        HttpResponse<String> first = sendProtectedGet(base, tenantId, apiKey);
        HttpResponse<String> second = sendProtectedGet(base, tenantId, apiKey);
        HttpResponse<String> third = sendProtectedGet(base, tenantId, apiKey);

        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());
        assertEquals(429, third.statusCode());
        assertTrue(third.body().contains("\"ruleId\""));
    }

    private HttpResponse<String> sendProtectedGet(String base, String tenantId, String apiKey) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(base + "/api/data"))
                        .header("X-Tenant-Id", tenantId)
                        .header("X-Api-Key", apiKey)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }
}