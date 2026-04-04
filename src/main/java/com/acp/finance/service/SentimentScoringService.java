package com.acp.finance.service;

import com.acp.finance.analysis.DedupeChecker;
import com.acp.finance.analysis.LocalHeuristicAnalyser;
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
    private final LocalHeuristicAnalyser localHeuristicAnalyser;
    private final DedupeChecker dedupeChecker;

    public SentimentScoringService(AppProperties props,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   LocalHeuristicAnalyser localHeuristicAnalyser,
                                   DedupeChecker dedupeChecker) {
        this.props = props;
        this.kafkaTemplate = kafkaTemplate;
        this.localHeuristicAnalyser = localHeuristicAnalyser;
        this.dedupeChecker = dedupeChecker;
    }

    @KafkaListener(topics = "${app.kafka.topic.raw:news-raw}", groupId = "sentiment-scorer")
    public void score(String message) {
        try {
            NewsArticle article = mapper.readValue(message, NewsArticle.class);

            // STEP 1 — populate missing metadata
            String normalised = NewsArticle.normalise(article.getTitle());
            if (article.getContentHash() == null || article.getContentHash().isBlank()) {
                article.setContentHash(NewsArticle.computeHash(normalised));
            }
            if (article.getArticleId() == null || article.getArticleId().isBlank()) {
                article.setArticleId(java.util.UUID.randomUUID().toString());
            }
            if (article.getIngestedAt() == null || article.getIngestedAt().isBlank()) {
                article.setIngestedAt(java.time.Instant.now().toString());
            }
            if (article.getSourceMode() == null || article.getSourceMode().isBlank()) {
                String src = article.getSource() != null ? article.getSource() : "";
                article.setSourceMode(
                    (src.equals("TemplateNews") || src.equals("MarketEvent"))
                    ? "LOCAL" : "EXTERNAL");
            }

            // STEP 2 — dedupe check
            boolean isDuplicate = dedupeChecker.checkAndMark(article.getContentHash());

            if (isDuplicate) {
                log.info("[Scorer] DEDUPED {} | {}", article.getTicker(), article.getTitle());

                SentimentResult dupeResult = new SentimentResult();
                dupeResult.setTicker(article.getTicker());
                dupeResult.setHeadline(article.getTitle());
                dupeResult.setScore(0.0);
                dupeResult.setPublishedAt(article.getPublishedAt());
                dupeResult.setDeduped(true);
                dupeResult.setArticleId(article.getArticleId());
                dupeResult.setContentHash(article.getContentHash());
                dupeResult.setSourceMode(article.getSourceMode());
                dupeResult.setEventType("OTHER");
                dupeResult.setHorizon("UNKNOWN");
                dupeResult.setConfidence(0.0);
                dupeResult.setAnalysisMode("DEDUPED");

                String dupeJson = mapper.writeValueAsString(dupeResult);
                kafkaTemplate.send(props.kafkaTopicSentiment, article.getTicker(), dupeJson);
                return;
            }

            // STEP 3 — run local heuristic analysis regardless of mode
            LocalHeuristicAnalyser.AnalysisResult analysis =
                localHeuristicAnalyser.analyse(article.getTitle());

            // STEP 4 — score the headline
            double score;
            String reasoning;
            String eventType = null;
            String horizon = null;
            double confidence = 0.0;
            boolean isBreaking = false;
            String analysisMode;

            if (mockSentiment) {
                SentimentScore mockScore = scoreMock(article.getTitle());
                score = mockScore.score();
                reasoning = mockScore.reasoning();
                analysisMode = "LOCAL_HEURISTIC";
                eventType = analysis.eventType();
                horizon = analysis.horizon();
                confidence = analysis.confidence();
                isBreaking = analysis.isBreaking();
            } else {
                FullScore full = callAnthropicApi(article.getTicker(), article.getTitle(), analysis);
                score = full.score();
                reasoning = full.reasoning();
                eventType = full.eventType();
                horizon = full.horizon();
                confidence = full.confidence();
                isBreaking = full.isBreaking();
                analysisMode = "ANTHROPIC";
            }

            // STEP 5 — build SentimentResult with ALL fields
            SentimentResult result = new SentimentResult();
            result.setTicker(article.getTicker());
            result.setHeadline(article.getTitle());
            result.setScore(score);
            result.setReasoning(reasoning);
            result.setPublishedAt(article.getPublishedAt());

            // New enrichment fields
            result.setEventType(eventType != null ? eventType : analysis.eventType());
            result.setHorizon(horizon != null ? horizon : analysis.horizon());
            result.setConfidence(confidence > 0 ? confidence : analysis.confidence());
            result.setBreaking(isBreaking || analysis.isBreaking());
            result.setDeduped(false);
            result.setArticleId(article.getArticleId());
            result.setContentHash(article.getContentHash());
            result.setSourceMode(article.getSourceMode());
            result.setAnalysisMode(analysisMode);

            // STEP 6 — publish as before
            String json = mapper.writeValueAsString(result);
            kafkaTemplate.send(props.kafkaTopicSentiment, article.getTicker(), json);

            log.info("[Scorer] {} | score={} | type={} | horizon={} | breaking={} | confidence={} | {}",
                article.getTicker(), score,
                result.getEventType(), result.getHorizon(),
                result.isBreaking(),
                String.format("%.2f", result.getConfidence()),
                article.getTitle());

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

    private FullScore callAnthropicApi(String ticker, String headline,
                                       LocalHeuristicAnalyser.AnalysisResult analysis) {
        if (props.anthropicApiKey == null || props.anthropicApiKey.isBlank()) {
            log.warn("[Scorer] ANTHROPIC_API_KEY not set — using neutral score");
            return new FullScore(0.0, "no api key",
                analysis.eventType(), analysis.horizon(),
                analysis.confidence(), analysis.isBreaking());
        }

        try {
            String prompt = String.format(
                "You are a financial sentiment analyser. " +
                "Score the sentiment of this news headline for stock ticker %s. " +
                "Return ONLY a valid JSON object — no markdown, no explanation outside the JSON. " +
                "Format: {\"score\": <float -1.0 to 1.0>, \"reasoning\": \"<5 words max>\", " +
                "\"eventType\": \"<EARNINGS|GUIDANCE|MERGER|REGULATION|PRODUCT|MANAGEMENT|LEGAL|MACRO|PARTNERSHIP|OTHER>\", " +
                "\"horizon\": \"<IMMEDIATE|INTRADAY|SWING|UNKNOWN>\", " +
                "\"confidence\": <float 0.0 to 1.0>, " +
                "\"isBreaking\": <true|false>} " +
                "Where -1.0 = extremely bearish, 0.0 = neutral, 1.0 = extremely bullish. " +
                "Headline: \"%s\"", ticker, headline);

            Map<String, Object> body = Map.of(
                    "model", "claude-haiku-4-5-20251001",
                    "max_tokens", 200,
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
            double score = Math.max(-1.0, Math.min(1.0,
                parsed.path("score").asDouble(0.0)));
            String reasoning = parsed.path("reasoning").asText("n/a");
            String eventType = parsed.path("eventType").asText(analysis.eventType());
            String horizon = parsed.path("horizon").asText(analysis.horizon());
            double confidence = parsed.path("confidence").asDouble(analysis.confidence());
            boolean isBreaking = parsed.path("isBreaking").asBoolean(analysis.isBreaking());

            return new FullScore(score, reasoning, eventType, horizon, confidence, isBreaking);

        } catch (Exception e) {
            log.error("[Scorer] Anthropic API error: {}", e.getMessage());
            return new FullScore(0.0, "api error",
                analysis.eventType(), analysis.horizon(),
                analysis.confidence(), analysis.isBreaking());
        }
    }

    // Simple inner record for mock scoring result
    private record SentimentScore(double score, String reasoning) {}

    // Full scoring result including all enrichment fields
    private record FullScore(double score, String reasoning,
                             String eventType, String horizon,
                             double confidence, boolean isBreaking) {}
}
