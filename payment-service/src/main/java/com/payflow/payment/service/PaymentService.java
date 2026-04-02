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

    /**
     * 创建支付订单并启动 Temporal Workflow 异步处理
     *
     * 流程: 幂等占位(Filter) → 订单持久化 → 风控检查 → 启动 Temporal Workflow
     */
    @Transactional
    public PaymentOrder createPayment(String idempotencyKey, CreatePaymentRequest request) {
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
            paymentOrderRepository.save(order);

            // 5. 启动 Temporal Workflow 异步处理(冻结 → 记账 → 转账 → 通知)
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
            log.error("Payment creation failed for idempotencyKey={}: {}", idempotencyKey, e.getMessage());
            throw e;
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

    private String generatePaymentId() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return "PAY_" + date + "_" + suffix;
    }
}
