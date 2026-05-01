package com.payflow.payment.client;

import feign.FeignException;

/**
 * Shared fallback decision for Feign clients.
 *
 * Business errors (4xx) from upstream services must propagate unchanged so
 * callers see the real reason — they are NOT service-unavailability events.
 * Only 5xx, network errors, and Resilience4j circuit-open should be coerced
 * into SERVICE_UNAVAILABLE.
 */
final class FallbackPolicy {

    private FallbackPolicy() {}

    static void rethrowIfClientError(Throwable cause) {
        if (cause instanceof FeignException fe && fe.status() >= 400 && fe.status() < 500) {
            throw fe;
        }
    }
}
