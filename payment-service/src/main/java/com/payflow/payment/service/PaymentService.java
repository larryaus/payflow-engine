package com.payflow.payment.service;

import com.payflow.payment.client.AccountClient;
import com.payflow.payment.client.LedgerClient;
import com.payflow.payment.client.RiskClient;
import com.payflow.payment.config.IdempotencyFilter;
import com.payflow.payment.controller.PaymentController.CreatePaymentRequest;
import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.domain.PaymentStatus;
import com.payflow.payment.exception.PaymentException;
import com.payflow.payment.mq.PaymentEventProducer;
import com.payflow.payment.repository.PaymentOrderRepository;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentOrderRepository paymentOrderRepository;
    private final IdempotencyFilter idempotencyFilter;
    private final RiskClient riskClient;
    private final AccountClient accountClient;
    private final LedgerClient ledgerClient;
    private final PaymentEventProducer eventProducer;
    private final RedissonClient redissonClient;

    public PaymentService(
            PaymentOrderRepository paymentOrderRepository,
            IdempotencyFilter idempotencyFilter,
            RiskClient riskClient,
            AccountClient accountClient,
            LedgerClient ledgerClient,
            PaymentEventProducer eventProducer,
            RedissonClient redissonClient) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.idempotencyFilter = idempotencyFilter;
        this.riskClient = riskClient;
        this.accountClient = accountClient;
        this.ledgerClient = ledgerClient;
        this.eventProducer = eventProducer;
        this.redissonClient = redissonClient;
    }

    /**
     * 创建支付订单 — 核心支付流程编排
     *
     * 流程: 幂等检查 → 风控 → 冻结 → 记账 → 转账 → 通知
     */
    @Transactional
    public PaymentOrder createPayment(String idempotencyKey, CreatePaymentRequest request) {
        // 1. 幂等性检查
        if (idempotencyFilter.isDuplicate(idempotencyKey)) {
            return paymentOrderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new PaymentException("IDEMPOTENCY_CONFLICT",
                            "Duplicate request, but original order not found"));
        }

        // 2. 创建订单
        PaymentOrder order = new PaymentOrder();
        order.setPaymentId(generatePaymentId());
        order.setIdempotencyKey(idempotencyKey);
        order.setFromAccount(request.from_account());
        order.setToAccount(request.to_account());
        order.setAmount(request.amount());
        order.setCurrency(request.currency() != null ? request.currency() : "CNY");
        order.setPaymentMethod(request.payment_method());
        order.setMemo(request.memo());
        order.setCallbackUrl(request.callback_url());
        paymentOrderRepository.save(order);

        // 3. 风控检查
        boolean riskPass = riskClient.checkRisk(
                request.from_account(), request.to_account(), request.amount());
        if (!riskPass) {
            order.transitTo(PaymentStatus.REJECTED);
            paymentOrderRepository.save(order);
            throw new PaymentException("RISK_REJECTED", "风控拒绝此交易");
        }
        order.transitTo(PaymentStatus.PENDING);

        // 4. 异步处理转账(冻结 → 记账 → 扣款 → 通知)
        paymentOrderRepository.save(order);
        eventProducer.sendPaymentCreated(order);

        return order;
    }

    /**
     * 异步处理支付 — 由 Kafka Consumer 触发
     */
    public void processPaymentAsync(String paymentId) {
        PaymentOrder order = getPayment(paymentId);
        String lockKey = "account:lock:" + order.getFromAccount();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new PaymentException("ACCOUNT_BUSY", "账户繁忙,请稍后重试");
            }

            // 4a. 冻结付款方金额
            order.transitTo(PaymentStatus.PROCESSING);
            paymentOrderRepository.save(order);
            accountClient.freezeAmount(order.getFromAccount(), order.getAmount());

            // 4b. 创建复式记账分录
            ledgerClient.createEntry(order.getPaymentId(),
                    order.getFromAccount(), order.getToAccount(), order.getAmount());

            // 4c. 解冻并执行实际转账
            accountClient.transfer(order.getFromAccount(), order.getToAccount(), order.getAmount());

            // 4d. 标记完成
            order.transitTo(PaymentStatus.COMPLETED);
            paymentOrderRepository.save(order);

            // 4e. 发布完成事件(触发通知)
            eventProducer.sendPaymentCompleted(order);

            log.info("Payment {} completed successfully", paymentId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handlePaymentFailure(order, "Lock interrupted");
        } catch (Exception e) {
            handlePaymentFailure(order, e.getMessage());
            // 冻结回滚
            accountClient.unfreezeAmount(order.getFromAccount(), order.getAmount());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public PaymentOrder getPayment(String paymentId) {
        return paymentOrderRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new PaymentException("NOT_FOUND", "Payment not found: " + paymentId));
    }

    private void handlePaymentFailure(PaymentOrder order, String reason) {
        log.error("Payment {} failed: {}", order.getPaymentId(), reason);
        order.transitTo(PaymentStatus.FAILED);
        order.setUpdatedAt(Instant.now());
        paymentOrderRepository.save(order);
        eventProducer.sendPaymentFailed(order, reason);
    }

    private String generatePaymentId() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return "PAY_" + date + "_" + suffix;
    }
}
