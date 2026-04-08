package com.acp.finance.controller;

import com.acp.finance.config.AppProperties;
import com.acp.finance.model.NewsArticle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/events")
public class EventInjectorController {

    private static final Logger log = LoggerFactory.getLogger(EventInjectorController.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final Random random = new Random();

    private static final Map<String, String> COMPANY_NAMES = Map.of(
            "AAPL",  "Apple",
            "TSLA",  "Tesla",
            "MSFT",  "Microsoft",
            "GOOGL", "Alphabet"
    );

    private static final String[] DIVISIONS = {
            "cloud", "AI", "advertising", "hardware",
            "software", "services", "retail", "enterprise", "mobile"
    };

    private static final String[] CRASH = {
            "{company} shares collapse after catastrophic earnings miss",
            "{company} CEO arrested amid fraud investigation",
            "{company} faces imminent bankruptcy filing reports suggest",
            "{company} recalls entire product line over safety crisis",
            "{company} loses landmark lawsuit facing billion dollar damages",
            "{company} credit rating downgraded to junk status",
            "{company} major data breach exposes millions of customers",
            "{company} regulators freeze operations pending investigation",
            "{company} largest shareholder dumps entire stake immediately",
            "{company} profit warning sends shares into freefall"
    };

    private static final String[] BEARISH = {
            "{company} misses earnings estimates for third consecutive quarter",
            "{company} cuts workforce by {n} thousand amid restructuring",
            "{company} loses major contract to competitor",
            "{company} faces regulatory fine over {division} practices",
            "{company} CFO resigns unexpectedly citing personal reasons",
            "{company} delays flagship product launch indefinitely",
            "{company} reports widening losses in core {division} unit",
            "{company} analyst downgrades to sell with lower price target",
            "{company} market share falls as competition intensifies",
            "{company} revenue misses estimates amid weak demand"
    };

    private static final String[] NEUTRAL = {
            "{company} files routine quarterly report with regulators",
            "{company} board approves annual meeting date",
            "{company} confirms no material change to business outlook",
            "{company} appoints independent director to board",
            "{company} updates standard corporate governance policies",
            "{company} participates in industry conference next month",
            "{company} renews existing supplier agreements as expected",
            "{company} publishes annual sustainability report",
            "{company} confirms dividend payment schedule unchanged",
            "{company} completes routine share register update"
    };

    private static final String[] BULLISH = {
            "{company} beats earnings estimates with record quarterly profit",
            "{company} announces major share buyback programme",
            "{company} raises full year guidance above analyst expectations",
            "{company} wins landmark contract worth {n} billion",
            "{company} announces breakthrough product driving growth",
            "{company} reports strongest revenue growth in five years",
            "{company} expands into {n} new markets this quarter",
            "{company} dividend increased by {n} percent for shareholders",
            "{company} gains significant market share from competitors",
            "{company} upgraded to strong buy by leading analysts"
    };

    private static final String[] EUPHORIA = {
            "{company} announces revolutionary product changing entire industry",
            "{company} reports profit doubling beating all expectations",
            "{company} secures historic {n} billion government contract",
            "{company} stock hits all time high on record breaking results",
            "{company} announces merger creating worlds most valuable company",
            "{company} cure approved driving unprecedented revenue growth",
            "{company} AI breakthrough sends shares surging to record",
            "{company} Warren Buffett acquires major stake in company",
            "{company} revenue triples as global expansion accelerates",
            "{company} announces {n} for one stock split amid surge"
    };

    public EventInjectorController(KafkaTemplate<String, String> kafkaTemplate,
                                   AppProperties props,
                                   ObjectMapper mapper,
                                   StringRedisTemplate redis) {
        this.kafkaTemplate = kafkaTemplate;
        this.props         = props;
        this.mapper        = mapper;
        this.redis         = redis;
    }

    // ─── POST /api/events/inject ───────────────────────────────────────────────

    @PostMapping("/inject")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> inject(@RequestBody Map<String, Object> body) {
        if ("finnhub".equalsIgnoreCase(props.newsSource)) {
            return ResponseEntity.badRequest().body(Map.of(
                "status", "disabled",
                "message", "Event injection is disabled in " +
                    "live Finnhub mode — real headlines are " +
                    "flowing. Set NEWS_SOURCE=template to " +
                    "enable simulation."
            ));
        }
        String ticker    = ((String) body.getOrDefault("ticker",       "AAPL")).trim().toUpperCase();
        String sentiment = ((String) body.getOrDefault("sentiment",    "NEUTRAL")).trim().toUpperCase();
        int    count     = ((Number) body.getOrDefault("articleCount", 5)).intValue();

        List<String> tickers = ticker.equals("ALL")
                ? Arrays.stream(props.tickers.split(","))
                        .map(String::trim).filter(t -> !t.isBlank())
                        .collect(Collectors.toList())
                : List.of(ticker);

        String[] templates  = getTemplates(sentiment);
        int      totalSent  = 0;

        try {
            for (String t : tickers) {
                String company = COMPANY_NAMES.getOrDefault(t, t);
                for (int i = 0; i < count; i++) {
                    String title = fillTemplate(
                            templates[random.nextInt(templates.length)], company);

                    NewsArticle article = new NewsArticle();
                    article.setTicker(t);
                    article.setTitle(title);
                    article.setDescription("");
                    article.setPublishedAt(Instant.now().toString());
                    article.setSource("MarketEvent");

                    kafkaTemplate.send(props.kafkaTopicRaw, t,
                            mapper.writeValueAsString(article));
                    totalSent++;
                }
                log.info("[EventInjector] Injected {} {} articles for {}", count, sentiment, t);
            }
        } catch (Exception e) {
            log.error("[EventInjector] Error injecting articles: {}", e.getMessage());
        }

        String display = ticker.equals("ALL") ? "ALL tickers" : ticker;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status",           "ok");
        resp.put("ticker",           ticker);
        resp.put("sentiment",        sentiment);
        resp.put("articlesInjected", totalSent);
        resp.put("message",          "Injected " + totalSent + " " + sentiment
                                     + " articles for " + display);
        return ResponseEntity.ok(resp);
    }

    // ─── GET /api/events/scenarios ─────────────────────────────────────────────

    @GetMapping("/scenarios")
    @ResponseBody
    public ResponseEntity<?> getScenarios() {
        return ResponseEntity.ok(List.of(
                scenario("Tesla Crash",       "Simulate catastrophic news for TSLA",          "TSLA",  "CRASH",    10),
                scenario("Apple Euphoria",    "Simulate breakthrough news for AAPL",           "AAPL",  "EUPHORIA", 10),
                scenario("Microsoft Bullish", "Simulate strong results for MSFT",              "MSFT",  "BULLISH",  8),
                scenario("Google Bearish",    "Simulate regulatory trouble for GOOGL",         "GOOGL", "BEARISH",  8),
                scenario("Market Crash",      "Simulate crash across all tickers simultaneously", "ALL", "CRASH",   5),
                scenario("Market Euphoria",   "Simulate bull run across all tickers",          "ALL",   "EUPHORIA", 5)
        ));
    }

    // ─── POST /api/events/reset ────────────────────────────────────────────────

    @PostMapping("/reset")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reset(@RequestBody Map<String, Object> body) {
        String ticker = ((String) body.getOrDefault("ticker", "ALL")).trim().toUpperCase();

        List<String> tickers = ticker.equals("ALL")
                ? Arrays.stream(props.tickers.split(","))
                        .map(String::trim).filter(t -> !t.isBlank())
                        .collect(Collectors.toList())
                : List.of(ticker);

        for (String t : tickers) {
            redis.delete("sentiment:" + t + ":rolling:sum");
            redis.delete("sentiment:" + t + ":rolling:count");
            redis.delete("sentiment:" + t + ":ema:value");
            redis.delete("sentiment:" + t + ":window");
            redis.delete("sentiment:" + t + ":momentum:prev");
            redis.delete("sentiment:" + t + ":momentum:current");
            redis.delete("sentiment:" + t + ":last_signal");
            redis.delete("recent:"    + t);
            redis.delete("mock:"      + t + ":pointer");
            log.info("[EventInjector] Reset state for {}", t);
        }

        String display = ticker.equals("ALL") ? "ALL tickers" : ticker;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status",  "ok");
        resp.put("message", "Reset " + display + " state");
        return ResponseEntity.ok(resp);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> scenario(String name, String description,
                                         String ticker, String sentiment, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name",         name);
        m.put("description",  description);
        m.put("ticker",       ticker);
        m.put("sentiment",    sentiment);
        m.put("articleCount", count);
        return m;
    }

    private String[] getTemplates(String sentiment) {
        return switch (sentiment) {
            case "CRASH"    -> CRASH;
            case "BEARISH"  -> BEARISH;
            case "BULLISH"  -> BULLISH;
            case "EUPHORIA" -> EUPHORIA;
            default         -> NEUTRAL;
        };
    }

    private String fillTemplate(String template, String company) {
        String r = template.replace("{company}", company);
        r = r.replace("{division}", DIVISIONS[random.nextInt(DIVISIONS.length)]);
        // Context-aware {n} substitution — most-specific patterns first
        r = r.replaceFirst("\\{n\\} thousand",    (random.nextInt(100) + 1)  + " thousand");
        r = r.replaceFirst("\\{n\\} billion",     (random.nextInt(50)  + 1)  + " billion");
        r = r.replaceFirst("\\{n\\} percent",     (random.nextInt(36)  + 5)  + " percent");
        r = r.replaceFirst("\\{n\\} for one",     (random.nextInt(4)   + 2)  + " for one");
        r = r.replaceFirst("\\{n\\} new markets", (random.nextInt(19)  + 2)  + " new markets");
        r = r.replace("{n}", String.valueOf(random.nextInt(10) + 1));
        return r;
    }
}
