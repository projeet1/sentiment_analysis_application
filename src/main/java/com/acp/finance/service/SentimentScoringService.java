package com.acp.finance.service;

import com.acp.finance.config.AppProperties;
import com.acp.finance.model.NewsArticle;
import com.acp.finance.model.SentimentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Map;

@Service
public class SentimentScoringService {

    private static final Logger log = LoggerFactory.getLogger(SentimentScoringService.class);
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    private static final List<String> POSITIVE_KEYWORDS =
            List.of("beat", "surge", "record", "gain", "jump", "profit", "growth", "rises", "strong");
    private static final List<String> NEGATIVE_KEYWORDS =
            List.of("recall", "drop", "loss", "cut", "fall", "miss", "crash", "decline", "weak", "lawsuit");

    @Value("${MOCK_SENTIMENT:false}")
    private boolean mockSentiment;

    private final AppProperties props;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public SentimentScoringService(AppProperties props, KafkaTemplate<String, String> kafkaTemplate) {
        this.props = props;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = "${app.kafka.topic.raw:news-raw}", groupId = "sentiment-scorer")
    public void score(String message) {
        try {
            NewsArticle article = mapper.readValue(message, NewsArticle.class);
            SentimentScore result = mockSentiment
                    ? scoreMock(article.getTitle())
                    : callAnthropicApi(article.getTicker(), article.getTitle());

            SentimentResult sentimentResult = new SentimentResult();
            sentimentResult.setTicker(article.getTicker());
            sentimentResult.setHeadline(article.getTitle());
            sentimentResult.setScore(result.score);
            sentimentResult.setReasoning(result.reasoning);
            sentimentResult.setPublishedAt(article.getPublishedAt());

            String json = mapper.writeValueAsString(sentimentResult);
            kafkaTemplate.send(props.kafkaTopicSentiment, article.getTicker(), json);

            log.info("[Scorer] {} | score={} | {}", article.getTicker(), result.score, article.getTitle());

        } catch (Exception e) {
            log.error("[Scorer] Error scoring message: {}", e.getMessage());
        }
    }

    private SentimentScore scoreMock(String headline) {
        String lower = headline.toLowerCase();
        if (POSITIVE_KEYWORDS.stream().anyMatch(lower::contains)) {
            return new SentimentScore(0.75, "mock positive");
        }
        if (NEGATIVE_KEYWORDS.stream().anyMatch(lower::contains)) {
            return new SentimentScore(-0.65, "mock negative");
        }
        return new SentimentScore(0.05, "mock neutral");
    }

    private SentimentScore callAnthropicApi(String ticker, String headline) {
        if (props.anthropicApiKey == null || props.anthropicApiKey.isBlank()) {
            log.warn("[Scorer] ANTHROPIC_API_KEY not set — using neutral score");
            return new SentimentScore(0.0, "no api key");
        }

        try {
            String prompt = String.format(
                    "You are a financial sentiment analyser. " +
                    "Score the sentiment of this news headline for stock ticker %s. " +
                    "Return ONLY a valid JSON object — no markdown, no explanation outside the JSON. " +
                    "Format: {\"score\": <float -1.0 to 1.0>, \"reasoning\": \"<5 words max>\"} " +
                    "Where -1.0 = extremely bearish, 0.0 = neutral, 1.0 = extremely bullish. " +
                    "Headline: \"%s\"", ticker, headline);

            Map<String, Object> body = Map.of(
                    "model", "claude-haiku-4-5-20251001",
                    "max_tokens", 120,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", props.anthropicApiKey);
            headers.set("anthropic-version", "2023-06-01");

            ResponseEntity<String> response = restTemplate.exchange(
                    ANTHROPIC_URL,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            JsonNode root = mapper.readTree(response.getBody());
            String content = root.path("content").get(0).path("text").asText();

            // Strip any accidental markdown fences
            content = content.replaceAll("(?s)```[a-z]*", "").replaceAll("```", "").trim();

            JsonNode parsed = mapper.readTree(content);
            double score = Math.max(-1.0, Math.min(1.0, parsed.path("score").asDouble(0.0)));
            String reasoning = parsed.path("reasoning").asText("n/a");

            return new SentimentScore(score, reasoning);

        } catch (Exception e) {
            log.error("[Scorer] Anthropic API error: {}", e.getMessage());
            return new SentimentScore(0.0, "api error");
        }
    }

    // Simple inner record for the parsed API result
    private record SentimentScore(double score, String reasoning) {}
}
