package com.payflow.payment.audit;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 分页查询审计日志
     */
    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> listLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AuditLog> result = auditService.listAll(page, size);
        return ResponseEntity.ok(Map.of(
                "data", result.getContent(),
                "total", result.getTotalElements()
        ));
    }

    /**
     * 按 traceId 查询链路审计轨迹
     */
    @GetMapping("/trace/{traceId}")
    public ResponseEntity<List<AuditLog>> getByTrace(@PathVariable String traceId) {
        return ResponseEntity.ok(auditService.getByTraceId(traceId));
    }

    /**
     * 按资源查询审计轨迹（如 ?resource_type=PAYMENT&resource_id=xxx）
     */
    @GetMapping("/resource")
    public ResponseEntity<List<AuditLog>> getByResource(
            @RequestParam("resource_type") String resourceType,
            @RequestParam("resource_id") String resourceId) {
        return ResponseEntity.ok(auditService.getByResource(resourceType, resourceId));
    }
}
