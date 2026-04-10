package com.acp.finance.strategy;

import org.springframework.data.redis.core.StringRedisTemplate;

public class MomentumStrategy implements SentimentStrategy {

    @Override
    public String getName() { return "momentum"; }

    @Override
    public String getDisplayName() { return "Momentum"; }

    @Override
    public void update(String ticker, double score, StringRedisTemplate redis) {
        String emaKey     = "sentiment:" + ticker + ":ema:value";
        String prevKey    = "sentiment:" + ticker + ":momentum:prev";
        String currentKey = "sentiment:" + ticker + ":momentum:current";

        String emaValue = redis.opsForValue().get(emaKey);
        if (emaValue == null) return;

        String current = redis.opsForValue().get(currentKey);
        if (current != null) {
            redis.opsForValue().set(prevKey, current);
        }
        redis.opsForValue().set(currentKey, emaValue);
    }

    @Override
    public double getScore(String ticker, StringRedisTemplate redis) {
        String prevKey    = "sentiment:" + ticker + ":momentum:prev";
        String currentKey = "sentiment:" + ticker + ":momentum:current";
        String prevStr    = redis.opsForValue().get(prevKey);
        String currentStr = redis.opsForValue().get(currentKey);
        if (prevStr == null || currentStr == null) return 0.0;
        return Double.parseDouble(currentStr) - Double.parseDouble(prevStr);
    }

    @Override
    public String getSignal(String ticker, StringRedisTemplate redis,
                            double buyThreshold, double sellThreshold) {
        double delta = getScore(ticker, redis);
        if (delta >= 0.03)  return "BUY";
        if (delta <= -0.03) return "SELL";
        return "HOLD";
    }
}
