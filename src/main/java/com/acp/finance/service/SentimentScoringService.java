package com.acp.finance.service;

import com.acp.finance.analysis.DedupeChecker;
import com.acp.finance.analysis.LocalHeuristicAnalyser;
import com.acp.finance.config.AppProperties;
import com.acp.finance.model.NewsArticle;
import com.acp.finance.model.SentimentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SentimentScoringService {

    private static final Logger log = LoggerFactory.getLogger(SentimentScoringService.class);

    /** Must match the id= attribute on the @KafkaListener below. */
    private static final String LISTENER_ID = "sentiment-scorer";


    private static final String GEMINI_URL_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent?key=%s";
    /**
     * Human-readable descriptions of each tracked instrument, injected into the
     * LLM prompt so Claude can reason about macro relevance correctly.
     */
    private static final Map<String, String> INSTRUMENT_CONTEXT = Map.of(
        "SPY", "broad US equity market / S&P 500 index",
        "QQQ", "Nasdaq 100 / big tech / growth stocks",
        "TLT", "long-duration US Treasury bonds / long-term interest rates",
        "GLD", "gold / safe-haven asset / inflation hedge",
        "USO", "crude oil / energy market"
    );

    private static final List<String> POSITIVE_KEYWORDS =
            List.of("beat", "surge", "record", "gain", "jump", "profit", "growth", "rises", "strong",
                    "rally", "climbs", "jumps", "soars", "advances", "higher");
    private static final List<String> NEGATIVE_KEYWORDS =
            List.of("recall", "drop", "loss", "cut", "fall", "miss", "crash", "decline", "weak",
                    "lawsuit", "tumbles", "slides", "slips", "sinks", "fears", "concern");

    // ── Rate-limiter state (fixed 60-second window) ───────────────────────────
    private final AtomicInteger callsInWindow = new AtomicInteger(0);
    private volatile long windowStartMs = System.currentTimeMillis();

    // ── Circuit-breaker state ─────────────────────────────────────────────────
    private final AtomicBoolean llmDisabled = new AtomicBoolean(false);

    // ── Listener pause/resume ─────────────────────────────────────────────────
    private final AtomicBoolean listenerPaused = new AtomicBoolean(false);
    private final ScheduledExecutorService resumeScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "llm-resume-scheduler");
                t.setDaemon(true);
                return t;
            });

    @Value("${MOCK_SENTIMENT:false}")
    private boolean mockSentiment;

    private final AppProperties props;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaListenerEndpointRegistry kafkaListenerRegistry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    private final LocalHeuristicAnalyser localHeuristicAnalyser;
    private final DedupeChecker dedupeChecker;

    public SentimentScoringService(AppProperties props,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   KafkaListenerEndpointRegistry kafkaListenerRegistry,
                                   LocalHeuristicAnalyser localHeuristicAnalyser,
                                   DedupeChecker dedupeChecker) {
        this.props = props;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaListenerRegistry = kafkaListenerRegistry;
        this.localHeuristicAnalyser = localHeuristicAnalyser;
        this.dedupeChecker = dedupeChecker;
    }

    @PostConstruct
    private void logStartupMode() {
        if (props.useLlmSentiment && !mockSentiment) {
            if (isBlank(props.geminiApiKey)) {
                log.warn("[Scorer] USE_LLM_SENTIMENT=true but GEMINI_API_KEY is not set — " +
                         "will fall back to local scoring for every message");
            } else {
                log.info("[Scorer] LLM mode ENABLED (Gemini) — cap={} calls/min, fail-closed={}",
                         props.maxLlmCallsPerMinute, props.llmDisableOnError);
            }
        } else {
            log.info("[Scorer] LLM mode DISABLED — all scoring is local (safe default)");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main Kafka listener
    // ─────────────────────────────────────────────────────────────────────────

    @KafkaListener(
        id     = "sentiment-scorer",
        topics = "${app.kafka.topic.raw:news-raw}",
        groupId = "sentiment-scorer"
    )
    public void score(String message) {
        try {
            NewsArticle article = mapper.readValue(message, NewsArticle.class);

            // STEP 1 — populate missing metadata
            String normalised = NewsArticle.normalise(article.getTitle());
            if (isBlank(article.getContentHash())) {
                article.setContentHash(NewsArticle.computeHash(normalised));
            }
            if (isBlank(article.getArticleId())) {
                article.setArticleId(java.util.UUID.randomUUID().toString());
            }
            if (isBlank(article.getIngestedAt())) {
                article.setIngestedAt(java.time.Instant.now().toString());
            }
            if (isBlank(article.getSourceMode())) {
                String src = article.getSource() != null ? article.getSource() : "";
                article.setSourceMode(
                    (src.equals("TemplateNews") || src.equals("MacroEventSimulator"))
                    ? "LOCAL" : "EXTERNAL");
            }

            // STEP 2 — dedupe check
            boolean isDuplicate = dedupeChecker.checkAndMark(article.getContentHash());
            if (isDuplicate) {
                log.info("[Scorer] DEDUPED {} | {}", article.getTicker(), article.getTitle());
                publishDeduped(article);
                return;
            }

            // STEP 3 — run local heuristic analysis (always, provides event enrichment)
            LocalHeuristicAnalyser.AnalysisResult analysis =
                localHeuristicAnalyser.analyse(article.getTitle());

            // STEP 4 — decide scoring path
            ScoredResult scored = chooseScoringPath(article, analysis);

            // STEP 5 — build and publish SentimentResult
            SentimentResult result = buildResult(article, analysis, scored);
            kafkaTemplate.send(props.kafkaTopicSentiment, article.getTicker(),
                               mapper.writeValueAsString(result));

            log.info("[Scorer] {} | mode={} | score={} | type={} | horizon={} | breaking={} | {}",
                article.getTicker(), scored.analysisMode(), scored.score(),
                result.getEventType(), result.getHorizon(), result.isBreaking(),
                article.getTitle());

        } catch (Exception e) {
            log.error("[Scorer] Error scoring message: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scoring path selection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the scored result using LLM or local fallback, applying all
     * safety guards in order:
     *   1. LLM disabled by config or mock mode → local
     *   2. API key absent → local
     *   3. Circuit breaker tripped → local
     *   4. Rate cap reached → pause listener, local for this message
     *   5. Otherwise → LLM call
     */
    private ScoredResult chooseScoringPath(NewsArticle article,
                                           LocalHeuristicAnalyser.AnalysisResult analysis) {
        if (!props.useLlmSentiment || mockSentiment) {
            return scoreLocally(article.getTitle());
        }
        if (isBlank(props.geminiApiKey)) {
            log.debug("[Scorer] GEMINI_API_KEY not set — using local scoring");
            return scoreLocally(article.getTitle());
        }
        if (llmDisabled.get()) {
            log.debug("[Scorer] LLM circuit breaker open — using local scoring");
            return scoreLocally(article.getTitle());
        }
        if (!tryAcquireLlmSlot()) {
            // Cap reached for this window — pause listener so further messages
            // stay buffered in Kafka, and score this message locally.
            pauseListenerUntilWindowReset();
            log.info("[Scorer] Rate cap reached — scoring locally, Kafka listener paused");
            return scoreLocally(article.getTitle());
        }
        // All guards passed — call Gemini
        return callGeminiApi(article.getTicker(), article.getTitle(), analysis);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rate limiter — fixed 60-second window
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Atomically checks and increments the call counter for the current window.
     * Returns true if the call is allowed, false if the cap is already reached.
     */
    private synchronized boolean tryAcquireLlmSlot() {
        long now = System.currentTimeMillis();
        if (now - windowStartMs >= 60_000L) {
            windowStartMs = now;
            callsInWindow.set(0);
        }
        if (callsInWindow.get() >= props.maxLlmCallsPerMinute) {
            return false;
        }
        callsInWindow.incrementAndGet();
        return true;
    }

    /** Returns milliseconds remaining in the current rate-limit window. */
    private synchronized long msUntilWindowReset() {
        long elapsed = System.currentTimeMillis() - windowStartMs;
        return Math.max(0L, 60_000L - elapsed);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Kafka listener pause / resume
    // ─────────────────────────────────────────────────────────────────────────

    private void pauseListenerUntilWindowReset() {
        if (listenerPaused.compareAndSet(false, true)) {
            MessageListenerContainer container =
                kafkaListenerRegistry.getListenerContainer(LISTENER_ID);
            if (container != null && !container.isPauseRequested()) {
                container.pause();
                long delayMs = msUntilWindowReset() + 1_000L; // 1 s buffer
                log.warn("[Scorer] Kafka listener PAUSED — LLM window full. " +
                         "Resuming in ~{}s", delayMs / 1000);
                resumeScheduler.schedule(() -> resumeListener(), delayMs, TimeUnit.MILLISECONDS);
            } else {
                listenerPaused.set(false); // container not found; don't hold the flag
            }
        }
    }

    private void resumeListener() {
        MessageListenerContainer container =
            kafkaListenerRegistry.getListenerContainer(LISTENER_ID);
        if (container != null && container.isPauseRequested()) {
            container.resume();
            listenerPaused.set(false);
            log.info("[Scorer] Kafka listener RESUMED — new LLM rate-limit window open");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Circuit breaker
    // ─────────────────────────────────────────────────────────────────────────

    private void disableLlm(String reason) {
        if (llmDisabled.compareAndSet(false, true)) {
            log.error("[Scorer] LLM DISABLED by circuit breaker — reason: {}. " +
                      "All future scoring will use local fallback. " +
                      "Restart the app to re-enable LLM mode.", reason);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLM call
    // ─────────────────────────────────────────────────────────────────────────

    private ScoredResult callGeminiApi(String ticker, String headline,
                                       LocalHeuristicAnalyser.AnalysisResult analysis) {
        try {
            String instrumentDesc = INSTRUMENT_CONTEXT.getOrDefault(ticker, ticker);
            String prompt = buildPrompt(ticker, instrumentDesc, headline);

            // Gemini request body: contents → parts → text
            Map<String, Object> body = Map.of(
                "contents", List.of(
                    Map.of("parts", List.of(Map.of("text", prompt)))
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String url = String.format(GEMINI_URL_TEMPLATE, props.geminiApiKey);
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

            return parseGeminiResponse(response.getBody(), ticker, headline, analysis);

        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            log.error("[Scorer] Gemini HTTP {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            boolean billingLike = status == 401 || status == 403 || status == 429
                || containsBillingKeyword(e.getResponseBodyAsString());
            if (props.llmDisableOnError && billingLike) {
                disableLlm("HTTP " + status);
            }
            return scoreLocally(headline).withMode("LOCAL_HEURISTIC_FALLBACK");

        } catch (Exception e) {
            log.error("[Scorer] Gemini API error: {}", e.getMessage());
            return scoreLocally(headline).withMode("LOCAL_HEURISTIC_FALLBACK");
        }
    }

    /**
     * Builds the macro-instrument-aware classification prompt.
     *
     * Claude is asked to:
     *   1. Judge whether the headline is materially relevant to the instrument.
     *   2. If relevant, classify directional sentiment and assign a bounded score.
     *   3. Return compact JSON only — no markdown, no surrounding text.
     */
    private static String buildPrompt(String ticker, String instrumentDesc, String headline) {
        return String.format(
                "You are a macro market sentiment classifier.\n\n" +
                        "Instrument: %s — %s\n\n" +
                        "Assess the headline below and return ONLY a JSON object. Follow these rules:\n" +
                        "1. Set \"relevant\" to true if the headline is likely to have a plausible near-term directional impact on %s, either directly or indirectly through macro channels.\n" +
                        "2. Valid macro channels include interest rates, inflation, growth expectations, recession risk, labour market data, risk sentiment, safe-haven demand, energy supply, commodity demand, and geopolitics.\n" +
                        "3. Use relevant=false only if the headline has no plausible directional linkage to %s.\n" +
                        "4. If relevant=false, set score=0.0 and sentiment=\"neutral\".\n" +
                        "5. If relevant=true, classify sentiment as bullish, bearish, or neutral and assign a score in [-1.0, 1.0] where -1.0=extremely bearish and 1.0=extremely bullish.\n" +
                        "6. Keep reasoning to six words or fewer.\n" +
                        "7. Return valid JSON only with no markdown and no text outside the JSON.\n\n" +
                        "Required format:\n" +
                        "{\"relevant\":<true|false>," +
                        "\"instrument\":\"%s\"," +
                        "\"sentiment\":\"<bullish|bearish|neutral>\"," +
                        "\"score\":<float -1.0 to 1.0>," +
                        "\"reasoning\":\"<max 6 words>\"}\n\n" +
                        "Headline: \"%s\"",
                ticker, instrumentDesc, ticker, ticker, ticker, headline
        );
    }

    /**
     * Parses the Gemini generateContent response body.
     *
     * Gemini response path: candidates[0] → content → parts[0] → text
     *
     * Contract enforced here:
     *   - relevant=false  → score forced to 0.0
     *   - score always clamped to [-1.0, 1.0]
     *   - any parse or navigation failure falls back to local scoring
     */
    private ScoredResult parseGeminiResponse(String responseBody,
                                             String ticker,
                                             String headline,
                                             LocalHeuristicAnalyser.AnalysisResult analysis) {
        try {
            JsonNode root    = mapper.readTree(responseBody);
            // Navigate: candidates[0].content.parts[0].text
            JsonNode textNode = root.path("candidates").get(0)
                                    .path("content")
                                    .path("parts").get(0)
                                    .path("text");

            if (textNode == null || textNode.isMissingNode()) {
                log.warn("[Scorer] Gemini response missing candidates text for {} — falling back", ticker);
                return scoreLocally(headline).withMode("LOCAL_HEURISTIC_FALLBACK");
            }

            String content = textNode.asText();
            // Strip any accidental markdown fences before parsing
            content = content.replaceAll("(?s)```[a-z]*", "").replaceAll("```", "").trim();

            JsonNode parsed = mapper.readTree(content);

            boolean relevant  = parsed.path("relevant").asBoolean(true);
            String  sentiment = parsed.path("sentiment").asText("neutral").toLowerCase();
            String  reasoning = parsed.path("reasoning").asText("n/a");
            double  rawScore  = parsed.path("score").asDouble(0.0);
            double  score     = relevant ? clamp(rawScore) : 0.0;

            if (!relevant) {
                log.info("[Scorer] Gemini {} | IRRELEVANT → score=0.0 | {}", ticker, headline);
                return new ScoredResult(0.0, "not relevant to " + ticker,
                                        analysis.eventType(), analysis.horizon(),
                                        analysis.confidence(), analysis.isBreaking(),
                                        "GEMINI_IRRELEVANT");
            }

            log.info("[Scorer] Gemini {} | {} | score={} | {} | {}",
                     ticker, sentiment, score, reasoning, headline);
            return new ScoredResult(score, reasoning,
                                    analysis.eventType(), analysis.horizon(),
                                    analysis.confidence(), analysis.isBreaking(),
                                    "GEMINI");

        } catch (Exception e) {
            log.warn("[Scorer] Gemini parse failure for {} — falling back to local scoring: {}",
                     ticker, e.getMessage());
            return scoreLocally(headline).withMode("LOCAL_HEURISTIC_FALLBACK");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Local fallback scoring
    // ─────────────────────────────────────────────────────────────────────────

    private ScoredResult scoreLocally(String headline) {
        String lower = headline.toLowerCase();
        if (POSITIVE_KEYWORDS.stream().anyMatch(lower::contains)) {
            return new ScoredResult(0.75, "local positive", null, null, 0.0, false,
                                    "LOCAL_HEURISTIC");
        }
        if (NEGATIVE_KEYWORDS.stream().anyMatch(lower::contains)) {
            return new ScoredResult(-0.65, "local negative", null, null, 0.0, false,
                                    "LOCAL_HEURISTIC");
        }
        return new ScoredResult(0.05, "local neutral", null, null, 0.0, false,
                                "LOCAL_HEURISTIC");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result builders / publishers
    // ─────────────────────────────────────────────────────────────────────────

    private SentimentResult buildResult(NewsArticle article,
                                        LocalHeuristicAnalyser.AnalysisResult analysis,
                                        ScoredResult scored) {
        SentimentResult r = new SentimentResult();
        r.setTicker(article.getTicker());
        r.setHeadline(article.getTitle());
        r.setScore(scored.score());
        r.setReasoning(scored.reasoning());
        r.setPublishedAt(article.getPublishedAt());
        r.setEventType(scored.eventType() != null ? scored.eventType() : analysis.eventType());
        r.setHorizon(scored.horizon() != null ? scored.horizon() : analysis.horizon());
        r.setConfidence(scored.confidence() > 0 ? scored.confidence() : analysis.confidence());
        r.setBreaking(scored.isBreaking() || analysis.isBreaking());
        r.setDeduped(false);
        r.setArticleId(article.getArticleId());
        r.setContentHash(article.getContentHash());
        r.setSourceMode(article.getSourceMode());
        r.setAnalysisMode(scored.analysisMode());
        return r;
    }

    private void publishDeduped(NewsArticle article) throws Exception {
        SentimentResult d = new SentimentResult();
        d.setTicker(article.getTicker());
        d.setHeadline(article.getTitle());
        d.setScore(0.0);
        d.setPublishedAt(article.getPublishedAt());
        d.setDeduped(true);
        d.setArticleId(article.getArticleId());
        d.setContentHash(article.getContentHash());
        d.setSourceMode(article.getSourceMode());
        d.setEventType("OTHER");
        d.setHorizon("UNKNOWN");
        d.setConfidence(0.0);
        d.setAnalysisMode("DEDUPED");
        kafkaTemplate.send(props.kafkaTopicSentiment, article.getTicker(),
                           mapper.writeValueAsString(d));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static double clamp(double v) { return Math.max(-1.0, Math.min(1.0, v)); }

    private static boolean containsBillingKeyword(String body) {
        if (body == null) return false;
        String lower = body.toLowerCase();
        return lower.contains("quota") || lower.contains("billing")
            || lower.contains("credit") || lower.contains("limit")
            || lower.contains("insufficient") || lower.contains("overload");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner record types
    // ─────────────────────────────────────────────────────────────────────────

    private record ScoredResult(double score, String reasoning,
                                String eventType, String horizon,
                                double confidence, boolean isBreaking,
                                String analysisMode) {
        /** Returns a copy with a different analysisMode (used for fallback labelling). */
        ScoredResult withMode(String mode) {
            return new ScoredResult(score, reasoning, eventType, horizon,
                                    confidence, isBreaking, mode);
        }
    }
}
