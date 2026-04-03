package com.payflow.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaymentResponse(
        @JsonProperty("payment_id") String paymentId,
        String status,
        @JsonProperty("from_account") String fromAccount,
        @JsonProperty("to_account") String toAccount,
        Long amount,
        String currency,
        @JsonProperty("created_at") String createdAt
) {}
