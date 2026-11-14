package com.payflow.payment.repository;

import com.payflow.payment.domain.RefundOrder;
import com.payflow.payment.domain.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefundOrderRepository extends JpaRepository<RefundOrder, Long> {

    Optional<RefundOrder> findByRefundId(String refundId);

    Optional<RefundOrder> findByIdempotencyKey(String idempotencyKey);

    List<RefundOrder> findByPaymentId(String paymentId);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM RefundOrder r "
            + "WHERE r.paymentId = :paymentId AND r.status IN :statuses")
    long sumAmountByPaymentIdAndStatusIn(@Param("paymentId") String paymentId,
                                         @Param("statuses") Collection<RefundStatus> statuses);
}
