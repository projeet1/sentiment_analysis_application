package com.acp.finance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AppProperties {

    @Value("${NEWS_API_KEY:}")
    public String newsApiKey;

    @Value("${GEMINI_API_KEY:}")
    public String geminiApiKey;

    @Value("${TICKERS:SPY,QQQ,TLT,GLD,USO}")
    public String tickers;

    @Value("${app.kafka.topic.raw:news-raw}")
    public String kafkaTopicRaw;

    @Value("${app.kafka.topic.sentiment:news-sentiment}")
    public String kafkaTopicSentiment;

    @Value("${BUY_THRESHOLD:0.3}")
    public double buyThreshold;

    @Value("${SELL_THRESHOLD:-0.3}")
    public double sellThreshold;

    @Value("${RABBIT_QUEUE_BUY:buy-signals}")
    public String rabbitQueueBuy;

    @Value("${RABBIT_QUEUE_SELL:sell-signals}")
    public String rabbitQueueSell;

    @Value("${DYNAMODB_TABLE:trade-signals}")
    public String dynamoDbTable;

    @Value("${FINNHUB_API_KEY:}")
    public String finnhubApiKey;

    @Value("${NEWS_SOURCE:template}")
    public String newsSource;

    // ── LLM safety controls ───────────────────────────────────────────────────

    /** Master switch. When false the app never calls Gemini, regardless of key. */
    @Value("${USE_LLM_SENTIMENT:false}")
    public boolean useLlmSentiment;

    /** Hard cap on outbound LLM calls per 60-second window. */
    @Value("${MAX_LLM_CALLS_PER_MINUTE:8}")
    public int maxLlmCallsPerMinute;

    /**
     * When true, any 401 / 403 / 429 or quota-style error permanently disables
     * LLM for the lifetime of the process (fail-closed circuit breaker).
     */
    @Value("${LLM_DISABLE_ON_ERROR:true}")
    public boolean llmDisableOnError;

    // ── Poll volume controls ──────────────────────────────────────────────────

    /** Articles fetched per ticker per poll cycle. */
    @Value("${POLL_ARTICLES_PER_TICKER:1}")
    public int pollArticlesPerTicker;
}
