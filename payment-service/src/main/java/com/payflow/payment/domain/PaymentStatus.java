package com.payflow.payment.domain;

public enum PaymentStatus {
    CREATED,
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    REJECTED,
    REFUNDED;

    public boolean canTransitTo(PaymentStatus target) {
        return switch (this) {
            case CREATED -> target == PENDING || target == REJECTED;
            // PENDING → FAILED: async processing gave up before freezing anything (e.g. account lock busy)
            case PENDING -> target == PROCESSING || target == REJECTED || target == FAILED;
            case PROCESSING -> target == COMPLETED || target == FAILED;
            case COMPLETED -> target == REFUNDED;
            case FAILED, REJECTED, REFUNDED -> false;
        };
    }
}
