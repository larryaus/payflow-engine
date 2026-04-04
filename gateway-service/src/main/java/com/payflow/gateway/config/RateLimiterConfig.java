package com.payflow.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String xForwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                return Mono.just(xForwardedFor.split(",")[0].trim());
            }
            String xRealIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
            if (xRealIp != null && !xRealIp.isBlank()) {
                return Mono.just(xRealIp);
            }
            return Mono.just(
                    exchange.getRequest().getRemoteAddress() != null
                            ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                            : "unknown"
            );
        };
    }
}
