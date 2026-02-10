# Testing Guide

## 1. Test Layers

## 1.1 Unit/Integration via JUnit

Test classes:

- `AdminApiHttpIntegrationTests`
- `RateLimitAuditIntegrationTests`
- `RateLimitWildcardAndConcurrencyTests`
- `RateLimitGatewayApplicationTests`

Coverage highlights:

- Admin CRUD and runtime enforcement over real HTTP
- Audit persistence on blocked decisions
- Wildcard matching and specificity behavior
- Concurrency correctness under parallel access

## 1.2 End-to-End Shell Scripts

- `scripts/e2e.sh`: full assignment scenario checks
- `scripts/e2e_persistence.sh`: explicit restart persistence validation

## 2. How to Run

Run all JUnit tests:

```bash
./mvnw test
```

Build package:

```bash
./mvnw package
```

Run E2E against running app:

```bash
bash scripts/e2e.sh
```

Run managed app restart path:

```bash
MANAGE_APP=1 bash scripts/e2e.sh
```

Run dedicated persistence scenario:

```bash
bash scripts/e2e_persistence.sh
```

## 3. E2E Assertions

Script validates:

- header validation errors (`400`)
- GET and POST success responses (`200`)
- admin CRUD behavior
- positive and negative cases for multiple rules
- tuple isolation across tenant/key combinations
- wildcard and exact-rule precedence
- concurrent request cap enforcement
- blocked response schema and `Retry-After`
- audit event existence and fields
- persistence of rules and counters after restart (managed mode)

## 4. Environment Variables for Scripts

Common variables:

- `BASE_URL`
- `TENANT_PREFIX`
- `MANAGE_APP`
- `APP_JAR`
- `APP_PORT`
- `APP_DB_URL`

## 5. Troubleshooting Test Failures

- If scripts cannot connect, verify app and `BASE_URL`.
- If managed mode fails, ensure jar exists from `./mvnw package`.
- If concurrency assertion fails intermittently, rerun and inspect logs for environment resource limits.