package com.payflow.payment.config;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的幂等性控制
 * 使用 SETNX 确保同一 idempotencyKey 只处理一次
 *
 * 流程:
 *   tryAcquire()   — 原子占位，返回 true 表示首次请求
 *   markCompleted() — 订单持久化后写入 paymentId，供重复请求查询
 *   getExistingPaymentId() — 重复请求通过此方法获取已创建的 paymentId
 */
@Component
public class IdempotencyFilter {

    private static final String KEY_PREFIX = "idempotent:";
    private static final String PROCESSING = "PROCESSING";
    private static final long TTL_HOURS = 24;

    private final RedisTemplate<String, String> redisTemplate;

    public IdempotencyFilter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 原子性地尝试占位
     * @return true 如果成功占位（首次请求），false 如果 key 已存在（重复请求）
     */
    public boolean tryAcquire(String idempotencyKey) {
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + idempotencyKey, PROCESSING, TTL_HOURS, TimeUnit.HOURS);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 订单创建成功后，将 paymentId 写入 Redis，供重复请求直接查询
     */
    public void markCompleted(String idempotencyKey, String paymentId) {
        redisTemplate.opsForValue()
                .set(KEY_PREFIX + idempotencyKey, paymentId, TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * 获取已关联的 paymentId
     * @return 如果仍在处理中（PROCESSING）或 key 不存在则返回 empty
     */
    public Optional<String> getExistingPaymentId(String idempotencyKey) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        if (value == null || PROCESSING.equals(value)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /**
     * 只读检查 key 是否已存在（不修改 Redis 状态）
     * 避免原来 !tryAcquire() 的副作用导致竞态条件
     */
    public boolean isDuplicate(String idempotencyKey) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        return value != null;
    }
}
