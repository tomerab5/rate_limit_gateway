# Rate Limiting Gateway

Spring Boot gateway that enforces per-tenant/per-key quotas on protected APIs, persists limiter state, and audits blocked requests.

## Contents

1. Overview
2. Quick Start
3. Assignment Coverage
4. Architecture and Flow
5. API Summary
6. Persistence Model
7. Build and Verification
8. Script Reference
9. One-Command Reviewer Check
10. Scoring Evidence
11. Configuration
12. Troubleshooting
13. Design Decisions
14. Known Limitations
15. AI Agent & Setup
16. Additional Docs

## 1. Overview

Protected endpoints:

- `GET /api/data`
- `POST /api/data`

Required headers on protected endpoints:

- `X-Tenant-Id`
- `X-Api-Key`

Rate limit identity tuple:

- `(tenantId, apiKey, method, path)`

When over limit:

- Returns `429`
- Includes `Retry-After` response header
- Returns JSON with `errorCode`, `ruleId`, `retryAfterSeconds`
- Persists audit event (`decision=BLOCKED`)

## 2. Quick Start

### Linux/macOS

```bash
./mvnw test
./mvnw package
./mvnw spring-boot:run
```

### Windows (PowerShell)

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
.\mvnw.cmd spring-boot:run
```

Quick request check:

```bash
curl -i http://localhost:8081/api/data -H "X-Tenant-Id: t1" -H "X-Api-Key: k1"
```

Expected: HTTP `200` with JSON body containing `"ok": true`.

## 3. Assignment Coverage

| Requirement | Status | Where |
|---|---|---|
| Two protected endpoints | Done | `GET /api/data`, `POST /api/data` |
| Missing/blank headers => `400` JSON | Done | `HeaderValidationInterceptor` |
| Middleware enforcement before controller | Done | `HandlerInterceptor` on `/api/**` |
| `429` includes code/rule/retry | Done | `HeaderValidationInterceptor` + `RateLimitService` |
| Admin limit CRUD | Done | `PUT/GET/DELETE /admin/limits` |
| Persist rules + limiter state + audit | Done | H2 file DB entities/repositories |
| Blocked request audit (transactional with decision) | Done | `RateLimitService.check(...)` transaction |
| Automated tests + E2E script | Done | JUnit + `scripts/e2e.sh` |
| S3 export | Optional (not implemented) | Out of scope by choice |

Detailed mapping: `docs/ASSIGNMENT_TRACEABILITY.md`.

## 4. Architecture and Flow

Request path:

1. Request enters `/api/**`.
2. `HeaderValidationInterceptor` validates required headers.
3. Interceptor calls `RateLimitService.check(...)`.
4. Service resolves best matching rule from DB (`exact` and `*` wildcard support).
5. Service updates persistent fixed-window state.
6. If blocked, service writes audit row and interceptor returns `429`.
7. If allowed, request reaches controller.

Core classes:

- `src/main/java/com/radix/rate_limit_gateway/ratelimit/HeaderValidationInterceptor.java`
- `src/main/java/com/radix/rate_limit_gateway/ratelimit/RateLimitService.java`
- `src/main/java/com/radix/rate_limit_gateway/admin/AdminLimitsController.java`
- `src/main/java/com/radix/rate_limit_gateway/admin/AdminAuditController.java`

## 5. API Summary

| Endpoint | Method | Success | Common errors |
|---|---|---|---|
| `/api/data` | `GET` | `200` | `400`, `429` |
| `/api/data` | `POST` | `200` | `400`, `429` |
| `/admin/limits` | `PUT` | `200` | `400`, `404` |
| `/admin/limits` | `GET` | `200` | `400` |
| `/admin/limits/{id}` | `DELETE` | `204` | `404` |
| `/admin/audit` | `GET` | `200` | `400` |

Example rate-limited response body:

```json
{
  "errorCode": "RATE_LIMITED",
  "ruleId": "12",
  "retryAfterSeconds": 37,
  "message": "Too many requests. Retry after 37 seconds."
}
```

Full examples: `docs/API.md`.

## 6. Persistence Model

Storage: file-based H2 database.

Entities:

- `rate_limit_rules` (rule configuration)
- `rate_limit_states` (fixed-window counters/window start)
- `audit_events` (blocked decision audit trail)

Runtime defaults:

- `spring.datasource.url=jdbc:h2:file:./data/rate-limit-gateway`
- `spring.jpa.hibernate.ddl-auto=update`

Managed E2E restart persistence uses script-local absolute DB path in `scripts/e2e.sh`, with `WRITE_DELAY=0` for stable restart behavior.

## 7. Build and Verification

Recommended reviewer commands:

### Linux/macOS

```bash
./mvnw test
./mvnw package
bash scripts/e2e.sh
MANAGE_APP=1 bash scripts/e2e.sh
bash scripts/e2e_persistence.sh
```

### Windows (PowerShell + Git Bash)

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
& 'C:\Program Files\Git\bin\bash.exe' scripts/e2e.sh
$env:MANAGE_APP='1'; & 'C:\Program Files\Git\bin\bash.exe' scripts/e2e.sh
& 'C:\Program Files\Git\bin\bash.exe' scripts/e2e_persistence.sh
```

Expected:

- Maven commands finish with `BUILD SUCCESS`
- Scripts print success and exit `0`

## 8. Script Reference

- `scripts/e2e.sh`
  - Main assignment verification.
  - Covers header validation, admin CRUD, positive/negative rule checks, wildcard semantics, isolation, concurrency, 429 schema/header, audit, and managed restart persistence.
- `scripts/e2e_persistence.sh`
  - Focused two-phase restart persistence check (rule + counter).
- `e2e.sh`
  - Convenience wrapper for `scripts/e2e.sh`.

## 9. One-Command Reviewer Check

### Linux/macOS

```bash
./mvnw test && ./mvnw package && bash scripts/e2e.sh && MANAGE_APP=1 bash scripts/e2e.sh && bash scripts/e2e_persistence.sh
```

### Windows (PowerShell + Git Bash)

```powershell
.\mvnw.cmd test; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
.\mvnw.cmd package; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& 'C:\Program Files\Git\bin\bash.exe' scripts/e2e.sh; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$env:MANAGE_APP='1'; & 'C:\Program Files\Git\bin\bash.exe' scripts/e2e.sh; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& 'C:\Program Files\Git\bin\bash.exe' scripts/e2e_persistence.sh; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Expected success lines:

- `All assignment E2E checks passed.`
- `PASS: Persistence checks across restart`
- `Persistence checks passed.`

## 10. Scoring Evidence

| Requirement | Proof in code | Proof in tests/scripts |
|---|---|---|
| 2 protected endpoints | `src/main/java/com/radix/rate_limit_gateway/api/DataController.java` | `scripts/e2e.sh` GET/POST body checks |
| required headers + `400` JSON | `src/main/java/com/radix/rate_limit_gateway/ratelimit/HeaderValidationInterceptor.java` | `scripts/e2e.sh` header validation section |
| middleware enforcement | `src/main/java/com/radix/rate_limit_gateway/config/WebConfig.java` | blocked/allowed behavior in all E2E and integration tests |
| `429` includes code/rule/retry | `src/main/java/com/radix/rate_limit_gateway/ratelimit/HeaderValidationInterceptor.java` | `scripts/e2e.sh` 429 JSON/header checks |
| admin CRUD | `src/main/java/com/radix/rate_limit_gateway/admin/AdminLimitsController.java` | `src/test/java/com/radix/rate_limit_gateway/AdminApiHttpIntegrationTests.java` + `scripts/e2e.sh` |
| persisted rules | `src/main/java/com/radix/rate_limit_gateway/admin/RateLimitRule.java` | restart checks in `scripts/e2e.sh` and `scripts/e2e_persistence.sh` |
| persisted limiter state | `src/main/java/com/radix/rate_limit_gateway/admin/RateLimitState.java` + `src/main/java/com/radix/rate_limit_gateway/ratelimit/RateLimitService.java` | restart checks expecting `429` after restart in both persistence scripts |
| blocked audit persistence | `src/main/java/com/radix/rate_limit_gateway/audit/AuditEvent.java` + `src/main/java/com/radix/rate_limit_gateway/ratelimit/RateLimitService.java` | `src/test/java/com/radix/rate_limit_gateway/RateLimitAuditIntegrationTests.java` + `scripts/e2e.sh` audit checks |
| concurrency correctness | `src/main/java/com/radix/rate_limit_gateway/ratelimit/RateLimitService.java` | `src/test/java/com/radix/rate_limit_gateway/RateLimitWildcardAndConcurrencyTests.java` + `scripts/e2e.sh` |
| optional S3 | intentionally not implemented | documented in this README and `docs/ASSIGNMENT_TRACEABILITY.md` |

## 11. Configuration

Main environment variables used by scripts:

- `BASE_URL`
- `TENANT_PREFIX`
- `MANAGE_APP`
- `APP_JAR`
- `APP_PORT`
- `APP_DB_URL`

App-level settings:

- `app.seed-default-rules.enabled=false` by default
- Set to `true` to seed demo startup rules

## 12. Troubleshooting

### `bash` not found on Windows

Use Git Bash path explicitly:

```powershell
& 'C:\Program Files\Git\bin\bash.exe' scripts/e2e.sh
```

### Port conflict

Run with another port:

```powershell
.\mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--server.port=18081
```

### E2E cannot connect

- Ensure app is up on configured `BASE_URL`.
- For managed mode, run `package` first so `target/...jar` exists.

## 13. Design Decisions

### Rate limiting algorithm

Chosen: **Fixed Window**

Why:

- deterministic behavior
- straightforward persistence model
- easy to verify in integration/E2E tests

Tradeoff:

- allows bursts near window boundaries

### S3 export

Not implemented. Optional in assignment.

### Script validation method

Chosen `curl -sS -o /dev/null -w "%{http_code}"` + explicit comparisons for deterministic pass/fail.

## 14. Known Limitations

- Fixed-window boundary burst behavior.
- Local DB-backed design; not a multi-node distributed limiter.
- Admin endpoints intentionally unauthenticated (assignment scope).
- Optional S3 export not implemented.

## 15. AI Agent & Setup

Agent/tool:

- OpenAI Codex coding agent (GPT-5 based) in terminal workflow

Local setup:

- OS: Windows (PowerShell)
- Java: 17 (Temurin)
- Build tool: Maven Wrapper (`mvnw` / `mvnw.cmd`)
- Bash runtime for scripts: Git Bash

Working style:

- iterate: implement -> run tests -> inspect failures -> patch -> re-run
- use targeted E2E checks for behavior-level validation
- keep assignment traceability doc in sync with implementation

Representative loop:

```bash
./mvnw test
./mvnw package
bash scripts/e2e.sh
MANAGE_APP=1 bash scripts/e2e.sh
bash scripts/e2e_persistence.sh
```

## 16. Additional Docs

- `docs/ARCHITECTURE.md`
- `docs/API.md`
- `docs/OPERATIONS.md`
- `docs/TESTING.md`
- `docs/ASSIGNMENT_TRACEABILITY.md`
