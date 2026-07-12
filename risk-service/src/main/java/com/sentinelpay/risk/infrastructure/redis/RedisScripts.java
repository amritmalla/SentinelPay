package com.sentinelpay.risk.infrastructure.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Atomic Redis operations. INCR + conditional EXPIRE must be one round-trip to avoid TTL leaks.
 */
final class RedisScripts {

    static final DefaultRedisScript<Long> INCR_WITH_EXPIRE = new DefaultRedisScript<>(
            """
                    local v = redis.call('INCR', KEYS[1])
                    if v == 1 then
                      redis.call('EXPIRE', KEYS[1], ARGV[1])
                    end
                    return v
                    """,
            Long.class);

    private RedisScripts() {
    }
}
