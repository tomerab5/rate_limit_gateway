# Operations Guide

## 1. Runtime Requirements

- Java 17+
- writable project `data/` directory for H2 files

## 2. Start Modes

## 2.1 Development mode

```bash
./mvnw spring-boot:run
```

## 2.2 Jar mode

```bash
./mvnw package
java -jar target/rate-limit-gateway-0.0.1-SNAPSHOT.jar
```

## 3. Configuration

Default runtime properties (`src/main/resources/application.properties`):

- `spring.datasource.url=jdbc:h2:file:./data/rate-limit-gateway`
- `spring.jpa.hibernate.ddl-auto=update`

Default port (`src/main/resources/application.yml`):

- `server.port=8081`

Override examples:

```bash
java -jar target/rate-limit-gateway-0.0.1-SNAPSHOT.jar --server.port=18081
java -jar target/rate-limit-gateway-0.0.1-SNAPSHOT.jar --spring.datasource.url=jdbc:h2:file:./data/custom-db
```

## 4. Health and Functional Checks

Basic request:

```bash
curl -i http://localhost:8081/api/data -H "X-Tenant-Id: t1" -H "X-Api-Key: k1"
```

Admin listing:

```bash
curl "http://localhost:8081/admin/limits?tenantId=t1"
```

## 5. Data Persistence and Reset

Files are under `data/`.

To reset local state, stop app and remove relevant H2 DB files (for example `data/rate-limit-gateway.mv.db`).

## 6. Logs and Diagnostics

Spring Boot logs to console by default.

For managed script run, logs are written to:

- `/tmp/rate-limit-gateway-e2e.log` (from `scripts/e2e.sh` managed mode)
- `/tmp/rate-limit-gateway-e2e-persistence.log` (from `scripts/e2e_persistence.sh`)

## 7. Known Operational Constraints

- Limiter state is local to this service instance and DB.
- Admin APIs are not authenticated (out of assignment scope).
- S3 export is not implemented.