# API Reference

Base URL default: `http://localhost:8081`

## 1. Protected API

## 1.1 GET `/api/data`

Headers (required):

- `X-Tenant-Id`
- `X-Api-Key`

Example:

```bash
curl -i http://localhost:8081/api/data \
  -H "X-Tenant-Id: t1" \
  -H "X-Api-Key: k1"
```

Success `200`:

```json
{
  "ok": true,
  "method": "GET",
  "message": "dummy response"
}
```

## 1.2 POST `/api/data`

Headers (required):

- `X-Tenant-Id`
- `X-Api-Key`

Example:

```bash
curl -i -X POST http://localhost:8081/api/data \
  -H "X-Tenant-Id: t1" \
  -H "X-Api-Key: k1"
```

Success `200`:

```json
{
  "ok": true,
  "method": "POST",
  "message": "dummy response"
}
```

## 1.3 Validation error `400`

Missing or blank header returns:

```json
{
  "errorCode": "MISSING_HEADER",
  "message": "X-Api-Key is required"
}
```

## 1.4 Rate limited `429`

Headers:

- `Retry-After: <seconds>`

Body:

```json
{
  "errorCode": "RATE_LIMITED",
  "ruleId": "3",
  "retryAfterSeconds": 42,
  "message": "Too many requests. Retry after 42 seconds."
}
```

## 2. Admin Limits API

## 2.1 PUT `/admin/limits`

Create or update rule.

Request body:

```json
{
  "tenantId": "t1",
  "apiKey": "k1",
  "method": "GET",
  "path": "/api/data",
  "limit": 5,
  "windowSeconds": 60
}
```

Optional update by id:

```json
{
  "id": 7,
  "tenantId": "t1",
  "apiKey": "k1",
  "method": "GET",
  "path": "/api/data",
  "limit": 8,
  "windowSeconds": 60
}
```

Success `200` returns stored rule row.

Validation errors return `400` with reason string.

## 2.2 GET `/admin/limits?tenantId=...`

Example:

```bash
curl "http://localhost:8081/admin/limits?tenantId=t1"
```

Success `200` returns list.

## 2.3 DELETE `/admin/limits/{id}`

Example:

```bash
curl -i -X DELETE "http://localhost:8081/admin/limits/7"
```

Responses:

- `204` when deleted
- `404` when id not found

## 3. Admin Audit API

## 3.1 GET `/admin/audit`

Query params:

- `tenantId` optional filter
- `limit` optional, defaults to 50, max 500

Examples:

```bash
curl "http://localhost:8081/admin/audit"
curl "http://localhost:8081/admin/audit?tenantId=t1&limit=20"
```

Success `200` returns newest-first audit events.

## 4. Wildcard Rule Semantics

Rule values for `apiKey`, `method`, or `path` may be `*`.

Examples:

- tenant-wide rule: `tenant=t2, apiKey=*, method=*, path=*`
- wildcard API key only: `apiKey=*`, with specific method/path

Exact rules have priority over wildcard rules when both match.

## 5. Error Summary

- `400` invalid headers or invalid admin request
- `404` deleting unknown rule id
- `429` blocked by rate limiter