# PayFlow Engine - 银行支付流系统

## 1. 系统架构总览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Client (Browser)                               │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │ HTTP
                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Frontend — Nginx (port 3000 / 80)                        │
│   ┌──────────────────────┐  ┌────────────────────────────────────────┐      │
│   │ 静态资源 (React SPA)  │  │ 反向代理 → Gateway Service              │      │
│   └──────────────────────┘  └────────────────────────────────────────┘      │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │ /api/*
                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│               Gateway Service — Spring Cloud Gateway (port 8080)            │
│   ┌────────────────┐ ┌──────────────┐ ┌────────────┐ ┌───────────────┐     │
│   │ JWT 认证        │ │ Redis 限流    │ │ Trace ID   │ │ 请求日志       │     │
│   │ (Bearer Token) │ │ (10r/s/IP)   │ │ (X-Trace-Id)│ │ (method/path) │     │
│   └────────────────┘ └──────────────┘ └────────────┘ └───────────────┘     │
└──────┬──────────┬──────────┬──────────┬──────────┬─────────────────────────┘
       │          │          │          │          │
       ▼          ▼          ▼          ▼          ▼
┌────────────┐┌────────────┐┌────────────┐┌────────────┐┌──────────────────┐
│  Payment   ││  Account   ││  Ledger    ││  Risk      ││  Notification    │
│  Service   ││  Service   ││  Service   ││  Service   ││  Service         │
│  (8081)    ││  (8082)    ││  (8083)    ││  (8084)    ││  (8085)          │
└────────┬───┘└────────────┘└────────────┘└────────────┘└──────────────────┘
         │ (sync Feign)
         ├──────────────────> Risk Service (风控规则引擎)
         │                    - 黑名单检查 / 限额管理
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
┌──────────────────┐  ┌──────────────┐  ┌──────────────────────┐
│  PostgreSQL      │  │  Redis       │  │  Ledger Service      │
│  (主数据库)       │  │  (缓存/锁/   │  │  (记账/分录服务)      │
│                  │  │   限流)       │  │                      │
│ - 支付订单表     │  │ - 幂等性控制  │  │ - 复式记账           │
│ - 账户表         │  │ - 分布式锁   │  │ - 事务一致性         │
│ - 分录表         │  │ - 网关限流   │  │                      │
│ - 退款表         │  │              │  │                      │
└──────────────────┘  └──────────────┘  └──────────────────────┘
```

---

## 2. 微服务拆分

| 服务名 | 职责 | 技术栈 | 端口 |
|--------|------|--------|------|
| `frontend` | 前端 SPA + Nginx 反向代理 | React + TypeScript + Ant Design + Nginx | 3000 |
| `gateway-service` | API 网关（路由、JWT 认证、限流、链路追踪） | Java 21 + Spring Cloud Gateway | 8080 |
| `payment-service` | 支付核心流程（下单、查询、退款） | Java 21 + Spring Boot 3 | 8081 |
| `account-service` | 账户管理、余额查询、冻结解冻、转账 | Java 21 + Spring Boot 3 | 8082 |
| `ledger-service` | 复式记账、分录管理 | Java 21 + Spring Boot 3 | 8083 |
| `risk-service` | 风控规则引擎、黑名单、限额 | Python 3.12 + FastAPI | 8084 |
| `notification-service` | Webhook 回调通知 | Go 1.22 | 8085 |

---

## 3. API 设计

### 3.1 支付下单

```
POST /api/v1/payments
Content-Type: application/json
Idempotency-Key: {client-generated-uuid}

Request:
{
  "from_account": "ACC_001",
  "to_account": "ACC_002",
  "amount": 10000,           // 单位: 分(cent), 避免浮点精度问题
  "currency": "CNY",
  "payment_method": "BANK_TRANSFER",
  "memo": "货款支付",
  "callback_url": "https://merchant.com/webhook/payment"
}

Response 201 Created:
{
  "payment_id": "PAY_20260322_abcdef",
  "status": "PENDING",
  "created_at": "2026-03-22T10:00:00Z"
}
```

### 3.2 查询支付状态

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

### 3.3 退款

```
POST /api/v1/payments/{payment_id}/refund
Idempotency-Key: {uuid}

Request:
{
  "amount": 5000,              // 支持部分退款
  "reason": "商品退货"
}

Response 201 Created:
{
  "refund_id": "REF_20260322_xyz",
  "payment_id": "PAY_20260322_abcdef",
  "status": "PROCESSING",
  "amount": 5000
}
```

### 3.4 查询退款列表

```
GET /api/v1/payments/{payment_id}/refunds

Response 200:
[
  {
    "refund_id": "REF_20260322_xyz",
    "payment_id": "PAY_20260322_abcdef",
    "amount": 5000,
    "reason": "商品退货",
    "status": "COMPLETED",
    "created_at": "2026-03-22T10:01:00Z",
    "completed_at": "2026-03-22T10:01:30Z"
  }
]
```

### 3.5 账户列表

```
GET /api/v1/accounts

Response 200:
[
  {
    "account_id": "ACC_001",
    "account_name": "测试账户1",
    "available_balance": 500000,
    "frozen_balance": 0,
    "currency": "CNY",
    "updated_at": "2026-03-22T10:00:00Z"
  }
]
```

### 3.6 账户余额查询

```
GET /api/v1/accounts/{account_id}/balance

Response 200:
{
  "account_id": "ACC_001",
  "available_balance": 500000,   // 可用余额(分)
  "frozen_balance": 10000,       // 冻结余额(分)
  "currency": "CNY",
  "updated_at": "2026-03-22T10:00:00Z"
}
```

---

## 4. Payment Flow (支付流程)

```
Client             Nginx(frontend)    GatewaySvc      PaymentSvc      RiskSvc       AccountSvc      LedgerSvc       Kafka         NotifySvc
  │                     │                │               │              │               │              │              │
  │  POST /payments     │                │               │              │               │              │              │
  │────────────────────>│                │               │              │               │              │              │
  │                     │  proxy_pass    │               │              │               │              │              │
  │                     │───────────────>│               │              │               │              │              │
  │                     │                │  JWT 认证      │              │               │              │              │
  │                     │                │  限流 (10r/s)  │              │               │              │              │
  │                     │                │  Trace ID 注入 │              │               │              │              │
  │                     │                │  路由转发      │              │               │              │              │
  │                     │                │──────────────>│               │              │               │              │
  │                     │                │  幂等性检查    │              │               │              │              │
  │                     │                │  (Redis SETNX)│              │               │              │              │
  │                     │                │  持久化订单    │              │               │              │              │
  │                     │                │  (CREATED)    │              │               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │  1.风控检查(sync)             │               │              │              │
  │                     │                │──────────────>│              │               │              │              │
  │                     │                │  PASS→PENDING │              │               │              │              │
  │                     │                │<──────────────│              │               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │  2.发布 payment.created       │               │              │              │
  │                     │                │────────────────────────────────────────────────────────────>│              │
  │  201 Created        │                │               │              │               │              │              │
  │<────────────────────│                │               │              │               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │  (async via Kafka consumer)    │              │               │              │              │
  │                     │                │  3.冻结金额    │              │               │              │              │
  │                     │                │  (PROCESSING) │──────────────────────────────────────────> │              │
  │                     │                │               │  freeze: OK  │               │              │              │
  │                     │                │               │<─────────────│               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │               │  4.复式记账   │               │              │              │
  │                     │                │──────────────────────────────────────────────────────────> │              │
  │                     │                │               │  entry OK    │               │              │              │
  │                     │                │               │<─────────────────────────────│              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │  5.转账        │              │               │              │              │
  │                     │                │─────────────────────────────>│               │              │              │
  │                     │                │  transfer OK (COMPLETED)      │               │              │              │
  │                     │                │<─────────────────────────────│               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │  6.发布 payment.completed     │               │              │              │
  │                     │                │────────────────────────────────────────────────────────────>│              │
  │                     │                │               │              │               │              │─────────────>│
  │                     │                │               │              │               │              │  7.Webhook   │──> callback
```

**失败补偿（Saga）：** 若步骤 4/5 失败，按逆序补偿：reverseEntry → unfreezeAmount → 状态标记 FAILED → 发布 payment.failed。

### 4.1 支付状态机

```
                ┌──────────┐
                │ CREATED  │
                └────┬─────┘
                     │ 风控通过
                     ▼
                ┌──────────┐    风控拒绝(sync)  ┌──────────┐
                │ PENDING  │──────────────────>│ REJECTED │
                └────┬─────┘                  └──────────┘
      (CREATED也可→REJECTED)
                     │ Kafka消费触发
                     ▼
                ┌──────────────┐
                │ PROCESSING   │
                └────┬────┬────┘
          转账成功   │    │ 转账失败(+Saga补偿)
                     ▼    ▼
            ┌───────────┐ ┌──────────┐
            │ COMPLETED │ │  FAILED  │
            └─────┬─────┘ └──────────┘
                  │ 发起退款
                  ▼
            ┌───────────┐
            │ REFUNDED  │ (全额/部分, RefundStatus: PROCESSING→COMPLETED/FAILED)
            └───────────┘
```

---

## 5. 高并发处理策略

### 5.1 幂等性控制

```java
// 基于 Redis 的幂等性控制
@Component
public class IdempotencyFilter {

    @Autowired
    private RedisTemplate<String, String> redis;

    public boolean isDuplicate(String idempotencyKey) {
        // SETNX: 只有 key 不存在时才设置成功, TTL 24h
        Boolean result = redis.opsForValue()
            .setIfAbsent("idempotent:" + idempotencyKey, "1", 24, TimeUnit.HOURS);
        return !Boolean.TRUE.equals(result);
    }
}
```

### 5.2 分布式锁（防止同一账户并发扣款）

```java
// 基于 Redis + Redisson 的分布式锁
public PaymentResult processPayment(PaymentRequest req) {
    String lockKey = "account:lock:" + req.getFromAccount();
    RLock lock = redissonClient.getLock(lockKey);

    try {
        // 等待 5 秒获取锁, 持有锁最多 30 秒(防止死锁)
        if (lock.tryLock(5, 30, TimeUnit.SECONDS)) {
            return doTransfer(req);
        } else {
            throw new PaymentException("ACCOUNT_BUSY", "账户繁忙,请稍后重试");
        }
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

### 5.3 限流策略

```yaml
# gateway-service/src/main/resources/application.yml — Redis 令牌桶算法
spring:
  cloud:
    gateway:
      default-filters:
        - name: RequestRateLimiter
          args:
            redis-rate-limiter.replenishRate: 10      # 每秒补充 10 个令牌
            redis-rate-limiter.burstCapacity: 20      # 最大突发 20 个令牌
            redis-rate-limiter.requestedTokens: 1     # 每次请求消耗 1 个令牌
            key-resolver: "#{@ipKeyResolver}"         # 按客户端 IP 限流
```

- **replenishRate=10**：每个客户端 IP 每秒最多 10 个请求
- **burstCapacity=20**：允许瞬间突发 20 个请求，超出返回 429
- **ipKeyResolver**：从 `X-Forwarded-For` → `X-Real-IP` → remote address 解析客户端 IP
- 基于 Redis 实现，支持分布式部署下的全局限流

### 5.4 数据库层优化

```
读写分离:
├── Master: 写操作(支付、转账)
├── Slave x2: 读操作(查询、对账)
└── 连接池: HikariCP, maxPoolSize=50

分库分表(当单表 > 5000万行):
├── 按 account_id hash 分 16 个库
├── 按 created_at 按月分表
└── 使用 ShardingSphere 中间件

热点账户处理:
├── 异步记账: 先记流水, 定时汇总
├── 内存记账: 热点账户余额缓存在 Redis
└── 合并扣款: 批量处理同一账户的请求
```

### 5.5 异步化 + 事件驱动

```
同步链路(用户等待): 风控检查 → 冻结金额 → 返回 202
异步链路(后台处理): 分录记账 → 实际转账 → 解冻 → 通知

Kafka Topic 设计:
├── payment.created     (Producer: payment-service; 按 from_account 分区，保证同账户顺序)
├── payment.completed   (Producer: payment-service)
├── payment.failed      (Producer: payment-service)
└── notification.send   (Consumer: notification-service)

注意: ledger.entry 和 risk.alert 在代码中并不存在。
账务操作通过同步 REST 调用 ledger-service 完成，非 Kafka。
```

---

## 6. 数据库设计

### 核心表结构

```sql
-- 支付订单表
CREATE TABLE payment_order (
    id              BIGSERIAL PRIMARY KEY,
    payment_id      VARCHAR(64)  NOT NULL UNIQUE,    -- 业务主键
    idempotency_key VARCHAR(64)  NOT NULL UNIQUE,    -- 幂等键
    from_account    VARCHAR(32)  NOT NULL,
    to_account      VARCHAR(32)  NOT NULL,
    amount          BIGINT       NOT NULL,            -- 金额(分)
    currency        VARCHAR(3)   NOT NULL DEFAULT 'CNY',
    status          VARCHAR(16)  NOT NULL DEFAULT 'CREATED',
    payment_method  VARCHAR(32)  NOT NULL,
    memo            VARCHAR(256),
    callback_url    VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);
CREATE INDEX idx_payment_from_account ON payment_order(from_account, created_at);
CREATE INDEX idx_payment_status ON payment_order(status);

-- 账户表
CREATE TABLE account (
    id                BIGSERIAL PRIMARY KEY,
    account_id        VARCHAR(32)  NOT NULL UNIQUE,
    account_name      VARCHAR(128) NOT NULL,
    available_balance BIGINT       NOT NULL DEFAULT 0,  -- 可用余额(分)
    frozen_balance    BIGINT       NOT NULL DEFAULT 0,  -- 冻结金额(分)
    currency          VARCHAR(3)   NOT NULL DEFAULT 'CNY',
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    version           BIGINT       NOT NULL DEFAULT 0,  -- 乐观锁
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 记账分录表 (复式记账)
CREATE TABLE ledger_entry (
    id              BIGSERIAL PRIMARY KEY,
    entry_id        VARCHAR(64)  NOT NULL UNIQUE,
    payment_id      VARCHAR(64)  NOT NULL,
    debit_account   VARCHAR(32)  NOT NULL,    -- 借方账户
    credit_account  VARCHAR(32)  NOT NULL,    -- 贷方账户
    amount          BIGINT       NOT NULL,
    currency        VARCHAR(3)   NOT NULL DEFAULT 'CNY',
    entry_type      VARCHAR(32)  NOT NULL,    -- PAYMENT / REFUND / FEE
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ledger_payment ON ledger_entry(payment_id);

-- 退款表
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
```

---

## 7. Code Structure (项目代码结构)

```
payflow-engine/
│
├── README.md
├── CLAUDE.md                       # AI 助手指南
├── docker-compose.yml              # 全栈本地编排
│
├── gateway-service/                # API 网关 (Java Spring Cloud Gateway)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/payflow/gateway/
│       ├── GatewayApplication.java
│       ├── config/
│       │   ├── JwtProperties.java              # JWT 配置属性 (secret, enabled)
│       │   └── RateLimiterConfig.java           # IP 限流 KeyResolver
│       └── filter/
│           ├── AuthenticationFilter.java        # JWT 认证过滤器 (order -2)
│           ├── TraceIdFilter.java               # 链路追踪 X-Trace-Id (order -3)
│           └── RequestLoggingFilter.java        # 请求日志 (order -1)
│   └── src/main/resources/
│       └── application.yml                      # 路由、限流、JWT 配置
│
├── frontend/                       # 前端 (React + TypeScript + Ant Design)
│   ├── nginx.conf                  # 静态托管 + 反向代理到 Gateway Service
│   ├── Dockerfile
│   ├── package.json
│   ├── tsconfig.json
│   ├── vite.config.ts
│   ├── index.html
│   └── src/
│       ├── main.tsx                            # 入口
│       ├── App.tsx                             # 路由配置
│       ├── layouts/
│       │   └── MainLayout.tsx                  # 侧边栏布局
│       ├── pages/
│       │   ├── PaymentListPage.tsx             # 支付列表(分页/筛选)
│       │   ├── PaymentCreatePage.tsx           # 发起支付表单
│       │   ├── PaymentDetailPage.tsx           # 支付详情 + 退款
│       │   └── AccountPage.tsx                 # 账户余额查询
│       ├── api/
│       │   ├── client.ts                       # Axios 实例(拦截器)
│       │   ├── payment.ts                      # 支付 API 封装
│       │   └── account.ts                      # 账户 API 封装
│       ├── types/
│       │   └── index.ts                        # TypeScript 类型定义
│       ├── utils/
│       │   └── format.ts                       # 金额/时间格式化
│       └── styles/
│           └── global.css
│
├── payment-service/                # 支付核心服务 (Java Spring Boot)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/payflow/payment/
│       ├── PaymentApplication.java
│       ├── controller/
│       │   └── PaymentController.java          # REST API 入口 (含 request record DTOs)
│       ├── service/
│       │   ├── PaymentService.java             # 支付编排(主流程 + 异步处理 + Saga补偿)
│       │   ├── RefundService.java              # 退款逻辑
│       │   └── pipeline/
│       │       ├── PaymentCreationPipeline.java # 流水线编排器
│       │       ├── PaymentCreationContext.java  # 流水线上下文(含 shortCircuit)
│       │       ├── PaymentCreationStep.java     # Step 接口
│       │       └── step/
│       │           ├── IdempotencyStep.java     # Step1: 幂等占位
│       │           ├── OrderPersistStep.java    # Step2: 订单持久化
│       │           ├── RiskCheckStep.java       # Step3: 风控检查
│       │           └── EventPublishStep.java    # Step4: 事件发布
│       ├── dto/
│       │   ├── CreatePaymentResponse.java
│       │   ├── PaymentResponse.java
│       │   ├── PaymentListResponse.java
│       │   ├── RefundResponse.java
│       │   └── RefundDetailResponse.java
│       ├── domain/
│       │   ├── PaymentOrder.java               # 支付订单实体
│       │   ├── PaymentStatus.java              # 状态枚举(含状态机 canTransitTo)
│       │   ├── RefundOrder.java                # 退款实体
│       │   └── RefundStatus.java               # 退款状态枚举(PROCESSING/COMPLETED/FAILED)
│       ├── repository/
│       │   ├── PaymentOrderRepository.java
│       │   └── RefundOrderRepository.java
│       ├── client/
│       │   ├── AccountClient.java              # Feign → account-service
│       │   ├── AccountClientFallbackFactory.java # 熔断降级
│       │   ├── RiskClient.java                 # Feign → risk-service
│       │   ├── RiskClientFallbackFactory.java  # 熔断降级
│       │   ├── LedgerClient.java               # Feign → ledger-service
│       │   └── LedgerClientFallbackFactory.java # 熔断降级
│       ├── mq/
│       │   ├── PaymentEventProducer.java       # Kafka 生产者
│       │   └── PaymentEventConsumer.java       # Kafka 消费者(触发 processPaymentAsync)
│       ├── config/
│       │   ├── IdempotencyFilter.java          # 幂等性拦截器(Redis SETNX)
│       │   ├── KafkaConfig.java                # Kafka 配置
│       │   └── WebConfig.java                  # Web 配置
│       └── exception/
│           ├── PaymentException.java
│           └── GlobalExceptionHandler.java
│
├── account-service/                # 账户服务 (Java Spring Boot)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/payflow/account/
│       ├── AccountApplication.java
│       ├── controller/
│       │   └── AccountController.java          # freeze / unfreeze / transfer / balance
│       ├── service/
│       │   └── AccountService.java             # 余额操作、冻结解冻、分布式锁
│       ├── domain/
│       │   └── Account.java                    # @Version 乐观锁
│       └── repository/
│           └── AccountRepository.java
│
├── ledger-service/                 # 记账服务 (Java Spring Boot)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/payflow/ledger/
│       ├── LedgerApplication.java
│       ├── controller/
│       │   └── LedgerController.java
│       ├── service/
│       │   └── LedgerService.java              # 复式记账逻辑
│       ├── domain/
│       │   └── LedgerEntry.java
│       └── repository/
│           └── LedgerEntryRepository.java
│
├── risk-service/                   # 风控服务 (Python FastAPI)
│   ├── Dockerfile
│   ├── requirements.txt
│   ├── main.py                                 # FastAPI 入口
│   └── rules/
│       ├── rule_engine.py                      # 责任链编排
│       ├── blacklist.py                        # 黑名单检查
│       └── amount_limit.py                     # 限额规则
│
├── notification-service/           # 通知服务 (Go)
│   ├── Dockerfile
│   ├── go.mod
│   ├── main.go
│   ├── handler/
│   │   └── webhook.go                          # Webhook 回调
│   └── consumer/
│       └── kafka_consumer.go                   # 消费 Kafka 事件
│
└── sql/                            # 数据库初始化脚本(容器启动时自动执行)
    ├── 001_create_payment_order.sql
    ├── 002_create_account.sql
    ├── 003_create_ledger_entry.sql
    ├── 004_create_refund_order.sql
    └── 005_seed_accounts.sql       # 测试账户种子数据 (acc001-acc004)
```

---

## 8. 关键设计决策

### 8.1 为什么用复式记账?

银行系统的核心是**钱不能多也不能少**。复式记账确保每笔交易都有借方和贷方，金额必须相等：

```
付款方(借) 10000  ──>  收款方(贷) 10000
```

任何时刻 `SUM(debit) == SUM(credit)`，否则系统报警。

### 8.2 为什么先冻结再转账?

```
冻结模式: 可用余额 ─(冻结)─> 冻结余额 ─(确认)─> 对方可用余额
直接扣款: 可用余额 ─(扣款)─> 对方可用余额 (失败时难以回滚)
```

冻结模式提供了**两阶段提交**的语义，即使下游失败也能安全解冻回滚。

### 8.3 金额为什么用 BIGINT(分)?

- 浮点数有精度问题: `0.1 + 0.2 ≠ 0.3`
- 用最小货币单位(分)的整数表示，完全避免精度丢失
- 展示层再除以 100 转为元

### 8.4 高可用保障

```
┌─────────────────────────────────────────┐
│           高可用策略                      │
├─────────────────────────────────────────┤
│ 服务层: K8s 多副本 + HPA 自动扩缩容       │
│ 数据层: PG 主从 + 自动 failover           │
│ 缓存层: Redis Cluster 6 节点             │
│ 消息层: Kafka 3 Broker, 副本因子=3       │
│ 网关层: Nginx 多实例 + Keepalived VIP    │
│ 容灾:  多 AZ 部署, RPO=0, RTO<30s       │
└─────────────────────────────────────────┘
```

---

## 9. 本地启动指南

### 9.1 环境要求

| 工具 | 最低版本 | 用途 |
|------|---------|------|
| Docker & Docker Compose | 24+ / 2.20+ | 基础设施 (PG, Redis, Kafka) |
| Java (JDK) | 21+ | payment / account / ledger 服务 |
| Maven | 3.9+ | Java 项目构建 |
| Node.js | 20+ | 前端构建 |
| Python | 3.12+ | 风控服务 |
| Go | 1.22+ | 通知服务 |

### 9.2 一键启动（推荐）

```bash
# 1. 克隆项目
git clone https://github.com/larryaus/payflow-engine.git
cd payflow-engine

# 2. 启动基础设施 (PostgreSQL + Redis + Kafka)
docker-compose up -d postgres redis kafka

# 3. 等待基础设施就绪 (约 10-15 秒)
echo "Waiting for infrastructure..."
sleep 15

# 4. 验证基础设施状态
docker-compose ps
```

### 9.3 逐个启动各服务

#### 终端 1 — 风控服务 (Python)

```bash
cd risk-service
python -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8084 --reload
```

验证: `curl http://localhost:8084/health`

#### 终端 2 — 账户服务 (Java)

```bash
cd account-service
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8082"
```

#### 终端 3 — 记账服务 (Java)

```bash
cd ledger-service
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8083"
```

#### 终端 4 — 支付服务 (Java)

```bash
cd payment-service
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
```

#### 终端 5 — 通知服务 (Go)

```bash
cd notification-service
go run main.go
```

验证: `curl http://localhost:8085/health`

#### 终端 6 — API 网关 (Java)

```bash
cd gateway-service
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"
```

验证: `curl http://localhost:8080/actuator/health`

#### 终端 7 — 前端

```bash
cd frontend
npm install
npm run dev
```

打开浏览器访问: **http://localhost:3000**

### 9.4 使用 Docker Compose 全量启动

```bash
# 一键启动所有服务 + 基础设施
docker-compose up -d

# 查看所有服务状态
docker-compose ps

# 查看某个服务日志
docker-compose logs -f payment-service

# 停止所有服务
docker-compose down

# 停止并清除数据卷 (完全重置)
docker-compose down -v
```

### 9.5 服务端口总览

启动完成后，各服务端口如下:

```
┌──────────────────────────────────┬────────┬──────────────────────────┐
│ 服务                              │ 端口   │ 地址                      │
├──────────────────────────────────┼────────┼──────────────────────────┤
│ Frontend (Nginx + React SPA)     │ 3000   │ http://localhost:3000     │
│ Gateway Service (API 网关)        │ 8080   │ http://localhost:8080     │
│ Payment Service                  │ 8081   │ http://localhost:8081     │
│ Account Service                  │ 8082   │ http://localhost:8082     │
│ Ledger Service                   │ 8083   │ http://localhost:8083     │
│ Risk Service                     │ 8084   │ http://localhost:8084     │
│ Notification Service             │ 8085   │ http://localhost:8085     │
├──────────────────────────────────┼────────┼──────────────────────────┤
│ PostgreSQL                       │ 5432   │ localhost:5432            │
│ Redis                            │ 6379   │ localhost:6379            │
│ Kafka                            │ 9092   │ localhost:9092            │
└──────────────────────────────────┴────────┴──────────────────────────┘
```

### 9.6 快速验证

```bash
# 1. 健康检查
curl http://localhost:8084/health        # Risk Service
curl http://localhost:8085/health        # Notification Service

# 2. 通过网关创建支付订单
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <jwt-token>" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "from_account": "ACC_001",
    "to_account": "ACC_002",
    "amount": 10000,
    "currency": "CNY",
    "payment_method": "BANK_TRANSFER",
    "memo": "测试支付"
  }'

# 3. 查询支付状态
curl -H "Authorization: Bearer <jwt-token>" \
  http://localhost:8080/api/v1/payments/PAY_XXXXXXXX_XXXXXX

# 4. 查询账户列表
curl -H "Authorization: Bearer <jwt-token>" \
  http://localhost:8080/api/v1/accounts

# 5. 查询账户余额
curl -H "Authorization: Bearer <jwt-token>" \
  http://localhost:8080/api/v1/accounts/ACC_001/balance

# 6. 发起退款
curl -X POST http://localhost:8080/api/v1/payments/PAY_XXXXXXXX_XXXXXX/refund \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <jwt-token>" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "amount": 5000,
    "reason": "测试退款"
  }'

# 提示: 开发环境可设置 JWT_ENABLED=false 跳过认证
```

### 9.7 数据库连接

```bash
# 连接 PostgreSQL
psql -h localhost -U payflow -d payflow
# 密码: payflow_secret

# 查看已创建的表
\dt

# 连接 Redis
redis-cli -h localhost -p 6379
```

### 9.8 常见问题

| 问题 | 解决方案 |
|------|---------|
| 端口被占用 | `lsof -i :PORT` 找到进程并 kill，或修改配置中的端口 |
| Kafka 启动失败 | 确保 Docker 分配了足够内存 (建议 >= 4GB) |
| Java 服务连不上 PG | 确认 PostgreSQL 已完全启动: `docker-compose logs postgres` |
| 前端 API 请求 404 | 确认 vite proxy 指向 Gateway Service (8080)，或直连后端服务 |
| Maven 下载慢 | 配置 `~/.m2/settings.xml` 使用阿里云镜像源 |
