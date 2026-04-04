package com.payflow.payment.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByTraceIdOrderByCreatedAtAsc(String traceId);

    List<AuditLog> findByResourceTypeAndResourceIdOrderByCreatedAtAsc(String resourceType, String resourceId);

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
