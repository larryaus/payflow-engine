package com.payflow.payment.service.pipeline.step;

import com.payflow.payment.config.IdempotencyFilter;
import com.payflow.payment.controller.PaymentController.CreatePaymentRequest;
import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.repository.PaymentOrderRepository;
import com.payflow.payment.service.pipeline.PaymentCreationContext;
import com.payflow.payment.service.pipeline.PaymentCreationStep;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Step 2: 订单持久化
 * 构建 PaymentOrder 实体并保存，随后回写 paymentId 到 Redis 解除重复请求的等待
 */
@Component
public class OrderPersistStep implements PaymentCreationStep {

    private final PaymentOrderRepository paymentOrderRepository;
    private final IdempotencyFilter idempotencyFilter;

    public OrderPersistStep(PaymentOrderRepository paymentOrderRepository,
                            IdempotencyFilter idempotencyFilter) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.idempotencyFilter = idempotencyFilter;
    }

    @Override
    public void execute(PaymentCreationContext ctx) {
        CreatePaymentRequest req = ctx.getRequest();
        PaymentOrder order = new PaymentOrder();
        order.setPaymentId(generatePaymentId());
        order.setIdempotencyKey(ctx.getIdempotencyKey());
        order.setFromAccount(req.from_account());
        order.setToAccount(req.to_account());
        order.setAmount(req.amount());
        order.setCurrency(req.currency() != null ? req.currency() : "CNY");
        order.setPaymentMethod(req.payment_method());
        order.setMemo(req.memo());
        order.setCallbackUrl(req.callback_url());
        paymentOrderRepository.save(order);

        // 订单已持久化，回写 paymentId 解除重复请求的轮询等待
        idempotencyFilter.markCompleted(ctx.getIdempotencyKey(), order.getPaymentId());
        ctx.setOrder(order);
    }

    private String generatePaymentId() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return "PAY_" + date + "_" + suffix;
    }
}
