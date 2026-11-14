package com.payflow.payment.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.payflow.payment.domain.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStatusTest {

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(PENDING, REJECTED),
            PENDING, EnumSet.of(PROCESSING, REJECTED, FAILED),
            PROCESSING, EnumSet.of(COMPLETED, FAILED),
            COMPLETED, EnumSet.of(REFUNDED),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            REJECTED, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class));

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void canTransitToMatchesTheDocumentedStateMachine(PaymentStatus from) {
        for (PaymentStatus to : PaymentStatus.values()) {
            assertThat(from.canTransitTo(to))
                    .as("%s -> %s", from, to)
                    .isEqualTo(ALLOWED.get(from).contains(to));
        }
    }

    @Test
    void pendingCanFailWhenProcessingGivesUpBeforeFreezing() {
        PaymentOrder order = new PaymentOrder();
        order.setStatus(PENDING);
        order.transitTo(FAILED);
        assertThat(order.getStatus()).isEqualTo(FAILED);
    }

    @Test
    void transitToRejectsIllegalTransitionsAndKeepsStatus() {
        PaymentOrder order = new PaymentOrder();
        order.setStatus(COMPLETED);
        assertThatThrownBy(() -> order.transitTo(FAILED)).isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(COMPLETED);
    }

    @Test
    void completingSetsCompletedAt() {
        PaymentOrder order = new PaymentOrder();
        order.setStatus(PROCESSING);
        order.transitTo(COMPLETED);
        assertThat(order.getCompletedAt()).isNotNull();
    }
}
