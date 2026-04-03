package com.payflow.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RefundResponse(
        @JsonProperty("refund_id") String refundId,
        @JsonProperty("payment_id") String paymentId,
        String status,
        Long amount
) {}
