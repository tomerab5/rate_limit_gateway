# Assignment Traceability

This file maps assignment requirements to implementation artifacts.

## 1. Protected API and Headers

Requirement:

- at least two protected endpoints
- required `X-Tenant-Id` and `X-Api-Key`
- missing/blank => `400` JSON

Implementation:

- `src/main/java/com/radix/rate_limit_gateway/api/DataController.java`
- `src/main/java/com/radix/rate_limit_gateway/ratelimit/HeaderValidationInterceptor.java`
- `src/main/java/com/radix/rate_limit_gateway/config/WebConfig.java`

## 2. Rate Limiting Middleware and 429 Body

Requirement:

- pre-controller enforcement
- `429` includes error code, rule id, retry-after seconds

Implementation:

- `HeaderValidationInterceptor` handles `429` response body and `Retry-After` header
- `RateLimitService` computes decision and retry seconds

## 3. Admin API for Limits

Requirement:

- `PUT /admin/limits`
- `GET /admin/limits?tenantId=...`
- `DELETE /admin/limits/{id}`

Implementation:

- `src/main/java/com/radix/rate_limit_gateway/admin/AdminLimitsController.java`

## 4. Persistence of Rules, State, and Audit

Requirement:

- persist rules
- persist algorithm state
- persist blocked audit events

Implementation:

- `RateLimitRule` + repository
- `RateLimitState` + repository
- `AuditEvent` + repository
- runtime DB: `jdbc:h2:file:./data/rate-limit-gateway`

## 5. Auditing on Blocked Requests

Requirement:

- write blocked event with required fields
- transactional with block decision

Implementation:

- `RateLimitService.writeBlockedAudit(...)`
- called inside transaction callback in `RateLimitService.check(...)`
- fields include timestamp, tenant, api key, method/path, rule id, ip, decision, retry/window metadata

## 6. Optional S3 Export

Requirement:

- optional

Implementation:

- not implemented by design
- local audit persistence implemented

## 7. Testing and E2E Script

Requirement:

- automated tests
- `scripts/e2e.sh` with positive and negative tests for each rule

Implementation:

- JUnit test suite under `src/test/java/...`
- `scripts/e2e.sh` comprehensive scenario checks
- `scripts/e2e_persistence.sh` restart persistence checks

## 8. README Requirements

Requirement:

- local run instructions
- AI Agent & Setup section
- documented decisions for open points

Implementation:

- `README.md` includes all required sections