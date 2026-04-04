package com.payflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.jwt")
public record JwtProperties(
        String secret,
        boolean enabled
) {
    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            secret = "payflow-default-secret-key-change-in-production-32chars!";
        }
    }
}
