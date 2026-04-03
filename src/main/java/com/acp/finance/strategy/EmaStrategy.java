package com.acp.finance.strategy;

import org.springframework.data.redis.core.StringRedisTemplate;

public class EmaStrategy implements SentimentStrategy {

    @Override
    public String getName() { return "ema"; }

    @Override
    public String getDisplayName() { return "EMA (\u03b1=0.3)"; }

    @Override
    public void update(String ticker, double score, StringRedisTemplate redis) {
        String key      = "sentiment:" + ticker + ":ema:value";
        String existing = redis.opsForValue().get(key);
        double prev     = existing != null ? Double.parseDouble(existing) : score;
        double ema      = (score * 0.3) + (prev * 0.7);
        redis.opsForValue().set(key, String.format("%.6f", ema));
    }

    @Override
    public double getScore(String ticker, StringRedisTemplate redis) {
        String key   = "sentiment:" + ticker + ":ema:value";
        String value = redis.opsForValue().get(key);
        return value != null ? Double.parseDouble(value) : 0.0;
    }

    @Override
    public String getSignal(String ticker, StringRedisTemplate redis,
                            double buyThreshold, double sellThreshold) {
        double score = getScore(ticker, redis);
        if (score >= buyThreshold)  return "BUY";
        if (score <= sellThreshold) return "SELL";
        return "HOLD";
    }
}
