# PayFlow Engine — AI Assistant Guide

## Project Overview

PayFlow Engine is a **polyglot microservices payment platform** simulating a financial payments system. It demonstrates high-concurrency patterns: idempotency, distributed locking, event-driven processing, and double-entry accounting.

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

**Infrastructure:** PostgreSQL 16, Redis 7, Kafka (Confluent 7.6), Nginx

**Rate limiting:** Nginx (`frontend/nginx.conf`) — `limit_req_zone` 10r/s per IP, burst=20, applies to all `/api/*` routes, returns 429 on exceed.

### Payment Flow (Sync → Async)
```
Client → Payment Service → Redis (idempotency check) → Risk Service (sync)
       → Account Service (freeze amount, sync) → Kafka publish
       → Return 202 Accepted

Async: Kafka → Ledger Service (double-entry) + Account Service (transfer)
             → Notification Service (webhook callbacks)
```

---

## Directory Structure

```
payflow-engine/
├── payment-service/          # Java Spring Boot — payment orchestration
│   └── src/main/java/com/payflow/payment/
│       ├── controller/       # REST endpoints
│       ├── service/          # Business logic
│       ├── domain/           # JPA entities, state machine
│       ├── repository/       # Data access
│       ├── client/           # Feign clients (inter-service calls)
│       ├── config/           # Spring beans, configs
│       ├── exception/        # Custom exceptions, global handlers
│       └── mq/               # Kafka producers/consumers
├── account-service/          # Java Spring Boot — balance management
│   └── src/main/java/com/payflow/account/
│       └── (same layer structure)
├── ledger-service/           # Java Spring Boot — double-entry ledger
│   └── src/main/java/com/payflow/ledger/
│       └── (same layer structure)
├── risk-service/             # Python FastAPI — rule engine
│   ├── main.py               # FastAPI app entry point
│   └── rules/
│       ├── rule_engine.py    # Chain of responsibility orchestrator
│       ├── blacklist.py      # Blacklist rule
│       └── amount_limit.py   # Amount limit rule
├── notification-service/     # Go — async event handling
│   ├── main.go               # HTTP server entry
│   ├── consumer/             # Kafka consumer
│   └── handler/              # Webhook delivery
├── frontend/                 # React + TypeScript SPA + Nginx
│   ├── nginx.conf            # Static hosting + reverse proxy + rate limiting
│   └── src/
│       ├── pages/            # PaymentListPage, PaymentCreatePage, PaymentDetailPage, AccountPage
│       ├── layouts/          # MainLayout (navigation)
│       ├── api/              # client.ts (Axios), payment.ts, account.ts
│       ├── types/            # TypeScript interfaces
│       └── utils/            # format.ts (amounts, dates, status colors)
├── sql/                      # Database init scripts (auto-loaded by Docker)
│   ├── 001_create_payment_order.sql
│   ├── 002_create_account.sql
│   ├── 003_create_ledger_entry.sql
│   └── 004_create_refund_order.sql
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
- **Idempotency:** `IdempotencyFilter` uses Redis SETNX with 24h TTL — payment endpoints are protected automatically
- **Distributed locks:** Use Redisson (not raw Redis) for account-level operations
- **State transitions:** `PaymentStatus` has `canTransitTo()` — always check before updating status
- **Inter-service calls:** Use Feign clients in the `client/` package, not raw HTTP
- **Kafka:** Publish via `PaymentEventProducer` — topics: `payment.created`, `payment.completed`, `payment.failed`, `ledger.entry`, `notification.send`, `risk.alert`

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

### Go (Notification Service)

- **Style:** Standard Go conventions — exported functions PascalCase, unexported camelCase
- **Kafka consumption:** Handled in `consumer/kafka_consumer.go`
- **Webhook only:** Only `handler/webhook.go` exists — no SMS/email handlers

### Nginx (Frontend)

- **Config:** `frontend/nginx.conf` — handles static files and reverse proxy
- **Rate limiting:** `limit_req_zone` on `/api/v1/payments` and `/api/v1/accounts` — 10r/s per IP, burst=20, returns 429

### Database

- **Amounts:** Stored as `BIGINT` — never `DECIMAL`/`FLOAT`
- **IDs:** Use UUIDs for business IDs (payment_id, account_id, etc.), auto-increment `id` as PK
- **Migrations:** Add new SQL files numerically prefixed in `sql/` — they run in order at container startup
- **Credentials (local):** user=`payflow`, password=`payflow_secret`, database=`payflow`

---

## Critical Design Patterns

### 1. Idempotency
Every payment request must include `Idempotency-Key` header. The `IdempotencyFilter` in payment-service checks Redis with SETNX before processing. Same key within 24h returns cached response.

### 2. Freeze-Then-Transfer Model
Account operations follow: `freeze (hold) → process → transfer or unfreeze`. This allows safe rollback — never do a direct transfer without freeze.

### 3. Double-Entry Ledger
Every financial movement creates a pair of ledger entries (debit account + credit account). Amounts must balance. Ledger-service enforces this.

### 4. Payment State Machine
`PaymentStatus` transitions are strictly enforced: `CREATED → PENDING → COMPLETED/FAILED`, `COMPLETED → REFUNDING → REFUNDED`. Always use `canTransitTo()` before changing status.

### 5. Event-Driven Async Processing
Payment acceptance (202) is decoupled from fulfillment. The payment-service publishes to Kafka and returns immediately — downstream services (ledger, account transfer, notifications) process asynchronously.

---

## API Reference

### Payment Service (port 8081)
| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/payments` | Create payment — returns 202 Accepted |
| GET | `/api/v1/payments` | List payments (paginated) |
| GET | `/api/v1/payments/{id}` | Get payment by ID |
| POST | `/api/v1/payments/{id}/refund` | Initiate refund |

### Account Service (port 8082)
| Method | Path | Description |
|---|---|---|
| GET | `/accounts/{id}/balance` | Get account balance |
| POST | `/accounts/freeze` | Freeze amount (hold) |
| POST | `/accounts/unfreeze` | Release frozen amount |
| POST | `/accounts/transfer` | Execute transfer |

### Risk Service (port 8084)
| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/risk/check` | Evaluate transaction risk |

---

## CI/CD

- **CI** (`.github/workflows/ci.yml`): Runs on push to `main`/`develop` and PRs to `main`. Parallel jobs per service: lint + build + test.
- **CD** (`.github/workflows/cd.yml`): On push to `main` → build & push Docker images to `ghcr.io` + deploy to staging. On version tags (`v*`) → deploy to production.
- **Pre-commit hook**: Runs `lint-staged` on frontend files (ESLint + Prettier). Defined in `.husky/pre-commit` and `.lintstagedrc.json`.

---

## Common Tasks

### Adding a new payment rule (Risk Service)
1. Create a new file in `risk-service/rules/`
2. Implement the rule class with an `evaluate(request)` method
3. Register it in `rule_engine.py`'s chain

### Adding a new Java endpoint
1. Add route in `controller/` — validate input, return appropriate HTTP status
2. Add business logic in `service/` — keep controllers thin
3. Add repository method if DB access needed
4. Add Feign client method if inter-service call needed

### Adding a new database table
1. Create `sql/00N_create_<table>.sql` with the next sequential number
2. Follow the existing pattern: UUID business ID, BIGINT for amounts, timestamps

### Modifying payment status transitions
Edit `PaymentStatus.java` — the `canTransitTo()` method defines valid transitions. The state machine is central to data integrity.
