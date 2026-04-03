package com.payflow.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreatePaymentResponse(
        @JsonProperty("payment_id") String paymentId,
        String status,
        @JsonProperty("created_at") String createdAt
) {}
