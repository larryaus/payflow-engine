package com.payflow.payment.repository;

import com.payflow.payment.domain.RefundOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefundOrderRepository extends JpaRepository<RefundOrder, Long> {

    Optional<RefundOrder> findByRefundId(String refundId);

    Optional<RefundOrder> findByIdempotencyKey(String idempotencyKey);
}
