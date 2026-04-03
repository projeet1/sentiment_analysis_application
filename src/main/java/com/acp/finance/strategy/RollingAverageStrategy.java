package com.acp.finance.strategy;

import org.springframework.data.redis.core.StringRedisTemplate;

public class RollingAverageStrategy implements SentimentStrategy {

    @Override
    public String getName() { return "rolling"; }

    @Override
    public String getDisplayName() { return "Rolling Average"; }

    @Override
    public void update(String ticker, double score, StringRedisTemplate redis) {
        String sumKey = "sentiment:" + ticker + ":rolling:sum";
        String cntKey = "sentiment:" + ticker + ":rolling:count";
        String sumStr = redis.opsForValue().get(sumKey);
        String cntStr = redis.opsForValue().get(cntKey);
        double sum   = sumStr != null ? Double.parseDouble(sumStr) : 0.0;
        long   count = cntStr != null ? Long.parseLong(cntStr)     : 0L;
        sum   += score;
        count += 1;
        redis.opsForValue().set(sumKey, String.valueOf(sum));
        redis.opsForValue().set(cntKey, String.valueOf(count));
    }

    @Override
    public double getScore(String ticker, StringRedisTemplate redis) {
        String sumKey = "sentiment:" + ticker + ":rolling:sum";
        String cntKey = "sentiment:" + ticker + ":rolling:count";
        String sumStr = redis.opsForValue().get(sumKey);
        String cntStr = redis.opsForValue().get(cntKey);
        if (sumStr == null || cntStr == null) return 0.0;
        long count = Long.parseLong(cntStr);
        if (count == 0) return 0.0;
        return Double.parseDouble(sumStr) / count;
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
