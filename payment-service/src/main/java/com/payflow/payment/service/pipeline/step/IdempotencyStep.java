package com.payflow.payment.service.pipeline.step;

import com.payflow.payment.config.IdempotencyFilter;
import com.payflow.payment.exception.PaymentException;
import com.payflow.payment.repository.PaymentOrderRepository;
import com.payflow.payment.service.pipeline.PaymentCreationContext;
import com.payflow.payment.service.pipeline.PaymentCreationStep;
import org.springframework.stereotype.Component;

/**
 * Step 1: 幂等检查
 * - 首次请求: 原子占位，继续后续 Step
 * - 重复请求: 轮询等待原始订单完成，短路流水线直接返回
 */
@Component
public class IdempotencyStep implements PaymentCreationStep {

    private static final int MAX_RETRIES = 5;
    private static final long RETRY_INTERVAL_MS = 200;

    private final IdempotencyFilter idempotencyFilter;
    private final PaymentOrderRepository paymentOrderRepository;

    public IdempotencyStep(IdempotencyFilter idempotencyFilter,
                           PaymentOrderRepository paymentOrderRepository) {
        this.idempotencyFilter = idempotencyFilter;
        this.paymentOrderRepository = paymentOrderRepository;
    }

    @Override
    public void execute(PaymentCreationContext ctx) {
        if (!idempotencyFilter.tryAcquire(ctx.getIdempotencyKey())) {
            ctx.setOrder(waitForExistingOrder(ctx.getIdempotencyKey()));
            ctx.shortCircuit();
        }
    }

    private com.payflow.payment.domain.PaymentOrder waitForExistingOrder(String idempotencyKey) {
        for (int i = 0; i < MAX_RETRIES; i++) {
            var existingId = idempotencyFilter.getExistingPaymentId(idempotencyKey);
            if (existingId.isPresent()) {
                return paymentOrderRepository.findByPaymentId(existingId.get())
                        .orElseThrow(() -> new PaymentException("IDEMPOTENCY_CONFLICT",
                                "Duplicate request, but original order not found"));
            }
            try {
                Thread.sleep(RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PaymentException("IDEMPOTENCY_CONFLICT",
                        "Interrupted while waiting for original order");
            }
        }
        throw new PaymentException("IDEMPOTENCY_CONFLICT",
                "Duplicate request, original order still processing. Please retry later.");
    }
}
