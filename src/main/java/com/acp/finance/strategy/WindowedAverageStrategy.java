package com.acp.finance.strategy;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

public class WindowedAverageStrategy implements SentimentStrategy {

    @Override
    public String getName() { return "windowed"; }

    @Override
    public String getDisplayName() { return "Windowed (last 10)"; }

    @Override
    public void update(String ticker, double score, StringRedisTemplate redis) {
        String key = "sentiment:" + ticker + ":window";
        redis.opsForList().leftPush(key, String.valueOf(score));
        redis.opsForList().trim(key, 0, 9);
    }

    @Override
    public double getScore(String ticker, StringRedisTemplate redis) {
        String key = "sentiment:" + ticker + ":window";
        List<String> values = redis.opsForList().range(key, 0, 9);
        if (values == null || values.isEmpty()) return 0.0;
        return values.stream()
                .mapToDouble(Double::parseDouble)
                .average()
                .orElse(0.0);
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
