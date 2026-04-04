package com.acp.finance.service;

import com.acp.finance.config.AppProperties;
import com.acp.finance.model.SentimentResult;
import com.acp.finance.model.TradeSignal;
import com.acp.finance.strategy.EnsembleService;
import com.acp.finance.strategy.SentimentStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
public class AggregatorService {

    private static final Logger log = LoggerFactory.getLogger(AggregatorService.class);

    private final AppProperties props;
    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbitTemplate;
    private final DynamoDbClient dynamoDbClient;
    private final EnsembleService ensembleService;
    private final List<SentimentStrategy> strategies;
    private final ObjectMapper mapper = new ObjectMapper();

    // In-memory state for dashboard (no restart persistence needed for demo)
    private final Map<String, EnsembleService.EnsembleResult> latestEnsembleResults = new ConcurrentHashMap<>();
    private final List<TradeSignal> recentSignals = new CopyOnWriteArrayList<>();

    public AggregatorService(AppProperties props,
                             StringRedisTemplate redis,
                             RabbitTemplate rabbitTemplate,
                             DynamoDbClient dynamoDbClient,
                             EnsembleService ensembleService,
                             List<SentimentStrategy> strategies) {
        this.props = props;
        this.redis = redis;
        this.rabbitTemplate = rabbitTemplate;
        this.dynamoDbClient = dynamoDbClient;
        this.ensembleService = ensembleService;
        this.strategies = strategies;
    }

    @KafkaListener(topics = "${app.kafka.topic.sentiment:news-sentiment}", groupId = "aggregator")
    public void aggregate(String message) {
        try {
            SentimentResult result = mapper.readValue(message, SentimentResult.class);
            String ticker = result.getTicker();

            // ADDITION 2 — skip strategy updates for deduped articles
            if (result.isDeduped()) {
                log.info("[Aggregator] Skipping deduped article for {}: {}",
                    ticker, result.getHeadline());

                // Still store in recent articles for dashboard visibility
                try {
                    String recentKey = "recent:" + ticker;
                    redis.opsForList().leftPush(recentKey,
                        mapper.writeValueAsString(result));
                    redis.opsForList().trim(recentKey, 0, 4);
                } catch (Exception e) {
                    log.error("[Aggregator] Error storing deduped recent article: {}",
                        e.getMessage());
                }
                return;
            }

            // --- Update all strategies with the new score ---
            for (SentimentStrategy strategy : strategies) {
                strategy.update(ticker, result.getScore(), redis);
            }

            // --- Run ensemble evaluation ---
            EnsembleService.EnsembleResult ensemble = ensembleService.evaluate(
                    ticker, redis, props.buyThreshold, props.sellThreshold);

            // Store latest ensemble result in memory for dashboard
            latestEnsembleResults.put(ticker, ensemble);

            // ADDITION 1 — history snapshotting
            try {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("timestamp", java.time.Instant.now().toString());
                snapshot.put("ensembleScore", ensemble.ensembleScore());
                snapshot.put("ensembleSignal", ensemble.ensembleSignal());
                snapshot.put("buyVotes", ensemble.buyVotes());
                snapshot.put("sellVotes", ensemble.sellVotes());
                snapshot.put("holdVotes", ensemble.holdVotes());
                snapshot.put("eventType", result.getEventType());
                snapshot.put("isBreaking", result.isBreaking());
                snapshot.put("confidence", result.getConfidence());
                snapshot.put("deduped", result.isDeduped());

                String historyKey = "history:" + ticker + ":snapshots";
                redis.opsForList().leftPush(historyKey,
                    mapper.writeValueAsString(snapshot));
                redis.opsForList().trim(historyKey, 0, 99);
            } catch (Exception e) {
                log.error("[Aggregator] Error writing snapshot for {}: {}",
                    ticker, e.getMessage());
            }

            // --- Store recent article in Redis list ---
            String recentKey = "recent:" + ticker;
            redis.opsForList().leftPush(recentKey, mapper.writeValueAsString(result));
            redis.opsForList().trim(recentKey, 0, 4);

            // Log all strategy scores
            log.info("[Aggregator] {} | rolling={} | ema={} | windowed={} | momentum={} | ensemble={}",
                    ticker,
                    String.format("%.3f", ensemble.strategies().get("rolling").score()),
                    String.format("%.3f", ensemble.strategies().get("ema").score()),
                    String.format("%.3f", ensemble.strategies().get("windowed").score()),
                    String.format("%.3f", ensemble.strategies().get("momentum").score()),
                    ensemble.ensembleSignal());

            // --- Fire signal based on ensemble recommendation ---
            String lastSignalKey = "sentiment:" + ticker + ":last_signal";
            String lastSignal    = redis.opsForValue().get(lastSignalKey);
            String ensembleSignal = ensemble.ensembleSignal();

            boolean shouldFire = false;
            String  signalType = null;

            if ((ensembleSignal.equals("STRONG BUY") || ensembleSignal.equals("BUY"))
                    && !"BUY".equals(lastSignal)) {
                shouldFire = true;
                signalType = "BUY";
            } else if ((ensembleSignal.equals("STRONG SELL") || ensembleSignal.equals("SELL"))
                    && !"SELL".equals(lastSignal)) {
                shouldFire = true;
                signalType = "SELL";
            }

            if (shouldFire) {
                fireSignal(ticker, signalType, ensemble.ensembleScore(),
                        result.getHeadline(), result.getReasoning(), ensembleSignal,
                        result.getEventType(), result.getConfidence(),
                        result.isBreaking(), result.getHorizon());
                redis.opsForValue().set(lastSignalKey, signalType);
            }

        } catch (Exception e) {
            log.error("[Aggregator] Error: {}", e.getMessage());
        }
    }

    private void fireSignal(String ticker, String type, double score,
                            String headline, String reasoning, String ensembleSignal,
                            String eventType, double confidence,
                            boolean isBreaking, String horizon) {
        try {
            TradeSignal signal = new TradeSignal();
            signal.setId(UUID.randomUUID().toString());
            signal.setTicker(ticker);
            signal.setSignalType(type);
            signal.setSentimentScore(score);
            signal.setTriggeringHeadline(headline);
            signal.setReasoning(reasoning != null ? reasoning : "");
            signal.setTimestamp(Instant.now().toString());

            String json = mapper.writeValueAsString(signal);

            // Publish to appropriate RabbitMQ queue
            String queue = "BUY".equals(type) ? props.rabbitQueueBuy : props.rabbitQueueSell;
            rabbitTemplate.convertAndSend(queue, json);

            // Persist to DynamoDB
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("id",             attr(signal.getId()));
            item.put("ticker",         attr(ticker));
            item.put("signalType",     attr(type));
            item.put("sentimentScore", attr(String.format("%.4f", score)));
            item.put("headline",       attr(headline != null ? headline : ""));
            item.put("reasoning",      attr(signal.getReasoning()));
            item.put("timestamp",      attr(signal.getTimestamp()));
            item.put("ensembleSignal", attr(ensembleSignal));
            item.put("eventType",      attr(eventType != null ? eventType : "OTHER"));
            item.put("confidence",     attr(String.format("%.2f", confidence)));
            item.put("isBreaking",     attr(String.valueOf(isBreaking)));
            item.put("horizon",        attr(horizon != null ? horizon : "UNKNOWN"));

            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(props.dynamoDbTable)
                    .item(item)
                    .build());

            // Update in-memory cache for dashboard
            recentSignals.add(0, signal);
            if (recentSignals.size() > 100) recentSignals.remove(recentSignals.size() - 1);

            log.info("[Signal] {} {} | score={} | ensemble={}", type, ticker, score, ensembleSignal);

        } catch (Exception e) {
            log.error("[Aggregator] Error firing signal for {}: {}", ticker, e.getMessage());
        }
    }

    private AttributeValue attr(String value) {
        return AttributeValue.builder().s(value).build();
    }

    // --- Dashboard accessors ---

    public List<SentimentResult> getRecentArticles(String ticker) {
        List<String> raw = redis.opsForList().range("recent:" + ticker, 0, 4);
        if (raw == null || raw.isEmpty()) return List.of();
        return raw.stream()
                .map(s -> {
                    try { return mapper.readValue(s, SentimentResult.class); }
                    catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Map<String, Double> getLatestScores() {
        Map<String, Double> scores = new LinkedHashMap<>();
        latestEnsembleResults.forEach((ticker, ensemble) ->
                scores.put(ticker, ensemble.ensembleScore()));
        return scores;
    }

    public List<TradeSignal> getRecentSignals() {
        return Collections.unmodifiableList(recentSignals);
    }

    public Map<String, EnsembleService.EnsembleResult> getLatestEnsembleResults() {
        return Collections.unmodifiableMap(latestEnsembleResults);
    }

    public Map<String, Object> getTickerStats(String ticker) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("ticker", ticker);

        EnsembleService.EnsembleResult ensemble = latestEnsembleResults.get(ticker);

        if (ensemble != null) {
            stats.put("ensembleSignal", ensemble.ensembleSignal());
            stats.put("ensembleScore",  ensemble.ensembleScore());
            stats.put("buyVotes",       ensemble.buyVotes());
            stats.put("sellVotes",      ensemble.sellVotes());
            stats.put("holdVotes",      ensemble.holdVotes());
            stats.put("strategies",     ensemble.strategies());
        } else {
            stats.put("ensembleSignal", "HOLD");
            stats.put("ensembleScore",  0.0);
            stats.put("buyVotes",       0);
            stats.put("sellVotes",      0);
            stats.put("holdVotes",      0);
            stats.put("strategies",     Map.of());
        }

        String count = redis.opsForValue().get("sentiment:" + ticker + ":rolling:count");
        String last  = redis.opsForValue().get("sentiment:" + ticker + ":last_signal");
        stats.put("articleCount", count != null ? Long.parseLong(count) : 0L);
        stats.put("lastSignal",   last  != null ? last                  : "NONE");
        return stats;
    }
}
