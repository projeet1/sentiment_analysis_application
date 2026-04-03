package com.acp.finance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AppProperties {

    @Value("${NEWS_API_KEY:}")
    public String newsApiKey;

    @Value("${ANTHROPIC_API_KEY:}")
    public String anthropicApiKey;

    @Value("${TICKERS:AAPL,TSLA,MSFT,GOOGL}")
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
}
