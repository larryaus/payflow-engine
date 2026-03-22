package com.payflow.payment.controller;

import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.service.PaymentService;
import com.payflow.payment.service.RefundService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final RefundService refundService;

    public PaymentController(PaymentService paymentService, RefundService refundService) {
        this.paymentService = paymentService;
        this.refundService = refundService;
    }

    /**
     * 创建支付订单
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreatePaymentRequest request) {

        PaymentOrder order = paymentService.createPayment(idempotencyKey, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "payment_id", order.getPaymentId(),
                "status", order.getStatus().name(),
                "created_at", order.getCreatedAt().toString()
        ));
    }

    /**
     * 查询支付列表（分页）
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listPayments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<PaymentOrder> result = paymentService.listPayments(page, size);
        List<Map<String, Object>> items = result.getContent().stream()
                .map(order -> Map.<String, Object>of(
                        "payment_id", order.getPaymentId(),
                        "status", order.getStatus().name(),
                        "from_account", order.getFromAccount(),
                        "to_account", order.getToAccount(),
                        "amount", order.getAmount(),
                        "currency", order.getCurrency(),
                        "created_at", order.getCreatedAt().toString()
                ))
                .toList();

        return ResponseEntity.ok(Map.of(
                "data", items,
                "total", result.getTotalElements()
        ));
    }

    /**
     * 查询支付状态
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<Map<String, Object>> getPayment(@PathVariable String paymentId) {
        PaymentOrder order = paymentService.getPayment(paymentId);

        return ResponseEntity.ok(Map.of(
                "payment_id", order.getPaymentId(),
                "status", order.getStatus().name(),
                "from_account", order.getFromAccount(),
                "to_account", order.getToAccount(),
                "amount", order.getAmount(),
                "currency", order.getCurrency(),
                "created_at", order.getCreatedAt().toString()
        ));
    }

    /**
     * 发起退款
     */
    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<Map<String, Object>> refund(
            @PathVariable String paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RefundRequest request) {

        var refund = refundService.createRefund(paymentId, idempotencyKey, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "refund_id", refund.getRefundId(),
                "payment_id", paymentId,
                "status", refund.getStatus().name(),
                "amount", refund.getAmount()
        ));
    }

    // --- Request DTOs ---

    public record CreatePaymentRequest(
            String from_account,
            String to_account,
            Long amount,
            String currency,
            String payment_method,
            String memo,
            String callback_url
    ) {}

    public record RefundRequest(
            Long amount,
            String reason
    ) {}
}
