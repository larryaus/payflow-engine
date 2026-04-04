# PayFlow Engine — AI Assistant Guide

## Project Overview

PayFlow Engine is a **polyglot microservices payment platform** simulating a financial payments system. It demonstrates high-concurrency patterns: idempotency, distributed locking, event-driven processing, Saga compensation, and double-entry accounting.

---

## Architecture

Six services communicate via REST (sync) and Kafka (async):

| Service | Language/Framework | Port | Role |
|---|---|---|---|
| `payment-service` | Java 21 + Spring Boot 3 | 8081 | Orchestrator — accepts payments, coordinates flow |
| `account-service` | Java 21 + Spring Boot 3 | 8082 | Balance management — freeze/transfer/unfreeze |
| `ledger-service` | Java 21 + Spring Boot 3 | 8083 | Double-entry accounting records |
| `risk-service` | Python 3.12 + FastAPI | 8084 | Policy enforcement — blacklist, amount limits |
| `notification-service` | Go 1.22 | 8085 | Event-driven webhook/notification delivery |
| `frontend` | React 18 + TypeScript + Vite | 3000 | SPA — payments UI |

**Infrastructure:** PostgreSQL 16, Redis 7, Kafka (Confluent 7.6 — KRaft mode, no ZooKeeper), Nginx

**Rate limiting:** Nginx (`frontend/nginx.conf`) — `limit_req_zone` 10r/s per IP, burst=20, applies to all `/api/*` routes, returns 429 on exceed.

### Payment Flow (Sync → Async)

**Synchronous path (returns 201 Created):**
```
Client → Payment Service
       → IdempotencyStep  (Redis SETNX, 24h TTL; short-circuit on duplicate)
       → OrderPersistStep (create PaymentOrder, status=CREATED)
       → RiskCheckStep    (sync Feign → Risk Service; PASS→PENDING, FAIL→REJECTED+exception)
       → EventPublishStep (publish payment.created to Kafka)
       → Return 201 Created
```

**Asynchronous path (triggered by payment.created Kafka event):**
```
Kafka consumer (payment.created)
       → Acquire Redisson lock on fromAccount (tryLock 5s, hold max 30s)
       → status → PROCESSING
       → Account Service: freeze amount
       → Ledger Service:  create double-entry record
       → Account Service: transfer (debit frozen → credit to_account)
       → status → COMPLETED, publish payment.completed
       → Notification Service: webhook callback

On failure → Saga compensation (reverse order):
       → reverseEntry (if ledger was created)
       → unfreezeAmount (if account was frozen)
       → status → FAILED, publish payment.failed
```

---

## Directory Structure

```
payflow-engine/
├── payment-service/          # Java Spring Boot — payment orchestration
│   └── src/main/java/com/payflow/payment/
│       ├── controller/       # REST endpoints + request record DTOs
│       ├── service/          # PaymentService, RefundService
│       │   └── pipeline/     # PaymentCreationPipeline + steps
│       │       └── step/     # IdempotencyStep, OrderPersistStep, RiskCheckStep, EventPublishStep
│       ├── dto/              # Response DTOs (CreatePaymentResponse, PaymentResponse, etc.)
│       ├── domain/           # JPA entities + state machine (PaymentStatus, RefundStatus)
│       ├── repository/       # Spring Data JPA repos
│       ├── client/           # Feign clients + FallbackFactory (AccountClient, RiskClient, LedgerClient)
│       ├── config/           # IdempotencyFilter, KafkaConfig, WebConfig
│       ├── exception/        # PaymentException, GlobalExceptionHandler
│       └── mq/               # PaymentEventProducer, PaymentEventConsumer
│   └── src/main/resources/
│       └── application.yml   # Resilience4j circuit breaker config
├── account-service/          # Java Spring Boot — balance management
│   └── src/main/java/com/payflow/account/
│       ├── controller/       # AccountController
│       ├── service/          # AccountService (balance ops, Redisson distributed lock)
│       ├── domain/           # Account.java (@Version optimistic locking)
│       ├── repository/       # AccountRepository
│       └── exception/        # GlobalExceptionHandler
├── ledger-service/           # Java Spring Boot — double-entry ledger
│   └── src/main/java/com/payflow/ledger/
│       ├── controller/       # LedgerController
│       ├── service/          # LedgerService (createEntry, reverseEntry, verifyBalance)
│       ├── domain/           # LedgerEntry
│       └── repository/       # LedgerEntryRepository
├── risk-service/             # Python FastAPI — rule engine
│   ├── main.py               # FastAPI app entry point
│   └── rules/
│       ├── rule_engine.py    # Chain of responsibility orchestrator
│       ├── blacklist.py      # Blacklist rule
│       └── amount_limit.py   # Amount limit rule
├── notification-service/     # Go — async event handling
│   ├── main.go               # HTTP server + graceful shutdown
│   ├── consumer/             # kafka_consumer.go (StartKafkaConsumer)
│   └── handler/              # webhook.go (WebhookHandler)
├── frontend/                 # React + TypeScript SPA + Nginx
│   ├── nginx.conf            # Static hosting + reverse proxy + rate limiting
│   └── src/
│       ├── App.tsx           # Route definitions
│       ├── main.tsx          # Entry point
│       ├── pages/            # PaymentListPage, PaymentCreatePage, PaymentDetailPage, AccountPage
│       ├── layouts/          # MainLayout (navigation sidebar)
│       ├── api/              # client.ts (Axios + JWT interceptor), payment.ts, account.ts
│       ├── types/            # index.ts (TypeScript interfaces)
│       ├── utils/            # format.ts (amounts, dates, status colors)
│       └── styles/           # global.css
├── sql/                      # Database init scripts (auto-loaded by Docker)
│   ├── 001_create_payment_order.sql
│   ├── 002_create_account.sql
│   ├── 003_create_ledger_entry.sql
│   ├── 004_create_refund_order.sql
│   └── 005_seed_accounts.sql # Test accounts (acc001–acc004)
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
npm run dev   # Dev server on port 3000, proxies /api to localhost:8081
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
- **Entities:** Use JPA `@Version` on `Account` for optimistic locking
- **Idempotency:** `IdempotencyFilter` / `IdempotencyStep` uses Redis SETNX with 24h TTL — payment endpoints are protected automatically
- **Distributed locks:** Use Redisson (not raw Redis) for account-level operations — `tryLock(5, 30, TimeUnit.SECONDS)`
- **State transitions:** `PaymentStatus` has `canTransitTo()` — always call `order.transitTo(target)` to enforce state machine; never assign status directly
- **Inter-service calls:** Use Feign clients in the `client/` package, not raw HTTP
- **Feign resilience:** Each Feign client has a paired `*FallbackFactory` for Resilience4j circuit breaker fallback — register fallbacks when adding new clients
- **Circuit breaker:** Configured in `payment-service/src/main/resources/application.yml` — sliding window 10, failure threshold 50%, open wait 30s
- **Kafka topics published** via `PaymentEventProducer`: `payment.created` (partitioned by `from_account`), `payment.completed`, `payment.failed`. Note: `ledger.entry` and `risk.alert` do NOT exist — ledger operations use synchronous REST calls, not Kafka
- **Kafka topics consumed** by notification-service: `payment.completed`, `payment.failed`, `notification.send`
- **Response DTOs:** Controllers return DTO objects from the `dto/` package — do not return raw entities

### Payment Creation Pipeline

Payment creation is orchestrated by `PaymentCreationPipeline` which runs steps in order. Any step can set `ctx.shortCircuit()` to skip remaining steps:

```
IdempotencyStep → OrderPersistStep → RiskCheckStep → EventPublishStep
```

Add new pre-acceptance steps by implementing `PaymentCreationStep` and injecting into the pipeline constructor in order.

### Saga Compensation

`processPaymentAsync` (triggered by `payment.created` Kafka event) tracks boolean flags (`froze`, `ledgerCreated`) to know which operations succeeded. On failure, `compensate()` reverses in reverse order — ledger reversal first, then unfreeze. Each compensation step has independent try-catch so one failure does not abort the rest. Manual intervention is logged if compensation itself fails.

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

### Nginx (Frontend)

- **Config:** `frontend/nginx.conf` — handles static files and reverse proxy
- **Rate limiting:** `limit_req_zone` on `/api/v1/payments` and `/api/v1/accounts` — 10r/s per IP, burst=20, returns 429

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
All Feign clients in payment-service have circuit breakers configured in `application.yml`. Each client also has a `*FallbackFactory` that throws `PaymentException("SERVICE_UNAVAILABLE", ...)` when the circuit is open. This prevents cascade failures.

---

## API Reference

### Payment Service (port 8081)
| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/payments` | Create payment — requires `Idempotency-Key` header, returns 201 Created |
| GET | `/api/v1/payments` | List payments (paginated — `?page=1&size=20`, page is 1-based) |
| GET | `/api/v1/payments/{paymentId}` | Get payment by ID |
| POST | `/api/v1/payments/{paymentId}/refund` | Initiate refund — requires `Idempotency-Key` header, returns 201 |
| GET | `/api/v1/payments/{paymentId}/refunds` | List refunds for a payment |

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
  - Java services: `mvn -B clean verify`
  - Risk service: `pip install -r requirements.txt` → `python -m py_compile main.py`
  - Notification service: `go mod tidy` → `go build ./...`
- **CD** (`.github/workflows/cd.yml`): Matrix build for all 6 services. On push to `main` → build & push Docker images to `ghcr.io` + deploy to staging. On version tags (`v*`) → deploy to production.
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
