package com.payflow.payment.controller;

import com.payflow.payment.audit.AuditService;
import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.dto.CreatePaymentResponse;
import com.payflow.payment.dto.PaymentListResponse;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.dto.RefundDetailResponse;
import com.payflow.payment.dto.RefundResponse;
import com.payflow.payment.service.PaymentService;
import com.payflow.payment.service.RefundService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final AuditService auditService;

    public PaymentController(PaymentService paymentService, RefundService refundService,
                             AuditService auditService) {
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.auditService = auditService;
    }

    /**
     * 创建支付订单
     */
    @PostMapping
    public ResponseEntity<CreatePaymentResponse> createPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreatePaymentRequest request,
            HttpServletRequest httpRequest) {

        PaymentOrder order = paymentService.createPayment(idempotencyKey, request);

        auditService.log("CREATE_PAYMENT", "PAYMENT", order.getPaymentId(),
                String.format("from=%s to=%s amount=%d currency=%s",
                        request.from_account(), request.to_account(), request.amount(), request.currency()),
                "SUCCESS", httpRequest.getRemoteAddr());

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
            @RequestBody RefundRequest request,
            HttpServletRequest httpRequest) {

        var refund = refundService.createRefund(paymentId, idempotencyKey, request);

        auditService.log("CREATE_REFUND", "REFUND", refund.getRefundId(),
                String.format("paymentId=%s amount=%d reason=%s",
                        paymentId, request.amount(), request.reason()),
                refund.getStatus().name(), httpRequest.getRemoteAddr());

        return ResponseEntity.status(HttpStatus.CREATED).body(new RefundResponse(
                refund.getRefundId(),
                paymentId,
                refund.getStatus().name(),
                refund.getAmount()
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
