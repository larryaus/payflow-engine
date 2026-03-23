package com.payflow.payment.service;

import com.payflow.payment.client.RiskClient;
import com.payflow.payment.config.IdempotencyFilter;
import com.payflow.payment.controller.PaymentController.CreatePaymentRequest;
import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.domain.PaymentStatus;
import com.payflow.payment.exception.PaymentException;
import com.payflow.payment.repository.PaymentOrderRepository;
import com.payflow.payment.workflow.PaymentWorkflow;
import com.payflow.payment.workflow.PaymentWorkflowInput;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentOrderRepository paymentOrderRepository;
    private final IdempotencyFilter idempotencyFilter;
    private final RiskClient riskClient;
    private final WorkflowClient workflowClient;

    @Value("${temporal.task-queue}")
    private String taskQueue;

    public PaymentService(
            PaymentOrderRepository paymentOrderRepository,
            IdempotencyFilter idempotencyFilter,
            RiskClient riskClient,
            WorkflowClient workflowClient) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.idempotencyFilter = idempotencyFilter;
        this.riskClient = riskClient;
        this.workflowClient = workflowClient;
    }

    private static final int DUPLICATE_MAX_RETRIES = 5;
    private static final long DUPLICATE_RETRY_INTERVAL_MS = 200;

    /**
     * 创建支付订单 — 核心支付流程编排
     *
     * 流程: 幂等占位(原子) → 创建订单 → 标记完成 → 风控 → 异步处理
     *
     * 幂等保证: Redis SETNX 原子占位，订单持久化后回写 paymentId。
     * 重复请求通过 paymentId 直接查询已有订单，若原始请求仍在处理中则短暂轮询等待。
     */
    @Transactional
    public PaymentOrder createPayment(String idempotencyKey, CreatePaymentRequest request) {
        // 1. 原子性幂等占位
        if (!idempotencyFilter.tryAcquire(idempotencyKey)) {
            // 重复请求 — 等待原始请求完成并返回已有订单
            return waitForExistingOrder(idempotencyKey);
        }

        try {
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

            // 3. 订单已持久化，回写 paymentId 到 Redis（解除重复请求的等待）
            idempotencyFilter.markCompleted(idempotencyKey, order.getPaymentId());

            // 4. 风控检查
            boolean riskPass = riskClient.checkRisk(
                    request.from_account(), request.to_account(), request.amount());
            if (!riskPass) {
                order.transitTo(PaymentStatus.REJECTED);
                paymentOrderRepository.save(order);
                throw new PaymentException("RISK_REJECTED", "风控拒绝此交易");
            }
            order.transitTo(PaymentStatus.PENDING);

            // 5. 启动 Temporal Workflow 异步处理(冻结 → 记账 → 扣款 → 通知)
            paymentOrderRepository.save(order);

            PaymentWorkflowInput workflowInput = new PaymentWorkflowInput(
                    order.getPaymentId(),
                    order.getFromAccount(),
                    order.getToAccount(),
                    order.getAmount(),
                    order.getCallbackUrl()
            );
            WorkflowOptions options = WorkflowOptions.newBuilder()
                    .setWorkflowId("payment-" + order.getPaymentId())
                    .setTaskQueue(taskQueue)
                    .build();
            PaymentWorkflow workflow = workflowClient.newWorkflowStub(PaymentWorkflow.class, options);
            WorkflowClient.start(workflow::processPayment, workflowInput);

            return order;
        } catch (Exception e) {
            // 占位成功但处理失败时，回写失败标记以便重复请求能快速感知
            // 保留 Redis key（防止 24h 内重复提交相同请求）
            log.error("Payment creation failed for idempotencyKey={}: {}", idempotencyKey, e.getMessage());
            throw e;
        }
    }

    /**
     * 重复请求等待原始订单完成 — 短暂轮询 Redis 直到 paymentId 可用
     */
    private PaymentOrder waitForExistingOrder(String idempotencyKey) {
        for (int i = 0; i < DUPLICATE_MAX_RETRIES; i++) {
            var existingId = idempotencyFilter.getExistingPaymentId(idempotencyKey);
            if (existingId.isPresent()) {
                return paymentOrderRepository.findByPaymentId(existingId.get())
                        .orElseThrow(() -> new PaymentException("IDEMPOTENCY_CONFLICT",
                                "Duplicate request, but original order not found"));
            }
            try {
                Thread.sleep(DUPLICATE_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PaymentException("IDEMPOTENCY_CONFLICT", "Interrupted while waiting for original order");
            }
        }
        throw new PaymentException("IDEMPOTENCY_CONFLICT",
                "Duplicate request, original order still processing. Please retry later.");
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

    private String generatePaymentId() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return "PAY_" + date + "_" + suffix;
    }
}
