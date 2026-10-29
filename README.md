# PayFlow Engine - Bank Payment Flow System

## 1. System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Client (Browser)                               │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │ HTTP
                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Frontend — Nginx (port 3000 / 80)                        │
│   ┌──────────────────────┐  ┌────────────────────────────────────────┐      │
│   │ React SPA (static)   │  │ Reverse proxy → Gateway Service        │      │
│   └──────────────────────┘  └────────────────────────────────────────┘      │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │ /api/*
                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│               Gateway Service — Spring Cloud Gateway (port 8080)            │
│   ┌──────────────────┐ ┌───────────────┐ ┌──────────────┐ ┌───────────────┐ │
│   │ JWT Bearer auth  │ │ Redis limiter │ │ Trace ID     │ │ Request log   │ │
│   │ (off by default) │ │ (10r/s/IP)    │ │ (X-Trace-Id) │ │ (method/path) │ │
│   └──────────────────┘ └───────────────┘ └──────────────┘ └───────────────┘ │
└──────┬──────────┬──────────┬──────────┬──────────┬──────────────────────────┘
       │          │          │          │          │
       ▼          ▼          ▼          ▼          ▼
┌────────────┐┌────────────┐┌────────────┐┌────────────┐┌──────────────────┐
│  Payment   ││  Account   ││  Ledger    ││  Risk      ││  Notification    │
│  Service   ││  Service   ││  Service   ││  Service   ││  Service         │
│  (8081)    ││  (8082)    ││  (8083)    ││  (8084)    ││  (8085)          │
│            ││            ││            ││            ││                  │
│  Audit /   ││  Actuator  ││  Actuator  ││  Trace mw  ││  Trace from      │
│  Actuator  ││  /Trace    ││  /Trace    ││  (FastAPI) ││  Kafka headers   │
└────────┬───┘└────────────┘└────────────┘└────────────┘└──────────────────┘
         │   X-Trace-Id across processes: HTTP header → MDC → Feign interceptor → Kafka header → Go/Python
         │   All logs carry [traceId=...]; payment-service writes the audit_log table asynchronously
         │ (sync Feign)
         ├──────────────────> Risk Service (risk rule engine)
         │                    - Blacklist check / amount limits
         │
         ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Message Queue (Kafka)                               │
│  Topics: payment.created | payment.completed | payment.failed               │
│          notification.send                                                  │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │
         ┌─────────────────────┼─────────────────────┐
         ▼                     ▼                      ▼
┌────────────────────┐  ┌────────────────────┐  ┌──────────────────────────────┐
│  PostgreSQL        │  │  Redis             │  │  Ledger Service              │
│  (primary DB)      │  │  (cache / locks /  │  │  (bookkeeping / entries)     │
│                    │  │   rate limiting)   │  │                              │
│ - Payment orders   │  │ - Idempotency      │  │ - Double-entry bookkeeping   │
│ - Accounts         │  │ - Distributed lock │  │ - Transactional consistency  │
│ - Ledger entries   │  │ - Gateway limiter  │  │                              │
│ - Refunds          │  │                    │  │                              │
└────────────────────┘  └────────────────────┘  └──────────────────────────────┘
```

---

## 2. Microservices

| Service | Responsibility | Tech Stack | Port |
|---------|----------------|------------|------|
| `frontend` | Frontend SPA + Nginx reverse proxy | React + TypeScript + Ant Design + Nginx | 3000 |
| `gateway-service` | API gateway (routing, JWT auth, rate limiting, distributed tracing) | Java 21 + Spring Cloud Gateway | 8080 |
| `payment-service` | Core payment flow (create, query, refund) | Java 21 + Spring Boot 3 | 8081 |
| `account-service` | Account management, balance queries, freeze/unfreeze, transfers | Java 21 + Spring Boot 3 | 8082 |
| `ledger-service` | Double-entry bookkeeping, ledger entry management | Java 21 + Spring Boot 3 | 8083 |
| `risk-service` | Risk rule engine, blacklist, amount limits | Python 3.12 + FastAPI | 8084 |
| `notification-service` | Webhook callback notifications | Go 1.22 | 8085 |

---

## 3. API Design

### 3.1 Create Payment

```
POST /api/v1/payments
Content-Type: application/json
Idempotency-Key: {client-generated-uuid}

Request:
{
  "from_account": "ACC_001",
  "to_account": "ACC_002",
  "amount": 10000,           // Unit: cents, avoids floating-point precision issues
  "currency": "CNY",
  "payment_method": "BANK_TRANSFER",
  "memo": "Payment for goods",
  "callback_url": "https://merchant.com/webhook/payment"
}

Response 201 Created:
{
  "payment_id": "PAY_20260322_abcdef",
  "status": "PENDING",
  "created_at": "2026-03-22T10:00:00Z"
}
```

### 3.2 Get Payment Status

```
GET /api/v1/payments/{payment_id}

Response 200:
{
  "payment_id": "PAY_20260322_abcdef",
  "status": "COMPLETED",        // PENDING | PROCESSING | COMPLETED | FAILED | REFUNDED
  "from_account": "ACC_001",
  "to_account": "ACC_002",
  "amount": 10000,
  "currency": "CNY",
  "created_at": "2026-03-22T10:00:00Z",
  "completed_at": "2026-03-22T10:00:03Z"
}
```

### 3.3 Refund

```
POST /api/v1/payments/{payment_id}/refund
Idempotency-Key: {uuid}

Request:
{
  "amount": 5000,              // Partial refunds are supported
  "reason": "Goods returned"
}

Response 201 Created:
{
  "refund_id": "REF_20260322_xyz",
  "payment_id": "PAY_20260322_abcdef",
  "status": "PROCESSING",
  "amount": 5000
}
```

### 3.4 List Refunds

```
GET /api/v1/payments/{payment_id}/refunds

Response 200:
[
  {
    "refund_id": "REF_20260322_xyz",
    "payment_id": "PAY_20260322_abcdef",
    "amount": 5000,
    "reason": "Goods returned",
    "status": "COMPLETED",
    "created_at": "2026-03-22T10:01:00Z",
    "completed_at": "2026-03-22T10:01:30Z"
  }
]
```

### 3.5 List Accounts

```
GET /api/v1/accounts

Response 200:
[
  {
    "account_id": "ACC_001",
    "account_name": "Test Account 1",
    "available_balance": 500000,
    "frozen_balance": 0,
    "currency": "CNY",
    "updated_at": "2026-03-22T10:00:00Z"
  }
]
```

### 3.6 Get Account Balance

```
GET /api/v1/accounts/{account_id}/balance

Response 200:
{
  "account_id": "ACC_001",
  "available_balance": 500000,   // Available balance (cents)
  "frozen_balance": 10000,       // Frozen balance (cents)
  "currency": "CNY",
  "updated_at": "2026-03-22T10:00:00Z"
}
```

### 3.7 Audit Log Queries

```
GET /api/v1/audit/logs?page=1&size=20         # Page through all entries
GET /api/v1/audit/trace/{traceId}             # Replay every operation in a trace by trace ID
GET /api/v1/audit/resource?resource_type=PAYMENT&resource_id=PAY_xxx  # Query by resource

Response 200 (example):
[
  {
    "id": 42,
    "trace_id": "8f4c1e2a3b...",
    "service_name": "payment-service",
    "action": "FREEZE_AMOUNT",
    "resource_type": "PAYMENT",
    "resource_id": "PAY_20260501_xxx",
    "detail": "account=acc001 amount=10000",
    "result": "SUCCESS",
    "created_at": "2026-05-01T10:00:01Z"
  }
]
```

### 3.8 Monitoring Endpoints (all Java services)

```
GET /actuator/health      # Health check (with details)
GET /actuator/info        # Service info
GET /actuator/metrics     # Metrics
GET /actuator/env         # Environment variables
```

---

## 4. Payment Flow

```
Client             Nginx(frontend)    GatewaySvc      PaymentSvc      RiskSvc       AccountSvc      LedgerSvc       Kafka         NotifySvc
  │                     │                │               │              │               │              │              │
  │  POST /payments     │                │               │              │               │              │              │
  │────────────────────>│                │               │              │               │              │              │
  │                     │  proxy_pass    │               │              │               │              │              │
  │                     │───────────────>│               │              │               │              │              │
  │                     │                │  JWT auth     │              │               │              │              │
  │                     │                │  Limit 10r/s  │              │               │              │              │
  │                     │                │  Add Trace ID │              │               │              │              │
  │                     │                │  Routing      │              │               │              │              │
  │                     │                │──────────────>│               │              │               │              │
  │                     │                │  Idempotency  │              │               │              │              │
  │                     │                │  (Redis SETNX)│              │               │              │              │
  │                     │                │  Persist order│              │               │              │              │
  │                     │                │  (CREATED)    │              │               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │  1.Risk check (sync)         │               │              │              │
  │                     │                │──────────────>│              │               │              │              │
  │                     │                │  PASS→PENDING │              │               │              │              │
  │                     │                │<──────────────│              │               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │  2.Publish payment.created   │               │              │              │
  │                     │                │────────────────────────────────────────────────────────────>│              │
  │  201 Created        │                │               │              │               │              │              │
  │<────────────────────│                │               │              │               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │  (async via Kafka consumer)    │              │               │              │              │
  │                     │                │  3.Freeze     │              │               │              │              │
  │                     │                │  (PROCESSING) │──────────────────────────────────────────> │              │
  │                     │                │               │  freeze: OK  │               │              │              │
  │                     │                │               │<─────────────│               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │               │  4.Ledger    │               │              │              │
  │                     │                │──────────────────────────────────────────────────────────> │              │
  │                     │                │               │  entry OK    │               │              │              │
  │                     │                │               │<─────────────────────────────│              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │  5.Transfer   │              │               │              │              │
  │                     │                │─────────────────────────────>│               │              │              │
  │                     │                │  transfer OK (COMPLETED)      │               │              │              │
  │                     │                │<─────────────────────────────│               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │  6.Publish payment.completed │               │              │              │
  │                     │                │────────────────────────────────────────────────────────────>│              │
  │                     │                │               │              │               │              │─────────────>│
  │                     │                │               │              │               │              │  7.Webhook   │──> callback
```

**Failure compensation (Saga):** Failure handling splits into two paths depending on whether the transfer has already happened:
- **Failure before transfer**: compensate in reverse order `reverseEntry → unfreezeAmount`, mark the status `FAILED` → publish `payment.failed`.
- **Failure after transfer** (DB save or event publish error): the money has already moved, so **compensation is strictly forbidden**. Audit `RECONCILE_REQUIRED` and make a best-effort attempt to correct the order to `COMPLETED`; anything left over is handled by the reconciliation job.

**Kafka redelivery protection:** On entry, `processPaymentAsync` skips immediately if `status != PENDING` (prevents a double charge when the same offset is replayed after a consumer restart).

### 4.1 Payment State Machine

```
                ┌──────────┐
                │ CREATED  │
                └────┬─────┘
                     │ risk check passed
                     ▼
                ┌──────────┐  risk rejected (sync)  ┌──────────┐
                │ PENDING  │───────────────────────>│ REJECTED │
                └────┬─────┘                        └──────────┘
      (CREATED can also → REJECTED)
                     │ triggered by Kafka consumer
                     ▼
                ┌──────────────┐
                │ PROCESSING   │
                └────┬────┬────┘
  transfer succeeded │    │ transfer failed (+ Saga compensation)
                     ▼    ▼
            ┌───────────┐ ┌──────────┐
            │ COMPLETED │ │  FAILED  │
            └─────┬─────┘ └──────────┘
                  │ refund initiated
                  ▼
            ┌───────────┐
            │ REFUNDED  │ (full/partial, RefundStatus: PROCESSING→COMPLETED/FAILED)
            └───────────┘
```

---

## 5. High-Concurrency Strategies

### 5.1 Idempotency Control

```java
// Redis-based idempotency control
@Component
public class IdempotencyFilter {

    @Autowired
    private RedisTemplate<String, String> redis;

    public boolean isDuplicate(String idempotencyKey) {
        // SETNX: only succeeds when the key does not exist, TTL 24h
        Boolean result = redis.opsForValue()
            .setIfAbsent("idempotent:" + idempotencyKey, "1", 24, TimeUnit.HOURS);
        return !Boolean.TRUE.equals(result);
    }
}
```

### 5.2 Distributed Locks (preventing concurrent debits on the same account)

```java
// Distributed lock based on Redis + Redisson
public PaymentResult processPayment(PaymentRequest req) {
    String lockKey = "account:lock:" + req.getFromAccount();
    RLock lock = redissonClient.getLock(lockKey);

    try {
        // Wait up to 5s to acquire the lock, hold it for at most 30s (prevents deadlock)
        if (lock.tryLock(5, 30, TimeUnit.SECONDS)) {
            return doTransfer(req);
        } else {
            throw new PaymentException("ACCOUNT_BUSY", "Account is busy, please retry later");
        }
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

### 5.3 Rate Limiting

```yaml
# gateway-service/src/main/resources/application.yml — Redis token bucket algorithm
spring:
  cloud:
    gateway:
      default-filters:
        - name: RequestRateLimiter
          args:
            redis-rate-limiter.replenishRate: 10      # Replenish 10 tokens per second
            redis-rate-limiter.burstCapacity: 20      # Max burst of 20 tokens
            redis-rate-limiter.requestedTokens: 1     # Each request consumes 1 token
            key-resolver: "#{@ipKeyResolver}"         # Rate limit by client IP
```

- **replenishRate=10**: at most 10 requests per second per client IP
- **burstCapacity=20**: allows an instantaneous burst of 20 requests; anything beyond returns 429
- **ipKeyResolver**: resolves the client IP from `X-Forwarded-For` → `X-Real-IP` → remote address
- Backed by Redis, so the limit is global across a distributed deployment

### 5.4 Database-Layer Optimization

```
Read/write splitting:
├── Master: writes (payments, transfers)
├── Slave x2: reads (queries, reconciliation)
└── Connection pool: HikariCP, maxPoolSize=50

Sharding (once a single table exceeds 50 million rows):
├── Hash account_id into 16 databases
├── Partition tables monthly by created_at
└── Use ShardingSphere middleware

Hot account handling:
├── Async bookkeeping: record journal entries first, aggregate on a schedule
├── In-memory bookkeeping: cache hot account balances in Redis
└── Merged debits: batch requests for the same account
```

### 5.5 Async + Event-Driven

```
Sync path (user waits): risk check → freeze amount → return 202
Async path (background): ledger entry → actual transfer → unfreeze → notify

Kafka topic design:
├── payment.created     (Producer: payment-service; partitioned by from_account to preserve per-account ordering)
├── payment.completed   (Producer: payment-service)
├── payment.failed      (Producer: payment-service)
└── notification.send   (Consumer: notification-service)

Note: ledger.entry and risk.alert do not exist in the code.
Ledger operations go through synchronous REST calls to ledger-service, not Kafka.
Every event message carries an X-Trace-Id header so cross-process tracing is never lost.
```

### 5.6 Optimistic Locking + Retry

```java
// AccountService: @Version optimistic lock + retry (up to 3 times, fresh transaction each time)
public void transfer(String from, String to, Long amount) {
    retryOnOptimisticLock(() -> transactionTemplate.executeWithoutResult(status -> {
        Account fromAcc = getAccount(from);
        Account toAcc   = getAccount(to);
        fromAcc.debit(amount);
        toAcc.credit(amount);
        accountRepository.save(fromAcc);
        accountRepository.save(toAcc);
    }));
}
```

- Both `Account` and `PaymentOrder` carry a `@Version` column
- On conflict, `GlobalExceptionHandler` returns **409 Conflict**
- ⚠️ **Do not initialize** the `@Version` field (leave it null); otherwise Spring Data treats the new entity as "already persisted", `save()` takes the `merge()` path, and a duplicate insert is triggered while the id is still null. The DB column already has `DEFAULT 0`.

### 5.7 Feign 4xx Error Propagation

```java
// FallbackPolicy: 4xx business errors must be rethrown as-is, never disguised as SERVICE_UNAVAILABLE
static void rethrowIfClientError(Throwable cause) {
    if (cause instanceof FeignException fe && fe.status() >= 400 && fe.status() < 500) {
        throw fe;
    }
}
```

`AccountClientFallbackFactory` / `LedgerClientFallbackFactory` call `FallbackPolicy.rethrowIfClientError()` before falling back: a 4xx means the upstream is reachable and returned a business error, so it should propagate directly; only 5xx / network errors / an open circuit take the `SERVICE_UNAVAILABLE` fallback. `RiskClientFallbackFactory` intentionally skips this handling (rejecting when risk control is unavailable is the safe policy).

### 5.8 Distributed Tracing

| Component | Implementation |
|-----------|----------------|
| Gateway | `TraceIdFilter` generates a UUID (if the inbound request has no `X-Trace-Id`) |
| Java services | `TraceFilter` (servlet, `HIGHEST_PRECEDENCE`) writes to SLF4J MDC; `logback-spring.xml` renders `[traceId=...]` |
| Java Feign outbound | `FeignTraceInterceptor` automatically copies it from MDC to the header |
| Kafka | `PaymentEventProducer` writes it to the message header; `PaymentEventConsumer` / Go consumer / Python middleware read it back |
| Python (risk) | FastAPI `trace_middleware` propagates it and attaches it to logs |
| Go (notification) | `extractTraceId(headers)` parses it from the Kafka header |

---

## 6. Database Design

### Core Table Schemas

```sql
-- Payment order table
CREATE TABLE payment_order (
    id              BIGSERIAL PRIMARY KEY,
    payment_id      VARCHAR(64)  NOT NULL UNIQUE,    -- Business key
    idempotency_key VARCHAR(64)  NOT NULL UNIQUE,    -- Idempotency key
    from_account    VARCHAR(32)  NOT NULL,
    to_account      VARCHAR(32)  NOT NULL,
    amount          BIGINT       NOT NULL,            -- Amount (cents)
    currency        VARCHAR(3)   NOT NULL DEFAULT 'CNY',
    status          VARCHAR(16)  NOT NULL DEFAULT 'CREATED',
    payment_method  VARCHAR(32)  NOT NULL,
    memo            VARCHAR(256),
    callback_url    VARCHAR(512),
    version         BIGINT       NOT NULL DEFAULT 0,    -- Optimistic lock (006_add_payment_order_version.sql)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);
CREATE INDEX idx_payment_from_account ON payment_order(from_account, created_at);
CREATE INDEX idx_payment_status ON payment_order(status);

-- Account table
CREATE TABLE account (
    id                BIGSERIAL PRIMARY KEY,
    account_id        VARCHAR(32)  NOT NULL UNIQUE,
    account_name      VARCHAR(128) NOT NULL,
    available_balance BIGINT       NOT NULL DEFAULT 0,  -- Available balance (cents)
    frozen_balance    BIGINT       NOT NULL DEFAULT 0,  -- Frozen amount (cents)
    currency          VARCHAR(3)   NOT NULL DEFAULT 'CNY',
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    version           BIGINT       NOT NULL DEFAULT 0,  -- Optimistic lock
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Ledger entry table (double-entry bookkeeping)
CREATE TABLE ledger_entry (
    id              BIGSERIAL PRIMARY KEY,
    entry_id        VARCHAR(64)  NOT NULL UNIQUE,
    payment_id      VARCHAR(64)  NOT NULL,
    debit_account   VARCHAR(32)  NOT NULL,    -- Debit account
    credit_account  VARCHAR(32)  NOT NULL,    -- Credit account
    amount          BIGINT       NOT NULL,
    currency        VARCHAR(3)   NOT NULL DEFAULT 'CNY',
    entry_type      VARCHAR(32)  NOT NULL,    -- PAYMENT / REFUND / FEE
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ledger_payment ON ledger_entry(payment_id);

-- Refund table
CREATE TABLE refund_order (
    id              BIGSERIAL PRIMARY KEY,
    refund_id       VARCHAR(64)  NOT NULL UNIQUE,
    payment_id      VARCHAR(64)  NOT NULL,
    amount          BIGINT       NOT NULL,
    reason          VARCHAR(256),
    status          VARCHAR(16)  NOT NULL DEFAULT 'PROCESSING',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

-- Audit log table (006_create_audit_log.sql)
CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    trace_id        VARCHAR(64),                          -- Trace ID
    service_name    VARCHAR(50)  NOT NULL,
    action          VARCHAR(100) NOT NULL,                -- e.g. FREEZE_AMOUNT / TRANSFER / RECONCILE_REQUIRED
    resource_type   VARCHAR(50)  NOT NULL,                -- PAYMENT / REFUND / ACCOUNT
    resource_id     VARCHAR(100),
    detail          TEXT,
    result          VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS',  -- SUCCESS / FAILED / INFO
    client_ip       VARCHAR(45),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_log_trace_id  ON audit_log(trace_id);
CREATE INDEX idx_audit_log_resource  ON audit_log(resource_type, resource_id);
CREATE INDEX idx_audit_log_action    ON audit_log(action);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);
```

---

## 7. Code Structure

```
payflow-engine/
│
├── README.md
├── CLAUDE.md                       # AI assistant guide
├── docker-compose.yml              # Full-stack local orchestration
│
├── gateway-service/                # API gateway (Java Spring Cloud Gateway)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/payflow/gateway/
│       ├── GatewayApplication.java
│       ├── config/
│       │   ├── JwtProperties.java              # JWT config properties (secret, enabled)
│       │   └── RateLimiterConfig.java           # IP rate-limit KeyResolver
│       └── filter/
│           ├── AuthenticationFilter.java        # JWT authentication filter (order -2)
│           ├── TraceIdFilter.java               # Tracing via X-Trace-Id (order -3)
│           └── RequestLoggingFilter.java        # Request logging (order -1)
│   └── src/main/resources/
│       └── application.yml                      # Routes, rate limiting, JWT config
│
├── frontend/                       # Frontend (React + TypeScript + Ant Design)
│   ├── nginx.conf                  # Static hosting + reverse proxy to Gateway Service
│   ├── Dockerfile
│   ├── package.json
│   ├── tsconfig.json
│   ├── vite.config.ts
│   ├── index.html
│   └── src/
│       ├── main.tsx                            # Entry point
│       ├── App.tsx                             # Route configuration
│       ├── layouts/
│       │   └── MainLayout.tsx                  # Sidebar layout
│       ├── pages/
│       │   ├── PaymentListPage.tsx             # Payment list (pagination/filtering)
│       │   ├── PaymentCreatePage.tsx           # Create-payment form
│       │   ├── PaymentDetailPage.tsx           # Payment detail + refund
│       │   ├── AccountPage.tsx                 # Account balance lookup
│       │   └── AuditPage.tsx                   # Audit log list + trace timeline
│       ├── api/
│       │   ├── client.ts                       # Axios instance (interceptors)
│       │   ├── payment.ts                      # Payment API wrapper
│       │   ├── account.ts                      # Account API wrapper
│       │   └── audit.ts                        # Audit API wrapper
│       ├── types/
│       │   └── index.ts                        # TypeScript type definitions
│       ├── utils/
│       │   └── format.ts                       # Amount/time formatting
│       └── styles/
│           └── global.css
│
├── payment-service/                # Core payment service (Java Spring Boot)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/payflow/payment/
│       ├── PaymentApplication.java
│       ├── controller/
│       │   └── PaymentController.java          # REST API entry point (incl. request record DTOs)
│       ├── service/
│       │   ├── PaymentService.java             # Payment orchestration (main flow + async processing + Saga compensation)
│       │   ├── RefundService.java              # Refund logic
│       │   └── pipeline/
│       │       ├── PaymentCreationPipeline.java # Pipeline orchestrator
│       │       ├── PaymentCreationContext.java  # Pipeline context (incl. shortCircuit)
│       │       ├── PaymentCreationStep.java     # Step interface
│       │       └── step/
│       │           ├── IdempotencyStep.java     # Step 1: idempotency reservation
│       │           ├── OrderPersistStep.java    # Step 2: order persistence
│       │           ├── RiskCheckStep.java       # Step 3: risk check
│       │           └── EventPublishStep.java    # Step 4: event publishing
│       ├── dto/
│       │   ├── CreatePaymentResponse.java
│       │   ├── PaymentResponse.java
│       │   ├── PaymentListResponse.java
│       │   ├── RefundResponse.java
│       │   └── RefundDetailResponse.java
│       ├── domain/
│       │   ├── PaymentOrder.java               # Payment order entity
│       │   ├── PaymentStatus.java              # Status enum (incl. state machine canTransitTo)
│       │   ├── RefundOrder.java                # Refund entity
│       │   └── RefundStatus.java               # Refund status enum (PROCESSING/COMPLETED/FAILED)
│       ├── repository/
│       │   ├── PaymentOrderRepository.java
│       │   └── RefundOrderRepository.java
│       ├── client/
│       │   ├── AccountClient.java              # Feign → account-service
│       │   ├── AccountClientFallbackFactory.java # Circuit-breaker fallback (4xx propagated)
│       │   ├── RiskClient.java                 # Feign → risk-service
│       │   ├── RiskClientFallbackFactory.java  # Circuit-breaker fallback (rejects on any error, safe policy)
│       │   ├── LedgerClient.java               # Feign → ledger-service
│       │   ├── LedgerClientFallbackFactory.java # Circuit-breaker fallback (4xx propagated)
│       │   └── FallbackPolicy.java             # rethrowIfClientError (4xx never disguised as SERVICE_UNAVAILABLE)
│       ├── audit/                              # Audit module
│       │   ├── AuditLog.java                   # Audit entity
│       │   ├── AuditLogRepository.java
│       │   ├── AuditService.java               # @Async writes, captures traceId automatically
│       │   └── AuditController.java            # /api/v1/audit/{logs|trace|resource}
│       ├── mq/
│       │   ├── PaymentEventProducer.java       # Kafka producer (writes the X-Trace-Id header)
│       │   └── PaymentEventConsumer.java       # Kafka consumer (reads X-Trace-Id, triggers processPaymentAsync)
│       ├── config/
│       │   ├── IdempotencyFilter.java          # Idempotency interceptor (Redis SETNX)
│       │   ├── KafkaConfig.java                # Kafka config
│       │   ├── WebConfig.java                  # Web config
│       │   ├── TraceFilter.java                # Inbound X-Trace-Id → MDC
│       │   └── FeignTraceInterceptor.java      # Injects traceId into outbound Feign calls
│       └── exception/
│           ├── PaymentException.java
│           └── GlobalExceptionHandler.java     # Incl. OptimisticLockException → 409
│   └── src/main/resources/
│       ├── application.yml                    # Resilience4j + Actuator
│       └── logback-spring.xml                 # [traceId=...] log pattern
│
├── account-service/                # Account service (Java Spring Boot)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/payflow/account/
│       ├── AccountApplication.java
│       ├── controller/
│       │   └── AccountController.java          # freeze / unfreeze / transfer / balance
│       ├── service/
│       │   └── AccountService.java             # TransactionTemplate + optimistic-lock retry (3 attempts)
│       ├── domain/
│       │   └── Account.java                    # @Version optimistic lock
│       ├── config/
│       │   └── TraceFilter.java                # Inbound X-Trace-Id → MDC
│       ├── repository/
│       │   └── AccountRepository.java
│       └── exception/
│           └── GlobalExceptionHandler.java     # OptimisticLockException → 409
│   └── src/main/resources/
│       ├── application.yml
│       └── logback-spring.xml
│
├── ledger-service/                 # Ledger service (Java Spring Boot)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/payflow/ledger/
│       ├── LedgerApplication.java
│       ├── controller/
│       │   └── LedgerController.java
│       ├── service/
│       │   └── LedgerService.java              # Double-entry bookkeeping logic
│       ├── domain/
│       │   └── LedgerEntry.java
│       ├── config/
│       │   └── TraceFilter.java                # Inbound X-Trace-Id → MDC
│       └── repository/
│           └── LedgerEntryRepository.java
│   └── src/main/resources/
│       ├── application.yml
│       └── logback-spring.xml
│
├── risk-service/                   # Risk service (Python FastAPI)
│   ├── Dockerfile
│   ├── requirements.txt
│   ├── main.py                                 # FastAPI entry point + trace_middleware
│   └── rules/
│       ├── rule_engine.py                      # Chain-of-responsibility orchestration
│       ├── blacklist.py                        # Blacklist check
│       └── amount_limit.py                     # Amount limit rule
│
├── notification-service/           # Notification service (Go)
│   ├── Dockerfile
│   ├── go.mod
│   ├── main.go
│   ├── handler/
│   │   └── webhook.go                          # Webhook callback
│   └── consumer/
│       └── kafka_consumer.go                   # Consumes Kafka events (extractTraceId)
│
└── sql/                            # Database init scripts (run in lexical order at container startup)
    ├── 001_create_payment_order.sql
    ├── 002_create_account.sql
    ├── 003_create_ledger_entry.sql
    ├── 004_create_refund_order.sql
    ├── 005_seed_accounts.sql       # Test account seed data (acc001-acc004)
    ├── 006_add_payment_order_version.sql  # PaymentOrder.version column (optimistic lock)
    └── 006_create_audit_log.sql           # Audit log table
```

---

## 8. Key Design Decisions

### 8.1 Why double-entry bookkeeping?

The core rule of a banking system is that **money can neither appear nor disappear**. Double-entry bookkeeping guarantees every transaction has a debit side and a credit side, and the amounts must be equal:

```
Payer (debit) 10000  ──>  Payee (credit) 10000
```

At any moment `SUM(debit) == SUM(credit)`; otherwise the system raises an alarm.

### 8.2 Why freeze before transferring?

```
Freeze model:  available balance ─(freeze)─> frozen balance ─(confirm)─> payee's available balance
Direct debit:  available balance ─(debit)─> payee's available balance (hard to roll back on failure)
```

The freeze model provides **two-phase commit** semantics: even if a downstream step fails, the funds can be safely unfrozen and rolled back.

### 8.3 Why store amounts as BIGINT (cents)?

- Floating-point numbers have precision problems: `0.1 + 0.2 ≠ 0.3`
- Representing amounts as integers in the smallest currency unit (cents) avoids precision loss entirely
- The presentation layer divides by 100 to convert to yuan

### 8.4 High-Availability Guarantees

```
┌──────────────────────────────────────────────────────────┐
│               High-Availability Strategy                 │
├──────────────────────────────────────────────────────────┤
│ Service layer: K8s multi-replica + HPA autoscaling       │
│ Data layer:    PG primary/replica + automatic failover   │
│ Cache layer:   Redis Cluster, 6 nodes                    │
│ Message layer: Kafka 3 brokers, replication factor = 3   │
│ Gateway layer: Nginx multi-instance + Keepalived VIP     │
│ DR:            multi-AZ deployment, RPO=0, RTO<30s       │
└──────────────────────────────────────────────────────────┘
```

---

## 9. Local Setup Guide

### 9.1 Prerequisites

| Tool | Minimum Version | Purpose |
|------|-----------------|---------|
| Docker & Docker Compose | 24+ / 2.20+ | Infrastructure (PG, Redis, Kafka) |
| Java (JDK) | 21+ | payment / account / ledger services |
| Maven | 3.9+ | Java project builds |
| Node.js | 20+ | Frontend build |
| Python | 3.12+ | Risk service |
| Go | 1.22+ | Notification service |

### 9.2 Quick Start (recommended)

```bash
# 1. Clone the project
git clone https://github.com/larryaus/payflow-engine.git
cd payflow-engine

# 2. Start the infrastructure (PostgreSQL + Redis + Kafka)
docker-compose up -d postgres redis kafka

# 3. Wait for the infrastructure to be ready (about 10-15 seconds)
echo "Waiting for infrastructure..."
sleep 15

# 4. Verify the infrastructure status
docker-compose ps
```

### 9.3 Starting Each Service Individually

#### Terminal 1 — Risk Service (Python)

```bash
cd risk-service
python -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8084 --reload
```

Verify: `curl http://localhost:8084/health`

#### Terminal 2 — Account Service (Java)

```bash
cd account-service
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8082"
```

#### Terminal 3 — Ledger Service (Java)

```bash
cd ledger-service
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8083"
```

#### Terminal 4 — Payment Service (Java)

```bash
cd payment-service
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
```

#### Terminal 5 — Notification Service (Go)

```bash
cd notification-service
go run main.go
```

Verify: `curl http://localhost:8085/health`

#### Terminal 6 — API Gateway (Java)

```bash
cd gateway-service
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"
```

Verify: `curl http://localhost:8080/actuator/health`

#### Terminal 7 — Frontend

```bash
cd frontend
npm install
npm run dev
```

Open in your browser: **http://localhost:3000**

### 9.4 Starting Everything with Docker Compose

```bash
# Start all services + infrastructure in one command
docker-compose up -d

# Show the status of all services
docker-compose ps

# Tail the logs of a single service
docker-compose logs -f payment-service

# Stop all services
docker-compose down

# Stop and remove data volumes (full reset)
docker-compose down -v
```

### 9.5 Service Port Overview

Once everything is up, the services listen on the following ports:

```
┌──────────────────────────────────┬────────┬──────────────────────────┐
│ Service                          │ Port   │ Address                  │
├──────────────────────────────────┼────────┼──────────────────────────┤
│ Frontend (Nginx + React SPA)     │ 3000   │ http://localhost:3000    │
│ Gateway Service (API gateway)    │ 8080   │ http://localhost:8080    │
│ Payment Service                  │ 8081   │ http://localhost:8081    │
│ Account Service                  │ 8082   │ http://localhost:8082    │
│ Ledger Service                   │ 8083   │ http://localhost:8083    │
│ Risk Service                     │ 8084   │ http://localhost:8084    │
│ Notification Service             │ 8085   │ http://localhost:8085    │
├──────────────────────────────────┼────────┼──────────────────────────┤
│ PostgreSQL                       │ 5432   │ localhost:5432           │
│ Redis                            │ 6379   │ localhost:6379           │
│ Kafka                            │ 9092   │ localhost:9092           │
└──────────────────────────────────┴────────┴──────────────────────────┘
```

### 9.6 Quick Verification

```bash
# 1. Health checks
curl http://localhost:8084/health        # Risk Service
curl http://localhost:8085/health        # Notification Service

# 2. Create a payment order through the gateway (JWT_ENABLED=false by default, no Authorization needed)
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "from_account": "ACC_001",
    "to_account": "ACC_002",
    "amount": 10000,
    "currency": "CNY",
    "payment_method": "BANK_TRANSFER",
    "memo": "Test payment"
  }'

# 3. Get the payment status
curl http://localhost:8080/api/v1/payments/PAY_XXXXXXXX_XXXXXX

# 4. List accounts
curl http://localhost:8080/api/v1/accounts

# 5. Get an account balance
curl http://localhost:8080/api/v1/accounts/ACC_001/balance

# 6. Initiate a refund
curl -X POST http://localhost:8080/api/v1/payments/PAY_XXXXXXXX_XXXXXX/refund \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "amount": 5000,
    "reason": "Test refund"
  }'

# 7. Look up the end-to-end audit trail by trace ID
TRACE_ID=$(curl -sI http://localhost:8080/api/v1/accounts | awk '/X-Trace-Id/ {print $2}' | tr -d '\r')
curl http://localhost:8080/api/v1/audit/trace/$TRACE_ID

# Tip: to enable JWT on the gateway, set JWT_ENABLED=true and add Authorization: Bearer <token> to requests
```

### 9.7 Database Connections

```bash
# Connect to PostgreSQL
psql -h localhost -U payflow -d payflow
# Password: payflow_secret

# List the created tables
\dt

# Connect to Redis
redis-cli -h localhost -p 6379
```

### 9.8 Troubleshooting

| Problem | Solution |
|---------|----------|
| Port already in use | Run `lsof -i :PORT` to find the process and kill it, or change the port in the config |
| Kafka fails to start | Make sure Docker has enough memory allocated (>= 4GB recommended) |
| Java services can't connect to PG | Confirm PostgreSQL has fully started: `docker-compose logs postgres` |
| Frontend API requests return 404 | Confirm the Vite proxy points to Gateway Service (8080), or call the backend services directly |
| Slow Maven downloads | Configure `~/.m2/settings.xml` to use a closer mirror (e.g. the Aliyun mirror) |

---

## 10. Known Issues & TODO

Findings from a code review of the current `main`. Items are grouped by impact; tick them off as fixes land.

### 10.1 🔴 Money safety (fix first)

- [ ] **Reject non-positive amounts everywhere.** `RefundRequest.amount` is only checked against the original payment amount, never for `> 0`. Because `Account.freeze` / `debit` / `credit` also accept negative values, a refund with `amount = -X` on any `COMPLETED` payment moves `X` from the payer to the payee and can drive the payer's balance negative. Add `@Positive` validation on request DTOs and guard every `Account` balance method. (`RefundService`, `PaymentController`, `Account`)
- [ ] **Keep payment currency consistent with account and ledger balances.** The frontend offers USD and `OrderPersistStep` stores it, but account operations take only an amount and account IDs, while accounts and new ledger entries use CNY. A USD payment can therefore move the same numeric amount of CNY and leave a CNY ledger entry under a USD order. Either accept only CNY end-to-end or validate matching account currencies and model explicit conversion before moving funds. (`PaymentCreatePage`, `OrderPersistStep`, `AccountService`, `LedgerEntry`)
- [ ] **Stop exposing internal money-moving endpoints through the gateway.** `/api/v1/accounts/**` and `/api/v1/ledger/**` are routed publicly, including `freeze`, `unfreeze`, `transfer` and ledger entry creation/reversal, and JWT is disabled by default with no role checks when enabled. Anyone can freeze and transfer funds out of any account. Route only read endpoints externally and keep mutations service-to-service. (`gateway-service/src/main/resources/application.yml`)
- [ ] **Cap cumulative partial refunds.** Each refund is validated in isolation, so refunding 60 twice on a 100 payment succeeds. Several partial refunds that add up to the full amount also never move the payment to `REFUNDED`. Validate against the sum of completed and in-flight refunds. (`RefundService`)
- [ ] **Serialize refund reservations through commit.** `RefundService.createRefund` releases its per-payment Redis lock after reading `COMPLETED` and inserting a refund, before `executeRefundSaga` moves money and before the surrounding `@Transactional` method commits. Another request can enter with the same payment and see neither a committed reservation nor a changed payment status. A cumulative-sum check alone would still race. Commit an atomic per-payment refundable-balance reservation (or use a database row lock with equivalent committed state), then execute idempotent remote operations against that reservation. (`RefundService.createRefund`)
- [ ] **Fix the refund Saga's post-transfer compensation.** If `transfer` succeeds and the ledger entry then fails, compensation only calls `unfreezeAmount`. The frozen funds were already debited, so the unfreeze fails (its error is swallowed) and the refund is marked `FAILED` with money moved and no ledger record. The whole Saga also runs inside one `@Transactional` method, so a failed final save rolls back the refund row after funds have moved. (`RefundService`)
- [ ] **Track frozen funds per payment, not per account.** `frozen_balance` is one number per account, and `freeze` / `unfreeze` / `transfer` carry no payment reference. When a call times out but actually succeeded, compensation acts on the shared pool. If `transfer` went through, the follow-up `unfreezeAmount` can release funds that another in-flight payment froze on the same account: the payee keeps the money and the payer gets it back as available balance. If `freeze` went through, the funds stay frozen and nothing ever releases them. Record a hold per payment (payment ID, amount, state) and have `transfer` and `unfreeze` consume that hold. (`Account`, `AccountService`, `PaymentService.compensate`)

### 10.2 🟠 Functional bugs

- [ ] **Risk-rejected orders are rolled back and lock the idempotency key.** `RiskCheckStep` saves `REJECTED` and then throws inside the `@Transactional` `createPayment`, so the whole transaction rolls back and the `REJECTED` order is never persisted. `OrderPersistStep` has already written the payment ID to Redis, though, so every retry with the same `Idempotency-Key` returns 409 "original order not found" for 24 hours. The same happens for any exception raised after `OrderPersistStep`. (`RiskCheckStep`, `OrderPersistStep`, `PaymentService`)
- [ ] **Tie the idempotency key to the transaction outcome.** If creation fails before `OrderPersistStep` writes the payment ID (for example, the order insert itself fails), the key stays at `PROCESSING`, and every retry waits 1s and then gets 409 "still processing" for 24 hours. The payment ID is also written to Redis before the transaction commits, so a concurrent duplicate can read it, miss the uncommitted row, and get 409 "original order not found". `RefundService` has the same problem: its key is never cleared when the refund throws. Delete the key when the transaction rolls back, and write the payment ID only after commit. (`IdempotencyFilter`, `IdempotencyStep`, `OrderPersistStep`, `RefundService`)
- [ ] **`GET /api/v1/ledger/verify` always returns `false`.** The sum queries filter on `entryType = 'DEBIT'` / `'CREDIT'`, but entries are written as `PAYMENT` / `REVERSAL`, so both sums are `null`. Because each row already carries both sides, a global debit = credit check is tautological anyway. Verify per account or per payment (e.g. `PAYMENT` minus `REVERSAL`) instead. Per-payment checks also need reversals filed under the original payment ID: today they are written as `REVERSAL_<paymentId>`, so `GET /api/v1/ledger/entries?payment_id=...` does not return them either (#11 fixes this). (`LedgerEntryRepository`, `LedgerService`)
- [ ] **Failure webhooks are never delivered.** The `payment.failed` event has no `callback_url`, so notification-service skips it. `Map.of` also throws `NullPointerException` when `reason` is `null`. (`PaymentEventProducer.sendPaymentFailed`)
- [ ] **Failures before `PROCESSING` hit an illegal state transition.** When the account lock is not acquired within 5s (or the wait is interrupted), `handleAsyncFailure` ends in `transitTo(FAILED)` on a `PENDING` order. `PENDING → FAILED` is not allowed, so it throws `IllegalStateException`. Kafka retries the message 3 times; if the lock is still busy, it lands in `payment.created.DLT` and the order stays `PENDING` for good. Either allow `PENDING → FAILED`, or rethrow pre-`PROCESSING` failures so Kafka retries them without touching the order. #11's `markFailed` hits the same transition when `markProcessing` fails. (`PaymentService.handleAsyncFailure`, `PaymentStatus`)
- [ ] **Use the saved entity version during asynchronous status updates.** `processPaymentAsync` has no encompassing DB transaction. Its first `paymentOrderRepository.save(order)` changes `PENDING` to `PROCESSING`, but ignores the returned JPA entity. With `@Version`, the original `order` retains the old version, so its later `COMPLETED` or `FAILED` save fails with an optimistic-lock exception. In an isolated PostgreSQL reproduction using the current code, a freeze failure left the order in `PROCESSING` and Kafka redelivery skipped it; the happy path hit the same conflict and only finished via `tryFinalizeCompleted`. Use the returned entity or reload before further updates, and retain durable recovery for crashes between steps. This applies to the current Kafka path; #11 removes this method and needs its own transition checks. (`PaymentService.processPaymentAsync`, `PaymentOrder`)
- [ ] **Stuck orders have no recovery path.** The README and CLAUDE.md hand missed finalizations to "the reconciliation job", but no scheduled job exists. In the current Kafka flow, if payment-service dies after `freeze`, the redelivered `payment.created` sees `PROCESSING` and is skipped, so the order stays `PROCESSING` with funds frozen. Orders whose `payment.created` never arrives stay `PENDING`, and `RECONCILE_REQUIRED` audit rows are never acted on. Add a sweeper that picks up orders stuck in `PENDING` / `PROCESSING` past a timeout and settles them against account-service and ledger-service state, using the per-payment holds and idempotency keys from 10.1 and 10.3. #11 makes in-flight workflows durable, but still needs this for workflows that run out of retries. (`PaymentService`)
- [ ] **Audit rows never carry a trace ID.** `AuditService.log` is `@Async` and reads `traceId` from MDC on the executor thread. MDC is thread-local and no `TaskDecorator` copies it over, so every row is written with `trace_id = NULL` and `GET /api/v1/audit/trace/{traceId}` returns nothing. Read the trace ID on the calling thread and pass it in, or register a `TaskDecorator` that copies the MDC. (`AuditService`)
- [ ] **A lost `payment.completed` is never republished.** If the `COMPLETED` save succeeds but `sendPaymentCompleted` then throws (e.g. a Kafka metadata timeout), `tryFinalizeCompleted` finds the order already `COMPLETED` and returns without republishing, so the success webhook is never sent. CLAUDE.md describes this path as re-save plus republish. This applies to the current Kafka flow; #11 replaces this code. (`PaymentService.tryFinalizeCompleted`)

### 10.3 🟡 Reliability & engineering

- [ ] **Make account and ledger operations idempotent, and configure timeouts explicitly.** No Resilience4j `TimeLimiter` is configured. If the Spring Cloud CircuitBreaker default (1s) applies, a slow call is reported as `SERVICE_UNAVAILABLE` while it may still succeed on the server, and the retry or compensation that follows acts on the wrong assumption (double freeze or transfer, duplicate ledger rows). Pass an idempotency key (e.g. `paymentId` + operation) to account-service and ledger-service, back it with a unique constraint, and set explicit timeouts. A key alone does not settle a timeout: treat it as an unknown outcome and look the operation up by its key before retrying or compensating.
- [ ] **Hand off to async processing only after commit.** The async trigger (Kafka `payment.created` publish, or Temporal workflow start once #11 lands) fires inside the `createPayment` transaction. The consumer can read the order before it is committed, and if the transaction rolls back the async flow still runs for an order that does not exist. Prefer an outbox table. `TransactionSynchronization.afterCommit` alone fixes the ordering but can still lose the hand-off: `PaymentEventProducer` never checks the future returned by `kafkaTemplate.send()`, so an asynchronous send failure leaves the order `PENDING` after the client has already received 201. A failed post-commit workflow start under #11 has the same effect.
- [ ] **Harden notification-service.** `callback_url` is user-supplied and `/api/v1/notify/webhook` is public, so the service can be made to POST to any internal address (SSRF). Allow-list schemes and hosts and block private ranges. Webhook retries also sleep inside the Kafka consume loop, so one slow endpoint stalls a whole topic for tens of seconds. Move delivery to a worker pool or a retry topic.
- [ ] **Generate the frontend `Idempotency-Key` once per submission.** `api/payment.ts` creates a new key on every call, so a retry after a network timeout becomes a second payment. Create the key when the form is submitted and reuse it for retries of that submission.
- [ ] **Don't count business errors as service failures.** `FallbackPolicy` rethrows 4xx responses unchanged, but the circuit breaker still records them as failures. With a window of 10 and a 50% threshold, a burst of insufficient-balance rejections can open the account-service circuit for every caller for 30s. Record only 5xx, network errors and timeouts. Once #11 lands, also mark 4xx errors non-retryable in the Temporal activity options. (`payment-service/src/main/resources/application.yml`)
- [ ] **Add tests.** CI runs `mvn -B clean verify`, but there are no test sources. Start with `Account` balance arithmetic, the `PaymentStatus` state machine, `RefundService` validation, and the payment Saga's compensation paths.
