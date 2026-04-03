package com.acp.finance.strategy;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EnsembleService {

    private final List<SentimentStrategy> strategies;

    public EnsembleService(List<SentimentStrategy> strategies) {
        this.strategies = strategies;
    }

    public record StrategyResult(String displayName, double score, String signal) {}

    public record EnsembleResult(
            Map<String, StrategyResult> strategies,
            String ensembleSignal,
            double ensembleScore,
            int buyVotes,
            int sellVotes,
            int holdVotes
    ) {}

    public EnsembleResult evaluate(String ticker, StringRedisTemplate redis,
                                   double buyThreshold, double sellThreshold) {
        Map<String, StrategyResult> results = new LinkedHashMap<>();
        int buyVotes = 0, sellVotes = 0, holdVotes = 0;

        for (SentimentStrategy strategy : strategies) {
            double score  = strategy.getScore(ticker, redis);
            String signal = strategy.getSignal(ticker, redis, buyThreshold, sellThreshold);
            results.put(strategy.getName(), new StrategyResult(strategy.getDisplayName(), score, signal));
            switch (signal) {
                case "BUY"  -> buyVotes++;
                case "SELL" -> sellVotes++;
                default     -> holdVotes++;
            }
        }

        String ensembleSignal;
        if      (buyVotes  >= 3) ensembleSignal = "STRONG BUY";
        else if (buyVotes  == 2) ensembleSignal = "BUY";
        else if (sellVotes >= 3) ensembleSignal = "STRONG SELL";
        else if (sellVotes == 2) ensembleSignal = "SELL";
        else                     ensembleSignal = "HOLD";

        double rolling  = results.containsKey("rolling")  ? results.get("rolling").score()  : 0.0;
        double ema      = results.containsKey("ema")       ? results.get("ema").score()       : 0.0;
        double windowed = results.containsKey("windowed")  ? results.get("windowed").score()  : 0.0;
        double momentum = results.containsKey("momentum")  ? results.get("momentum").score()  : 0.0;

        double ensembleScore = (rolling * 0.15) + (ema * 0.35) + (windowed * 0.30) + (momentum * 0.20);

        return new EnsembleResult(results, ensembleSignal, ensembleScore,
                buyVotes, sellVotes, holdVotes);
    }
}
