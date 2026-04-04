package com.payflow.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_ATTR = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        exchange.getAttributes().put(TRACE_ID_ATTR, traceId);

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(TRACE_ID_HEADER, traceId)
                .build();

        String finalTraceId = traceId;
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .then(Mono.fromRunnable(() ->
                        exchange.getResponse().getHeaders().putIfAbsent(TRACE_ID_HEADER,
                                java.util.List.of(finalTraceId))));
    }

    @Override
    public int getOrder() {
        return -3;
    }
}
