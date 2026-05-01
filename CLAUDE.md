# PayFlow Engine — AI Assistant Guide

## Project Overview

PayFlow Engine is a **polyglot microservices payment platform** simulating a financial payments system. It demonstrates high-concurrency patterns: idempotency, distributed locking, event-driven processing, Saga compensation, and double-entry accounting.

---

## Architecture

Seven services communicate via REST (sync) and Kafka (async):

| Service | Language/Framework | Port | Role |
|---|---|---|---|
| `gateway-service` | Java 21 + Spring Cloud Gateway | 8080 | API gateway — routing, JWT auth, rate limiting, tracing |
| `payment-service` | Java 21 + Spring Boot 3 | 8081 | Orchestrator — accepts payments, coordinates flow |
| `account-service` | Java 21 + Spring Boot 3 | 8082 | Balance management — freeze/transfer/unfreeze |
| `ledger-service` | Java 21 + Spring Boot 3 | 8083 | Double-entry accounting records |
| `risk-service` | Python 3.12 + FastAPI | 8084 | Policy enforcement — blacklist, amount limits |
| `notification-service` | Go 1.22 | 8085 | Event-driven webhook/notification delivery |
| `frontend` | React 18 + TypeScript + Vite | 3000 | SPA — payments UI |

**Infrastructure:** PostgreSQL 16, Redis 7, Kafka (Confluent 7.6 — KRaft mode, no ZooKeeper), Nginx

**Rate limiting:** Gateway service (`gateway-service`) — Redis token bucket via Spring Cloud Gateway `RequestRateLimiter` filter, 10r/s per IP, burst capacity 20, key resolved from `X-Forwarded-For` / `X-Real-IP` / remote address.

**Authentication:** Gateway service validates JWT tokens on all routes except whitelisted paths (`/actuator/**`, health endpoints). Valid tokens produce `X-Auth-User` and `X-Auth-Roles` headers forwarded to downstream services. **Default is `JWT_ENABLED=false`** — both the gateway's standalone default (`application.yml`) and `docker-compose.yml` ship with JWT disabled because the frontend has no login flow. Set `JWT_ENABLED=true` to require Bearer tokens.

**Distributed tracing:** End-to-end `X-Trace-Id` propagation across every service:
- Gateway: `TraceIdFilter` generates UUID if missing.
- Java services (payment / account / ledger): `TraceFilter` (servlet, `HIGHEST_PRECEDENCE`) reads the header, writes to SLF4J MDC under key `traceId`, echoes it on the response.
- Outbound REST: `FeignTraceInterceptor` (payment-service) copies the MDC trace ID onto every Feign call.
- Kafka: `PaymentEventProducer` writes `X-Trace-Id` as a message header; `PaymentEventConsumer` (Java) and `notification-service` (Go, `extractTraceId`) read it back into MDC / logs.
- Python (`risk-service`): FastAPI `trace_middleware` propagates the header and attaches it to log records.
- All Java services log with pattern `[traceId=%X{traceId:--}]` via `logback-spring.xml`.

**Monitoring:** Every Java service exposes Spring Boot Actuator endpoints `health`, `info`, `metrics`, `env` (with `health.show-details=always`).

**Audit trail:** payment-service writes an async `audit_log` row (table: `audit_log`, indexed by `trace_id`, `(resource_type, resource_id)`, `action`, `created_at`) at every key business point — payment creation, freeze, ledger create, transfer, completion, failure, compensation start, ledger reverse, unfreeze, reconcile-required. Queryable via `/api/v1/audit/...`.

### Payment Flow (Sync → Async)

**Synchronous path (returns 201 Created):**
```
Client → Nginx → Gateway Service (JWT auth, rate limit, trace ID)
       → Payment Service
       → IdempotencyStep  (Redis SETNX, 24h TTL; short-circuit on duplicate)
       → OrderPersistStep (create PaymentOrder, status=CREATED)
       → RiskCheckStep    (sync Feign → Risk Service; PASS→PENDING, FAIL→REJECTED+exception)
       → EventPublishStep (publish payment.created to Kafka)
       → Return 201 Created
```

**Asynchronous path (triggered by payment.created Kafka event):**
```
Kafka consumer (payment.created)
       → If order.status != PENDING → skip (Kafka redelivery dedupe)
       → Acquire Redisson lock on fromAccount (tryLock 5s, hold max 30s)
       → status → PROCESSING
       → Account Service: freeze amount        (audit: FREEZE_AMOUNT)
       → Ledger Service:  create double-entry  (audit: CREATE_LEDGER_ENTRY)
       → Account Service: transfer             (audit: TRANSFER)  ← sets `transferred = true`
       → status → COMPLETED, publish payment.completed
       → Notification Service: webhook callback

On failure (split by whether transfer already succeeded):
  PRE-transfer failure → Saga compensation (reverse order, each step independent try-catch):
       → reverseEntry   (if ledger was created)
       → unfreezeAmount (if account was frozen)
       → status → FAILED, publish payment.failed
  POST-transfer failure (DB save / event publish) → DO NOT compensate:
       → audit: RECONCILE_REQUIRED (manual intervention)
       → tryFinalizeCompleted: best-effort re-save status=COMPLETED + republish event
```

---

## Directory Structure

```
payflow-engine/
├── gateway-service/          # Java Spring Cloud Gateway — API gateway
│   └── src/main/java/com/payflow/gateway/
│       ├── config/           # JwtProperties, RateLimiterConfig (ipKeyResolver)
│       └── filter/           # AuthenticationFilter (JWT), TraceIdFilter, RequestLoggingFilter
│   └── src/main/resources/
│       └── application.yml   # Route definitions, Redis rate limiter, JWT config
├── payment-service/          # Java Spring Boot — payment orchestration
│   └── src/main/java/com/payflow/payment/
│       ├── controller/       # REST endpoints + request record DTOs
│       ├── service/          # PaymentService, RefundService
│       │   └── pipeline/     # PaymentCreationPipeline + steps
│       │       └── step/     # IdempotencyStep, OrderPersistStep, RiskCheckStep, EventPublishStep
│       ├── dto/              # Response DTOs (CreatePaymentResponse, PaymentResponse, etc.)
│       ├── domain/           # JPA entities + state machine (PaymentStatus, RefundStatus)
│       ├── repository/       # Spring Data JPA repos
│       ├── client/           # Feign clients + FallbackFactory + FallbackPolicy (4xx propagation)
│       ├── audit/            # AuditController, AuditLog, AuditLogRepository, AuditService (@Async)
│       ├── config/           # IdempotencyFilter, KafkaConfig, WebConfig, TraceFilter, FeignTraceInterceptor
│       ├── exception/        # PaymentException, GlobalExceptionHandler (incl. 409 OptimisticLockException)
│       └── mq/               # PaymentEventProducer, PaymentEventConsumer (both propagate X-Trace-Id)
│   └── src/main/resources/
│       ├── application.yml   # Resilience4j circuit breaker + Actuator config
│       └── logback-spring.xml # Log pattern with [traceId=...]
├── account-service/          # Java Spring Boot — balance management
│   └── src/main/java/com/payflow/account/
│       ├── controller/       # AccountController
│       ├── service/          # AccountService (TransactionTemplate + retry on optimistic lock)
│       ├── domain/           # Account.java (@Version optimistic locking)
│       ├── repository/       # AccountRepository
│       ├── config/           # TraceFilter
│       └── exception/        # GlobalExceptionHandler (incl. 409 OptimisticLockException)
│   └── src/main/resources/
│       ├── application.yml   # Actuator config
│       └── logback-spring.xml
├── ledger-service/           # Java Spring Boot — double-entry ledger
│   └── src/main/java/com/payflow/ledger/
│       ├── controller/       # LedgerController
│       ├── service/          # LedgerService (createEntry, reverseEntry, verifyBalance)
│       ├── domain/           # LedgerEntry
│       ├── config/           # TraceFilter
│       └── repository/       # LedgerEntryRepository
│   └── src/main/resources/
│       ├── application.yml   # Actuator config
│       └── logback-spring.xml
├── risk-service/             # Python FastAPI — rule engine
│   ├── main.py               # FastAPI app + trace_middleware (X-Trace-Id)
│   └── rules/
│       ├── rule_engine.py    # Chain of responsibility orchestrator
│       ├── blacklist.py      # Blacklist rule
│       └── amount_limit.py   # Amount limit rule
├── notification-service/     # Go — async event handling
│   ├── main.go               # HTTP server + graceful shutdown
│   ├── consumer/             # kafka_consumer.go (StartKafkaConsumer + extractTraceId)
│   └── handler/              # webhook.go (WebhookHandler)
├── frontend/                 # React + TypeScript SPA + Nginx
│   ├── nginx.conf            # Static hosting + reverse proxy to gateway-service
│   └── src/
│       ├── App.tsx           # Route definitions (incl. /audit)
│       ├── main.tsx          # Entry point
│       ├── pages/            # PaymentListPage, PaymentCreatePage, PaymentDetailPage, AccountPage, AuditPage
│       ├── layouts/          # MainLayout (navigation sidebar)
│       ├── api/              # client.ts (Axios + JWT interceptor), payment.ts, account.ts, audit.ts
│       ├── types/            # index.ts (TypeScript interfaces, incl. AuditLog)
│       ├── utils/            # format.ts (amounts, dates, status colors)
│       └── styles/           # global.css
├── sql/                      # Database init scripts (auto-loaded by Docker, applied in lexical order)
│   ├── 001_create_payment_order.sql
│   ├── 002_create_account.sql
│   ├── 003_create_ledger_entry.sql
│   ├── 004_create_refund_order.sql
│   ├── 005_seed_accounts.sql        # Test accounts (acc001–acc004)
│   ├── 006_add_payment_order_version.sql  # @Version column for optimistic locking
│   └── 006_create_audit_log.sql           # Audit log table
├── .github/workflows/        # CI (ci.yml) and CD (cd.yml) workflows
├── .husky/                   # Git hooks (pre-commit linting)
├── docker-compose.yml        # Full stack local orchestration
└── README.md                 # Architecture deep-dive
```

---

## Development Workflow

### Running the Full Stack
```bash
# Start everything (all services + infra)
docker-compose up -d

# Start infrastructure only (postgres, redis, kafka)
docker-compose up -d postgres redis kafka
```

### Running Individual Services

**Java services (payment, account, ledger):**
```bash
cd payment-service   # or account-service / ledger-service
mvn spring-boot:run
```

**Gateway service (Java — Spring Cloud Gateway):**
```bash
cd gateway-service
mvn spring-boot:run   # Runs on port 8080, requires Redis for rate limiting
```

**Risk service (Python):**
```bash
cd risk-service
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8084
```

**Notification service (Go):**
```bash
cd notification-service
go run main.go
```

**Frontend:**
```bash
cd frontend
npm install
npm run dev   # Dev server on port 3000, proxies /api to gateway on localhost:8080
```

### Building

**Java:**
```bash
mvn clean package           # Build JAR
mvn -B clean verify         # Full build + tests (CI command)
```

**Frontend:**
```bash
npm run build   # TypeScript check + Vite production build
npm run lint    # ESLint check
npm run format  # Prettier format
```

**Go:**
```bash
go build ./...
```

---

## Key Conventions

### Java Services (Spring Boot)

- **Package structure:** `com.payflow.<service>.<layer>` — always follow controller → service → repository layering
- **Naming:** PascalCase classes, camelCase methods, UPPER_SNAKE_CASE enum values
- **Amounts:** Always use `BIGINT` (cents/smallest currency unit) — never floating-point for money
- **Optimistic locking:** Both `Account` and `PaymentOrder` carry JPA `@Version`. **Do not initialize the version field** (leave it `null`) — Spring Data treats a non-null `@Version` as "not new" and routes `save()` to `merge()`, which on a null id triggers a duplicate-insert. The DB column has `DEFAULT 0`.
- **Optimistic-lock retries:** `AccountService` wraps `freeze` / `unfreeze` / `transfer` in `TransactionTemplate.executeWithoutResult` and retries up to 3 times on `ObjectOptimisticLockingFailureException` — each retry runs in a fresh transaction. `GlobalExceptionHandler` (account + payment) maps the exception to **409 Conflict**.
- **Idempotency:** `IdempotencyFilter` / `IdempotencyStep` uses Redis SETNX with 24h TTL — payment endpoints are protected automatically
- **Distributed locks:** Use Redisson (not raw Redis) for account-level operations — `tryLock(5, 30, TimeUnit.SECONDS)`
- **State transitions:** `PaymentStatus` has `canTransitTo()` — always call `order.transitTo(target)` to enforce state machine; never assign status directly
- **Inter-service calls:** Use Feign clients in the `client/` package, not raw HTTP
- **Feign resilience:** Each Feign client has a paired `*FallbackFactory` for Resilience4j circuit breaker fallback — register fallbacks when adding new clients
- **Feign 4xx propagation:** `AccountClientFallbackFactory` and `LedgerClientFallbackFactory` call `FallbackPolicy.rethrowIfClientError(cause)` first — a `FeignException` with status 400-499 means the upstream is healthy and returned a business error, so it must propagate unchanged. Only 5xx, network errors, and circuit-open should fall back to `SERVICE_UNAVAILABLE`. `RiskClientFallbackFactory` is intentionally NOT wrapped — its fail-safe (reject when risk check is unavailable) is correct for any error type.
- **Circuit breaker:** Configured in `payment-service/src/main/resources/application.yml` — sliding window 10, failure threshold 50%, open wait 30s
- **Kafka topics published** via `PaymentEventProducer`: `payment.created` (partitioned by `from_account`), `payment.completed`, `payment.failed`. All carry an `X-Trace-Id` header. Note: `ledger.entry` and `risk.alert` do NOT exist — ledger operations use synchronous REST calls, not Kafka
- **Kafka topics consumed** by notification-service: `payment.completed`, `payment.failed`, `notification.send`
- **Kafka redelivery dedupe:** `processPaymentAsync` short-circuits if `order.status != PENDING` — we observed Kafka retrying offsets after consumer restart, and re-running the flow on a `COMPLETED` order would double-spend.
- **Response DTOs:** Controllers return DTO objects from the `dto/` package — do not return raw entities
- **Audit logging:** Inject `AuditService` and call `auditService.log(action, resourceType, resourceId, detail, result, clientIp)` at every state change in financial flows. The call is `@Async` and reads `traceId` from MDC automatically — never block the main flow on it.
- **Trace propagation:** Inbound HTTP → `TraceFilter` populates MDC. Outbound Feign → `FeignTraceInterceptor` copies MDC → header. Outbound Kafka → `PaymentEventProducer` writes header. New inter-service paths must preserve this chain.

### Payment Creation Pipeline

Payment creation is orchestrated by `PaymentCreationPipeline` which runs steps in order. Any step can set `ctx.shortCircuit()` to skip remaining steps:

```
IdempotencyStep → OrderPersistStep → RiskCheckStep → EventPublishStep
```

Add new pre-acceptance steps by implementing `PaymentCreationStep` and injecting into the pipeline constructor in order.

### Saga Compensation

`processPaymentAsync` (triggered by `payment.created` Kafka event) tracks three boolean flags (`froze`, `ledgerCreated`, `transferred`). The failure handler **branches by `transferred`**:

- **Pre-transfer failure** (`transferred=false`): `compensate()` reverses in reverse order — ledger reversal first (if created), then unfreeze (if frozen). Each compensation step has its own try-catch so one failure does not abort the rest. The order is then transitioned to `FAILED` and `payment.failed` is published.
- **Post-transfer failure** (`transferred=true`, but the subsequent DB save / event publish failed): money has already moved — **never compensate**. Audit `RECONCILE_REQUIRED`, then `tryFinalizeCompleted()` re-reads the order in a fresh fetch and best-effort transitions it to `COMPLETED`. A missed finalization is left to the reconciliation job, never to compensation.

This split exists because earlier code ran `compensate()` from any catch block, so a post-transfer save failure would reverse the ledger and try to unfreeze a now-empty frozen balance, leaving books inconsistent.

### TypeScript / React (Frontend)

- **Component files:** PascalCase `.tsx` (e.g., `PaymentListPage.tsx`)
- **Utility files:** camelCase `.ts` (e.g., `format.ts`)
- **Path alias:** `@/*` maps to `src/*`
- **Idempotency keys:** Generated client-side in `payment.ts` before API calls — do not skip this
- **Auth:** JWT token injected by Axios interceptor in `client.ts`
- **Amounts:** Display using `formatAmount()` from `utils/format.ts` — converts cents to yuan with 2 decimal places
- **UI library:** Ant Design 5 — use its components for tables, forms, modals

### Python (Risk Service)

- **Style:** snake_case functions, PascalCase classes
- **Adding rules:** Subclass the base rule interface and register in `rule_engine.py` — chain of responsibility pattern
- **Amount units:** Risk rules receive amounts in cents (same as all services)
- **Endpoint signature:** `/api/v1/risk/check` accepts query parameters (`from_account`, `to_account`, `amount`), not a JSON body

### Go (Notification Service)

- **Style:** Standard Go conventions — exported functions PascalCase, unexported camelCase
- **Kafka consumption:** Handled in `consumer/kafka_consumer.go` — started via `consumer.StartKafkaConsumer(ctx)` with cancellable context for graceful shutdown
- **Webhook only:** Only `handler/webhook.go` exists — no SMS/email handlers
- **HTTP endpoints:** `GET /health` (health check), `POST /api/v1/notify/webhook` (manual webhook retry trigger)

### Gateway Service (Spring Cloud Gateway)

- **Package structure:** `com.payflow.gateway` — config and filter packages
- **Routing:** Defined in `application.yml` under `spring.cloud.gateway.routes` — each backend service has a route entry keyed by path prefix
- **Rate limiting:** Uses Spring Cloud Gateway `RequestRateLimiter` filter with Redis token bucket — configured as a default filter applying to all routes. `RateLimiterConfig` provides an `ipKeyResolver` bean that resolves client IP from `X-Forwarded-For` → `X-Real-IP` → remote address
- **Authentication:** `AuthenticationFilter` (order -2) validates JWT `Bearer` tokens using JJWT. Whitelisted paths skip auth. On valid token, `X-Auth-User` and `X-Auth-Roles` headers are injected for downstream services. Controlled by `gateway.jwt.enabled` property
- **Tracing:** `TraceIdFilter` (order -3) generates/propagates `X-Trace-Id` header on every request
- **Logging:** `RequestLoggingFilter` (order -1) logs method, path, status, duration, client IP, and trace ID for every request
- **Adding new routes:** Add a new entry under `spring.cloud.gateway.routes` in `gateway-service/src/main/resources/application.yml` with id, uri, and path predicate
- **Adding auth whitelist paths:** Add patterns to the `WHITELIST` list in `AuthenticationFilter.java`

### Nginx (Frontend)

- **Config:** `frontend/nginx.conf` — serves static files and proxies `/api/` requests to `gateway-service:8080`
- **No rate limiting:** Rate limiting is handled by the gateway service, not Nginx

### Database

- **Amounts:** Stored as `BIGINT` — never `DECIMAL`/`FLOAT`
- **IDs:** Use UUIDs for business IDs (payment_id, account_id, etc.), auto-increment `id` as PK
- **Migrations:** Add new SQL files numerically prefixed in `sql/` — they run in order at container startup
- **Seed data:** `005_seed_accounts.sql` seeds test accounts `acc001`–`acc004` for local development
- **Credentials (local):** user=`payflow`, password=`payflow_secret`, database=`payflow`

---

## Critical Design Patterns

### 1. Idempotency
Every payment request must include `Idempotency-Key` header. The `IdempotencyStep` in the payment creation pipeline checks Redis with SETNX before processing. Same key within 24h returns cached response.

### 2. Freeze-Then-Transfer Model (Async)
Account operations happen asynchronously after `payment.created` is consumed: `freeze → ledger → transfer`. On failure, Saga compensation runs in reverse. Never do a direct transfer without freeze. Never skip compensation on failure.

### 3. Double-Entry Ledger
Every financial movement creates a pair of ledger entries (debit account + credit account). Amounts must balance. `LedgerService.verifyBalance()` checks this globally. `reverseEntry` is used for Saga compensation.

### 4. Payment State Machine
`PaymentStatus` transitions are strictly enforced via `canTransitTo()`:
```
CREATED → PENDING (risk pass) | REJECTED (risk fail)
PENDING → PROCESSING | REJECTED
PROCESSING → COMPLETED | FAILED
COMPLETED → REFUNDED
FAILED / REJECTED / REFUNDED → (terminal, no transitions)
```
Always use `order.transitTo(target)` — never assign status directly.

### 5. Event-Driven Async Processing
Payment acceptance (201) is decoupled from fulfillment. The pipeline publishes `payment.created` and returns immediately — downstream processing (freeze, ledger, transfer, notifications) happens asynchronously via Kafka.

### 6. Circuit Breaker (Resilience4j)
All Feign clients in payment-service have circuit breakers configured in `application.yml`. Each client also has a `*FallbackFactory` that throws `PaymentException("SERVICE_UNAVAILABLE", ...)` when the circuit is open or the upstream is unreachable. **4xx responses (real business errors) propagate unchanged** via `FallbackPolicy.rethrowIfClientError` — they are not "service unavailable" events.

### 7. Distributed Tracing & Audit
A single `X-Trace-Id` flows through every hop: gateway → Java filter (MDC) → Feign interceptor → Kafka header → Go consumer / Python middleware. All Java logs render `[traceId=...]` from MDC. The `audit_log` table records every financial state change with that trace ID, enabling end-to-end forensic replay via `GET /api/v1/audit/trace/{traceId}` or `GET /api/v1/audit/resource?resource_type=PAYMENT&resource_id=...`.

---

## API Reference

### Gateway Service (port 8080)
All client requests should go through the gateway. It routes by path prefix:
- `/api/v1/payments/**` → payment-service (8081)
- `/api/v1/accounts/**` → account-service (8082)
- `/api/v1/ledger/**` → ledger-service (8083)
- `/api/v1/risk/**` → risk-service (8084)
- `/api/v1/notify/**` → notification-service (8085)

| Method | Path | Description |
|---|---|---|
| GET | `/actuator/health` | Gateway health check |

### Payment Service (port 8081)
| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/payments` | Create payment — requires `Idempotency-Key` header, returns 201 Created |
| GET | `/api/v1/payments` | List payments (paginated — `?page=1&size=20`, page is 1-based) |
| GET | `/api/v1/payments/{paymentId}` | Get payment by ID |
| POST | `/api/v1/payments/{paymentId}/refund` | Initiate refund — requires `Idempotency-Key` header, returns 201 |
| GET | `/api/v1/payments/{paymentId}/refunds` | List refunds for a payment |
| GET | `/api/v1/audit/logs` | Page audit log entries — `?page=1&size=20` |
| GET | `/api/v1/audit/trace/{traceId}` | Get all audit entries for a trace ID (chronological) |
| GET | `/api/v1/audit/resource` | Get audit entries for a resource — query params: `resource_type`, `resource_id` |

All Java services additionally expose `/actuator/health`, `/actuator/info`, `/actuator/metrics`, `/actuator/env`.

### Account Service (port 8082)
| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/accounts` | List all accounts |
| GET | `/api/v1/accounts/{accountId}/balance` | Get account balance |
| POST | `/api/v1/accounts/freeze` | Freeze amount — query params: `account_id`, `amount` |
| POST | `/api/v1/accounts/unfreeze` | Release frozen amount — query params: `account_id`, `amount` |
| POST | `/api/v1/accounts/transfer` | Execute transfer — query params: `from_account`, `to_account`, `amount` |

### Ledger Service (port 8083)
| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/ledger/entries` | Create double-entry record — query params: `reference_id`, `debit_account`, `credit_account`, `amount` |
| POST | `/api/v1/ledger/entries/reverse` | Reverse a ledger entry (Saga compensation) — same query params |
| GET | `/api/v1/ledger/entries` | Get entries by payment — query param: `payment_id` |
| GET | `/api/v1/ledger/verify` | Verify global debit/credit balance |

### Risk Service (port 8084)
| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/risk/check` | Evaluate risk — query params: `from_account`, `to_account`, `amount`; returns boolean |
| GET | `/health` | Health check |

### Notification Service (port 8085)
| Method | Path | Description |
|---|---|---|
| GET | `/health` | Health check |
| POST | `/api/v1/notify/webhook` | Manual webhook retry trigger |

---

## CI/CD

- **CI** (`.github/workflows/ci.yml`): Runs on push to `main`/`develop` and PRs to `main`. Parallel jobs per service:
  - Frontend: `npm ci` → `npm run lint` → `npm run build`
  - Java services (payment, account, ledger, gateway): `mvn -B clean verify`
  - Risk service: `pip install -r requirements.txt` → `python -m py_compile main.py`
  - Notification service: `go mod tidy` → `go build ./...`
- **CD** (`.github/workflows/cd.yml`): Matrix build for all 7 services. On push to `main` → build & push Docker images to `ghcr.io` + deploy to staging. On version tags (`v*`) → deploy to production.
- **Pre-commit hook**: Runs `lint-staged` on frontend files (ESLint + Prettier). Defined in `.husky/pre-commit` and `.lintstagedrc.json`.

---

## Common Tasks

### Adding a new payment rule (Risk Service)
1. Create a new file in `risk-service/rules/`
2. Implement the rule class with an `evaluate(from_account, to_account, amount)` method
3. Register it in `rule_engine.py`'s chain

### Adding a new payment creation step
1. Implement `PaymentCreationStep` in `payment-service/service/pipeline/step/`
2. Inject into `PaymentCreationPipeline` constructor in the desired order
3. Use `ctx.shortCircuit()` to stop pipeline execution if needed

### Adding a new Java endpoint
1. Add route in `controller/` — validate input, return appropriate HTTP status
2. Add business logic in `service/` — keep controllers thin
3. Add repository method if DB access needed
4. Add Feign client method + FallbackFactory if inter-service call needed

### Adding a new database table
1. Create `sql/00N_create_<table>.sql` with the next sequential number
2. Follow the existing pattern: UUID business ID, BIGINT for amounts, timestamps

### Modifying payment status transitions
Edit `PaymentStatus.java` — the `canTransitTo()` method defines valid transitions. The state machine is central to data integrity. Never add a direct assignment without updating this method.

### Adding a new audit event
1. Pick a stable `action` constant (e.g. `REFUND_INITIATED`) and a `resourceType` (`PAYMENT` / `REFUND` / `ACCOUNT`).
2. Inject `AuditService` and call `auditService.log(action, resourceType, resourceId, detail, result, clientIp)` after the state change has been persisted (success path) **and** in the catch block (failure path with `result="FAILED"`).
3. Do not block on it — the call is `@Async` and reads `traceId` from MDC.

### Adding a new traced inter-service call
1. **Inbound:** existing `TraceFilter` already populates MDC — nothing to do for HTTP receivers.
2. **Outbound Feign:** existing `FeignTraceInterceptor` covers all Feign clients automatically.
3. **Outbound Kafka:** add `X-Trace-Id` to `ProducerRecord` headers from MDC, mirroring `PaymentEventProducer`.
4. **Outbound non-Feign HTTP:** read `MDC.get("traceId")` and add the `X-Trace-Id` header manually.
