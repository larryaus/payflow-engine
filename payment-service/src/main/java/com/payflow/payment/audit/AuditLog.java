package com.payflow.payment.audit;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(nullable = false)
    private String action;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    private String detail;

    @Column(nullable = false)
    private String result = "SUCCESS";

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public AuditLog() {}

    public AuditLog(String traceId, String serviceName, String action,
                    String resourceType, String resourceId, String detail,
                    String result, String clientIp) {
        this.traceId = traceId;
        this.serviceName = serviceName;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.detail = detail;
        this.result = result;
        this.clientIp = clientIp;
    }

    // Getters
    public Long getId() { return id; }
    public String getTraceId() { return traceId; }
    public String getServiceName() { return serviceName; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getDetail() { return detail; }
    public String getResult() { return result; }
    public String getClientIp() { return clientIp; }
    public Instant getCreatedAt() { return createdAt; }
}
