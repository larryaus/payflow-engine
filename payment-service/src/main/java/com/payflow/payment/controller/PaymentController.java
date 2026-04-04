package com.payflow.payment.controller;

import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.dto.CreatePaymentResponse;
import com.payflow.payment.dto.PaymentListResponse;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.dto.RefundDetailResponse;
import com.payflow.payment.dto.RefundResponse;
import com.payflow.payment.service.PaymentService;
import com.payflow.payment.service.RefundService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public ResponseEntity<CreatePaymentResponse> createPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {

        PaymentOrder order = paymentService.createPayment(idempotencyKey, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatePaymentResponse(
                order.getPaymentId(),
                order.getStatus().name(),
                order.getCreatedAt().toString()
        ));
    }

    /**
     * 查询支付列表（分页）
     */
    @GetMapping
    public ResponseEntity<PaymentListResponse> listPayments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 20;

        Page<PaymentOrder> result = paymentService.listPayments(page, size);
        List<PaymentResponse> items = result.getContent().stream()
                .map(PaymentController::toPaymentResponse)
                .toList();

        return ResponseEntity.ok(new PaymentListResponse(items, result.getTotalElements()));
    }

    /**
     * 查询支付状态
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable String paymentId) {
        PaymentOrder order = paymentService.getPayment(paymentId);
        return ResponseEntity.ok(toPaymentResponse(order));
    }

    /**
     * 查询退款列表
     */
    @GetMapping("/{paymentId}/refunds")
    public ResponseEntity<List<RefundDetailResponse>> listRefunds(@PathVariable String paymentId) {
        List<RefundDetailResponse> refunds = refundService.listRefunds(paymentId).stream()
                .map(r -> new RefundDetailResponse(
                        r.getRefundId(),
                        r.getPaymentId(),
                        r.getAmount(),
                        r.getReason(),
                        r.getStatus().name(),
                        r.getCreatedAt().toString(),
                        r.getCompletedAt() != null ? r.getCompletedAt().toString() : null
                ))
                .toList();
        return ResponseEntity.ok(refunds);
    }

    /**
     * 发起退款
     */
    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable String paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RefundRequest request) {

        var refund = refundService.createRefund(paymentId, idempotencyKey, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(new RefundResponse(
                refund.getRefundId(),
                paymentId,
                refund.getStatus().name(),
                refund.getAmount()
        ));
    }

    // --- Request DTOs ---

    public record CreatePaymentRequest(
            @NotBlank String from_account,
            @NotBlank String to_account,
            @NotNull @Min(1) Long amount,
            @NotBlank String currency,
            @NotBlank String payment_method,
            String memo,
            String callback_url
    ) {}

    public record RefundRequest(
            @NotNull @Min(1) Long amount,
            String reason
    ) {}

    // --- Helpers ---

    private static PaymentResponse toPaymentResponse(PaymentOrder order) {
        return new PaymentResponse(
                order.getPaymentId(),
                order.getStatus().name(),
                order.getFromAccount(),
                order.getToAccount(),
                order.getAmount(),
                order.getCurrency(),
                order.getCreatedAt().toString()
        );
    }
}
