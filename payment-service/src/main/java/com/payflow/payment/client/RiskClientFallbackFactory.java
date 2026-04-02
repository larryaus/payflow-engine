package com.payflow.payment.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class RiskClientFallbackFactory implements FallbackFactory<RiskClient> {

    private static final Logger log = LoggerFactory.getLogger(RiskClientFallbackFactory.class);

    @Override
    public RiskClient create(Throwable cause) {
        return (fromAccount, toAccount, amount) -> {
            log.error("Risk service unavailable, rejecting payment as fail-safe. from={} to={} amount={}: {}",
                    fromAccount, toAccount, amount, cause.getMessage());
            // Fail-safe: reject the payment when risk service is down.
            // We cannot safely approve transactions without a risk check.
            return false;
        };
    }
}
