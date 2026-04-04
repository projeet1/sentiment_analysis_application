package com.acp.finance.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class DedupeChecker {

    private static final Logger log =
        LoggerFactory.getLogger(DedupeChecker.class);
    private static final long DEDUPE_TTL_HOURS = 24;

    private final StringRedisTemplate redis;

    public DedupeChecker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isDuplicate(String contentHash) {
        try {
            String key = "dedupe:" + contentHash;
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (Exception e) {
            log.error("[DedupeChecker] Redis error checking hash {}: {}",
                contentHash, e.getMessage());
            return false;
        }
    }

    public void markSeen(String contentHash) {
        try {
            String key = "dedupe:" + contentHash;
            redis.opsForValue().set(key, "1",
                DEDUPE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("[DedupeChecker] Redis error marking hash {}: {}",
                contentHash, e.getMessage());
        }
    }

    public boolean checkAndMark(String contentHash) {
        if (isDuplicate(contentHash)) return true;
        markSeen(contentHash);
        return false;
    }
}
