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

    // ─── Macro headline pools (shared across instruments) ──────────────────────

    private static final String[] CRASH = {
        "Wall Street tumbles on policy shock fears",
        "US equities plunge as systemic risk concerns grip markets",
        "S&P 500 enters freefall on emergency Fed intervention fears",
        "Treasury yields spike as bonds sell off sharply",
        "Gold slumps as the dollar surges on safe-haven flows",
        "Oil sinks on severe global growth concerns",
        "Nasdaq collapses as rate shock triggers leveraged unwind",
        "Credit markets seize up driving panic selling across asset classes",
        "Risk assets in meltdown as recession probability surges",
        "Broad market sell-off accelerates on deteriorating macro outlook"
    };

    private static final String[] BEARISH = {
        "US stocks fall after hawkish Fed remarks at policy meeting",
        "Nasdaq slides as Treasury yields surge on strong jobs data",
        "Treasury bonds sell off as rate cuts are repriced lower",
        "Gold drops as rising real yields weigh on precious metals",
        "Oil weakens on demand fears as global growth outlook dims",
        "S&P 500 retreats after hotter-than-expected inflation reading",
        "Growth stocks underperform as bond yields climb to recent highs",
        "Energy markets retreat as recession risks dampen demand forecasts",
        "Investors reduce risk exposure ahead of key central bank decision",
        "US equities decline as credit spreads widen on macro uncertainty"
    };

    private static final String[] NEUTRAL = {
        "Investors await the latest CPI report before making major moves",
        "Markets hold steady ahead of the Federal Reserve decision",
        "Treasury market quiet in thin trading ahead of key data release",
        "Gold holds near recent range as dollar and yields offset each other",
        "Oil trades sideways as traders assess conflicting supply signals",
        "US equities flat as investors digest mixed economic data",
        "Bond yields unchanged as markets price a data-dependent Fed",
        "Macro calendar light this week keeping volumes subdued",
        "Commodities consolidate near recent levels ahead of OPEC meeting",
        "Cross-asset volatility low as market awaits next catalyst"
    };

    private static final String[] BULLISH = {
        "US stocks rally after softer-than-expected inflation data",
        "Nasdaq gains as Treasury yields ease on dovish Fed commentary",
        "Treasury bonds jump on rising rate-cut expectations",
        "Gold rises on safe-haven demand and weaker dollar",
        "Oil climbs on supply concerns and improving demand outlook",
        "S&P 500 advances broadly as consumer sentiment beats forecasts",
        "Growth stocks lead gains as real yields fall back from recent highs",
        "Bond market rallies after Fed signals patient approach to tightening",
        "Precious metals firm as inflation expectations tick higher",
        "Energy prices rise after surprise drawdown in US crude inventories"
    };

    private static final String[] EUPHORIA = {
        "S&P 500 surges as markets price aggressive Fed easing cycle",
        "Nasdaq jumps on broad risk-on momentum after inflation shock fades",
        "Treasury bonds soar on recession fears and rate-cut expectations",
        "Gold breaks higher on intense safe-haven demand amid global stress",
        "Oil spikes sharply on major supply disruption fears",
        "US equities hit record highs as soft landing narrative is confirmed",
        "Bond market in historic rally as inflation falls to multi-year low",
        "Precious metals explode higher as central bank buying accelerates",
        "Risk assets surge across the board after surprise Fed pivot",
        "Energy markets in euphoric rally after OPEC announces deep cuts"
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
                "status",  "disabled",
                "message", "Event injection is disabled in live Finnhub mode — real headlines are " +
                           "flowing. Set NEWS_SOURCE=template to enable simulation."
            ));
        }

        String ticker    = ((String) body.getOrDefault("ticker",       "SPY")).trim().toUpperCase();
        String sentiment = ((String) body.getOrDefault("sentiment",    "NEUTRAL")).trim().toUpperCase();
        int    count     = ((Number) body.getOrDefault("articleCount", 5)).intValue();

        List<String> tickers = ticker.equals("ALL")
                ? Arrays.stream(props.tickers.split(","))
                        .map(String::trim).filter(t -> !t.isBlank())
                        .collect(Collectors.toList())
                : List.of(ticker);

        String[] pool    = getPool(sentiment);
        int      totalSent = 0;

        try {
            for (String t : tickers) {
                for (int i = 0; i < count; i++) {
                    String title = pool[random.nextInt(pool.length)];

                    NewsArticle article = new NewsArticle();
                    article.setTicker(t);
                    article.setTitle(title);
                    article.setDescription("");
                    article.setPublishedAt(Instant.now().toString());
                    article.setSource("MacroEventSimulator");

                    kafkaTemplate.send(props.kafkaTopicRaw, t, mapper.writeValueAsString(article));
                    totalSent++;
                }
                log.info("[EventInjector] Injected {} {} articles for {}", count, sentiment, t);
            }
        } catch (Exception e) {
            log.error("[EventInjector] Error injecting articles: {}", e.getMessage());
        }

        String display = ticker.equals("ALL") ? "ALL instruments" : ticker;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status",           "ok");
        resp.put("ticker",           ticker);
        resp.put("sentiment",        sentiment);
        resp.put("articlesInjected", totalSent);
        resp.put("message",          "Injected " + totalSent + " " + sentiment + " articles for " + display);
        return ResponseEntity.ok(resp);
    }

    // ─── GET /api/events/scenarios ─────────────────────────────────────────────

    @GetMapping("/scenarios")
    @ResponseBody
    public ResponseEntity<?> getScenarios() {
        return ResponseEntity.ok(List.of(
            scenario("Fed Dovish Pivot",
                     "Softer Fed tone boosts equities and bonds",
                     "ALL", "BULLISH",  8),
            scenario("Fed Hawkish Surprise",
                     "Hawkish Fed pressures risk assets and duration",
                     "ALL", "BEARISH",  8),
            scenario("Flight to Safety",
                     "Market stress drives demand for gold and Treasuries",
                     "GLD", "BULLISH",  8),
            scenario("Oil Supply Shock",
                     "Supply disruption fears send crude sharply higher",
                     "USO", "EUPHORIA", 8)
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
            redis.delete("recent:" + t);
            redis.delete("mock:"   + t + ":pointer");
            log.info("[EventInjector] Reset state for {}", t);
        }

        String display = ticker.equals("ALL") ? "ALL instruments" : ticker;
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

    private String[] getPool(String sentiment) {
        return switch (sentiment) {
            case "CRASH"    -> CRASH;
            case "BEARISH"  -> BEARISH;
            case "BULLISH"  -> BULLISH;
            case "EUPHORIA" -> EUPHORIA;
            default         -> NEUTRAL;
        };
    }
}
