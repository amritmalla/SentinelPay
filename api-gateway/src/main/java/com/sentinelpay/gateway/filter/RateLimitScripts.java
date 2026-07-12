package com.sentinelpay.gateway.filter;

import org.springframework.data.redis.core.script.DefaultRedisScript;

final class RateLimitScripts {

    /** Returns 1 when allowed, 0 when over limit. ARGV[1]=maxRequests, ARGV[2]=windowSeconds */
    static final DefaultRedisScript<Long> FIXED_WINDOW_ALLOW = new DefaultRedisScript<>(
            """
                    local v = redis.call('INCR', KEYS[1])
                    if v == 1 then
                      redis.call('EXPIRE', KEYS[1], ARGV[2])
                    end
                    if v > tonumber(ARGV[1]) then
                      return 0
                    end
                    return 1
                    """,
            Long.class);

    private RateLimitScripts() {
    }
}
