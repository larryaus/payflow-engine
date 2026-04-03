package com.payflow.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RefundDetailResponse(
        @JsonProperty("refund_id") String refundId,
        @JsonProperty("payment_id") String paymentId,
        Long amount,
        String reason,
        String status,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("completed_at") String completedAt
) {}
