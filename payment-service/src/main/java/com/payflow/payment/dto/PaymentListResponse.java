package com.payflow.payment.dto;

import java.util.List;

public record PaymentListResponse(
        List<PaymentResponse> data,
        long total
) {}
