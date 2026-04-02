package com.payflow.payment.service;

import com.payflow.payment.client.AccountClient;
import com.payflow.payment.client.LedgerClient;
import com.payflow.payment.controller.PaymentController.CreatePaymentRequest;
import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.domain.PaymentStatus;
import com.payflow.payment.exception.PaymentException;
import com.payflow.payment.mq.PaymentEventProducer;
import com.payflow.payment.repository.PaymentOrderRepository;
import com.payflow.payment.service.pipeline.PaymentCreationPipeline;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentCreationPipeline paymentCreationPipeline;
    private final AccountClient accountClient;
    private final LedgerClient ledgerClient;
    private final PaymentEventProducer eventProducer;
    private final RedissonClient redissonClient;

    public PaymentService(
            PaymentOrderRepository paymentOrderRepository,
            PaymentCreationPipeline paymentCreationPipeline,
            AccountClient accountClient,
            LedgerClient ledgerClient,
            PaymentEventProducer eventProducer,
            RedissonClient redissonClient) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentCreationPipeline = paymentCreationPipeline;
        this.accountClient = accountClient;
        this.ledgerClient = ledgerClient;
        this.eventProducer = eventProducer;
        this.redissonClient = redissonClient;
    }

    /**
     * 创建支付订单 — 委托给 PaymentCreationPipeline 执行
     *
     * 流水线顺序: 幂等占位 → 订单持久化 → 风控检查 → 事件发布
     */
    @Transactional
    public PaymentOrder createPayment(String idempotencyKey, CreatePaymentRequest request) {
        try {
            return paymentCreationPipeline.execute(idempotencyKey, request);
        } catch (Exception e) {
            log.error("Payment creation failed for idempotencyKey={}: {}", idempotencyKey, e.getMessage());
            throw e;
        }
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

            // 冻结付款方金额
            order.transitTo(PaymentStatus.PROCESSING);
            paymentOrderRepository.save(order);
            accountClient.freezeAmount(order.getFromAccount(), order.getAmount());

            // 创建复式记账分录
            ledgerClient.createEntry(order.getPaymentId(),
                    order.getFromAccount(), order.getToAccount(), order.getAmount());

            // 解冻并执行实际转账
            accountClient.transfer(order.getFromAccount(), order.getToAccount(), order.getAmount());

            // 标记完成
            order.transitTo(PaymentStatus.COMPLETED);
            paymentOrderRepository.save(order);

            // 发布完成事件(触发通知)
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

    public Page<PaymentOrder> listPayments(int page, int size) {
        // page param is 1-based from frontend, Spring Pageable is 0-based
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return paymentOrderRepository.findAll(pageable);
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
}
