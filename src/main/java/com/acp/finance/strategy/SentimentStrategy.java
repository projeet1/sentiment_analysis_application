package com.acp.finance.strategy;

import org.springframework.data.redis.core.StringRedisTemplate;

public interface SentimentStrategy {
    String getName();
    String getDisplayName();
    void update(String ticker, double score, StringRedisTemplate redis);
    double getScore(String ticker, StringRedisTemplate redis);
    String getSignal(String ticker, StringRedisTemplate redis,
                     double buyThreshold, double sellThreshold);
}
