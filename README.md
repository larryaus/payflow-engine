# PayFlow Engine - 银行支付流系统

## 1. 系统架构总览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Client Layer                                   │
│         Web App / Mobile App / Third-Party Partners (SDK/API)               │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │ HTTPS / gRPC
                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           API Gateway (Kong/Nginx)                          │
│   ┌──────────┐  ┌──────────────┐  ┌───────────┐  ┌──────────────────┐      │
│   │ 限流/熔断 │  │ JWT Auth     │  │ 路由分发   │  │ 请求日志/链路追踪 │      │
│   │ Rate Limit│  │ Token验证    │  │ Routing   │  │ Tracing          │      │
│   └──────────┘  └──────────────┘  └───────────┘  └──────────────────┘      │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │
            ┌──────────────────┼──────────────────────┐
            ▼                  ▼                       ▼
┌──────────────────┐ ┌──────────────────┐  ┌──────────────────────┐
│  Payment Service │ │  Account Service │  │  Notification Service│
│  (支付核心服务)    │ │  (账户服务)       │  │  (通知服务)           │
│                  │ │                  │  │                      │
│ - 支付下单       │ │ - 账户余额查询    │  │ - 支付结果通知        │
│ - 支付状态查询   │ │ - 账户冻结/解冻   │  │ - SMS / Email / Push │
│ - 退款处理       │ │ - KYC 验证       │  │ - Webhook 回调       │
│ - 对账           │ │ - 风控检查       │  │                      │
└────────┬─────────┘ └────────┬─────────┘  └──────────────────────┘
         │                    │
         ▼                    ▼
┌──────────────────┐ ┌──────────────────┐
│ Ledger Service   │ │  Risk Service    │
│ (记账/分录服务)   │ │  (风控服务)       │
│                  │ │                  │
│ - 复式记账       │ │ - 规则引擎       │
│ - 事务一致性     │ │ - 黑名单检查     │
│ - 余额计算       │ │ - 异常检测(ML)   │
│ - 日终对账       │ │ - 限额管理       │
└────────┬─────────┘ └──────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Message Queue (Kafka)                               │
│                                                                             │
│  Topics: payment.created | payment.completed | payment.failed               │
│          ledger.entry    | notification.send | risk.alert                    │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │
         ┌─────────────────────┼─────────────────────┐
         ▼                     ▼                      ▼
┌──────────────────┐  ┌──────────────┐  ┌───────────────────┐
│  PostgreSQL      │  │  Redis       │  │  Elasticsearch    │
│  (主数据库)       │  │  (缓存/锁)    │  │  (日志/审计)       │
│                  │  │              │  │                   │
│ - 支付订单表     │  │ - 幂等性控制  │  │ - 交易日志        │
│ - 账户表         │  │ - 分布式锁   │  │ - 审计追踪        │
│ - 分录表         │  │ - 限流计数器  │  │ - 报表查询        │
│ - 对账表         │  │ - Session    │  │                   │
└──────────────────┘  └──────────────┘  └───────────────────┘
```

---

## 2. 微服务拆分

| 服务名 | 职责 | 技术栈 | 端口 |
|--------|------|--------|------|
| `frontend` | 前端 SPA（支付管理、账户查询） | React + TypeScript + Ant Design | 3000 |
| `api-gateway` | 统一入口、限流、鉴权、路由 | Kong / Nginx + Lua | 8080 |
| `payment-service` | 支付核心流程（下单、查询、退款、对账） | Java Spring Boot | 8081 |
| `account-service` | 账户管理、余额查询、冻结解冻 | Java Spring Boot | 8082 |
| `ledger-service` | 复式记账、分录管理、余额一致性 | Java Spring Boot | 8083 |
| `risk-service` | 风控规则引擎、黑名单、限额 | Python FastAPI | 8084 |
| `notification-service` | 消息通知（SMS/Email/Webhook） | Go | 8085 |
| `reconciliation-service` | 日终对账、差异处理 | Java Spring Boot | 8086 |

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

Response 201:
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

Response 201:
{
  "refund_id": "REF_20260322_xyz",
  "payment_id": "PAY_20260322_abcdef",
  "status": "PROCESSING",
  "amount": 5000
}
```

### 3.4 账户余额查询

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
Client                Gateway         PaymentSvc      RiskSvc       AccountSvc      LedgerSvc       Kafka         NotifySvc
  │                     │                │               │              │               │              │              │
  │  POST /payments     │                │               │              │               │              │              │
  │────────────────────>│                │               │              │               │              │              │
  │                     │  幂等性检查     │               │              │               │              │              │
  │                     │  (Redis)       │               │              │               │              │              │
  │                     │───────────────>│               │              │               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │  1.风控检查    │              │               │              │              │
  │                     │                │──────────────>│              │               │              │              │
  │                     │                │  risk: PASS   │              │               │              │              │
  │                     │                │<──────────────│              │               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │  2.冻结付款方金额             │               │              │              │
  │                     │                │─────────────────────────────>│               │              │              │
  │                     │                │  freeze: OK                  │               │              │              │
  │                     │                │<─────────────────────────────│               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │  3.创建分录(复式记账)          │               │              │              │
  │                     │                │─────────────────────────────────────────────>│              │              │
  │                     │                │  ledger: OK                                  │              │              │
  │                     │                │<─────────────────────────────────────────────│              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │  4.解冻 + 扣款/入账           │               │              │              │
  │                     │                │─────────────────────────────>│               │              │              │
  │                     │                │  transfer: OK                │               │              │              │
  │                     │                │<─────────────────────────────│               │              │              │
  │                     │                │               │              │               │              │              │
  │                     │                │  5.发布事件 payment.completed │               │              │              │
  │                     │                │────────────────────────────────────────────────────────────>│              │
  │                     │                │               │              │               │              │              │
  │                     │                │               │              │               │              │  6.通知回调   │
  │                     │                │               │              │               │              │─────────────>│
  │                     │                │               │              │               │              │              │──> Webhook
  │  202 Accepted       │                │               │              │               │              │              │
  │<────────────────────│                │               │              │               │              │              │
  │                     │                │               │              │               │              │              │
```

### 4.1 支付状态机

```
                ┌──────────┐
                │ CREATED  │
                └────┬─────┘
                     │ 风控通过
                     ▼
                ┌──────────┐    风控拒绝    ┌──────────┐
                │ PENDING  │───────────────>│ REJECTED │
                └────┬─────┘               └──────────┘
                     │ 冻结成功
                     ▼
                ┌──────────────┐
                │ PROCESSING   │
                └────┬────┬────┘
          转账成功   │    │ 转账失败
                     ▼    ▼
            ┌───────────┐ ┌──────────┐
            │ COMPLETED │ │  FAILED  │
            └─────┬─────┘ └──────────┘
                  │ 发起退款
                  ▼
            ┌───────────┐
            │ REFUNDED  │ (全额/部分)
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

```
API Gateway 层:
├── 全局限流: 令牌桶算法, 10000 QPS
├── 用户级限流: 滑动窗口, 100 次/分钟
└── IP 级限流: 固定窗口, 1000 次/分钟

Service 层:
├── Sentinel 熔断降级
├── 线程池隔离(每个下游服务独立线程池)
└── 超时控制: 支付链路总超时 30s
```

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
├── payment.created     (Partition by account_id, 保证同账户顺序)
├── payment.completed
├── payment.failed
├── ledger.entry
├── notification.send
└── risk.alert
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
│
├── docker-compose.yml              # 本地开发环境编排
├── docker-compose.infra.yml        # 基础设施(PG, Redis, Kafka, ES)
│
├── frontend/                       # 前端 (React + TypeScript + Ant Design)
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
├── api-gateway/                    # API 网关
│   ├── nginx.conf
│   └── rate-limit.lua              # 限流脚本
│
├── payment-service/                # 支付核心服务 (Java Spring Boot)
│   ├── pom.xml
│   └── src/main/java/com/payflow/payment/
│       ├── PaymentApplication.java
│       ├── controller/
│       │   └── PaymentController.java          # REST API 入口
│       ├── service/
│       │   ├── PaymentService.java             # 支付编排(主流程)
│       │   ├── PaymentStateMachine.java        # 状态机管理
│       │   └── RefundService.java              # 退款逻辑
│       ├── domain/
│       │   ├── PaymentOrder.java               # 支付订单实体
│       │   ├── PaymentStatus.java              # 状态枚举
│       │   └── RefundOrder.java                # 退款实体
│       ├── repository/
│       │   ├── PaymentOrderRepository.java     # 数据访问层
│       │   └── RefundOrderRepository.java
│       ├── client/
│       │   ├── AccountClient.java              # 调用 account-service
│       │   ├── RiskClient.java                 # 调用 risk-service
│       │   └── LedgerClient.java               # 调用 ledger-service
│       ├── mq/
│       │   ├── PaymentEventProducer.java       # Kafka 生产者
│       │   └── PaymentEventConsumer.java       # Kafka 消费者
│       ├── config/
│       │   ├── RedisConfig.java
│       │   ├── KafkaConfig.java
│       │   └── IdempotencyFilter.java          # 幂等性拦截器
│       └── exception/
│           ├── PaymentException.java
│           └── GlobalExceptionHandler.java
│
├── account-service/                # 账户服务 (Java Spring Boot)
│   ├── pom.xml
│   └── src/main/java/com/payflow/account/
│       ├── AccountApplication.java
│       ├── controller/
│       │   └── AccountController.java
│       ├── service/
│       │   ├── AccountService.java             # 余额操作
│       │   └── FreezeService.java              # 冻结/解冻
│       ├── domain/
│       │   └── Account.java
│       └── repository/
│           └── AccountRepository.java
│
├── ledger-service/                 # 记账服务 (Java Spring Boot)
│   ├── pom.xml
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
│   ├── requirements.txt
│   ├── main.py
│   ├── rules/
│   │   ├── rule_engine.py                      # 规则引擎
│   │   ├── blacklist.py                        # 黑名单检查
│   │   └── amount_limit.py                     # 限额规则
│   └── models/
│       └── anomaly_detector.py                 # ML 异常检测
│
├── notification-service/           # 通知服务 (Go)
│   ├── go.mod
│   ├── main.go
│   ├── handler/
│   │   ├── webhook.go                          # Webhook 回调
│   │   ├── sms.go                              # 短信通知
│   │   └── email.go                            # 邮件通知
│   └── consumer/
│       └── kafka_consumer.go                   # 消费 Kafka 事件
│
├── reconciliation-service/         # 对账服务 (Java Spring Boot)
│   ├── pom.xml
│   └── src/main/java/com/payflow/recon/
│       ├── ReconApplication.java
│       └── service/
│           ├── ReconService.java               # 对账逻辑
│           └── DiffHandler.java                # 差异处理
│
├── common/                         # 公共模块
│   ├── proto/                      # gRPC Protobuf 定义
│   │   ├── payment.proto
│   │   ├── account.proto
│   │   └── ledger.proto
│   └── shared/
│       ├── ErrorCode.java                      # 统一错误码
│       ├── ApiResponse.java                    # 统一响应格式
│       └── SnowflakeIdGenerator.java           # 分布式 ID 生成
│
├── deploy/                         # 部署配置
│   ├── k8s/
│   │   ├── payment-deployment.yaml
│   │   ├── account-deployment.yaml
│   │   └── ingress.yaml
│   └── helm/
│       └── payflow-chart/
│
├── sql/                            # 数据库初始化脚本
│   ├── 001_create_payment_order.sql
│   ├── 002_create_account.sql
│   ├── 003_create_ledger_entry.sql
│   └── 004_create_refund_order.sql
│
└── docs/                           # 文档
    ├── api-spec.yaml               # OpenAPI 3.0 规范
    ├── architecture.md             # 架构详细文档
    └── runbook.md                  # 运维手册
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
