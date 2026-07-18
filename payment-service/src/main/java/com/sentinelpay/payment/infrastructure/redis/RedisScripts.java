package com.sentinelpay.payment.infrastructure.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;

final class RedisScripts {

    /** Atomically increment a hash field and set TTL on first write. ARGV[1]=field, ARGV[2]=ttlSeconds */
    static final DefaultRedisScript<Long> HINCR_WITH_EXPIRE = new DefaultRedisScript<>(
            """
                    redis.call('HINCRBY', KEYS[1], ARGV[1], 1)
                    if redis.call('TTL', KEYS[1]) < 0 then
                      redis.call('EXPIRE', KEYS[1], ARGV[2])
                    end
                    return 1
                    """,
            Long.class);

    private RedisScripts() {
    }
}
