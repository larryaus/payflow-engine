package com.payflow.payment.config;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的幂等性控制
 * 使用 SETNX 确保同一 idempotencyKey 只处理一次
 */
@Component
public class IdempotencyFilter {

    private final RedisTemplate<String, String> redisTemplate;

    public IdempotencyFilter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 检查请求是否重复
     * @return true 如果是重复请求
     */
    public boolean isDuplicate(String idempotencyKey) {
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent("idempotent:" + idempotencyKey, "1", 24, TimeUnit.HOURS);
        return !Boolean.TRUE.equals(result);
    }
}
