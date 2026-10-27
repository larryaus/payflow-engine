package com.payflow.payment.audit;

import com.payflow.payment.config.TraceFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 异步记录审计日志，不阻塞主业务流程
     */
    @Async
    public void log(String action, String resourceType, String resourceId,
                    String detail, String result, String clientIp) {
        try {
            String traceId = MDC.get(TraceFilter.MDC_TRACE_KEY);
            AuditLog entry = new AuditLog(
                    traceId, "payment-service", action,
                    resourceType, resourceId, detail, result, clientIp
            );
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to write audit log: {}", e.getMessage());
        }
    }

    /**
     * 按 traceId 查询审计轨迹（用于链路回溯）
     */
    public List<AuditLog> getByTraceId(String traceId) {
        return auditLogRepository.findByTraceIdOrderByCreatedAtAsc(traceId);
    }

    /**
     * 按资源查询审计轨迹（如某笔支付的完整历史）
     */
    public List<AuditLog> getByResource(String resourceType, String resourceId) {
        return auditLogRepository.findByResourceTypeAndResourceIdOrderByCreatedAtAsc(resourceType, resourceId);
    }

    /**
     * 分页查询审计日志
     */
    public Page<AuditLog> listAll(int page, int size) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(page - 1, size));
    }
}
