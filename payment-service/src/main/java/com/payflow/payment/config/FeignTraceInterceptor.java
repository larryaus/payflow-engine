package com.payflow.payment.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Feign 调用拦截器 — 自动将当前线程 MDC 中的 traceId 传播到下游服务。
 */
@Component
public class FeignTraceInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get(TraceFilter.MDC_TRACE_KEY);
        if (traceId != null) {
            template.header(TraceFilter.TRACE_HEADER, traceId);
        }
    }
}
