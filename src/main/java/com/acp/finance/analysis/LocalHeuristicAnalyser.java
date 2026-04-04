package com.acp.finance.analysis;

import com.acp.finance.model.NewsArticle;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class LocalHeuristicAnalyser {

    private static final Map<String, List<String>> EVENT_KEYWORDS =
        new LinkedHashMap<>();

    static {
        EVENT_KEYWORDS.put("EARNINGS", Arrays.asList(
            "earnings", "revenue", "profit", "loss", "quarterly",
            "q1", "q2", "q3", "q4", "eps", "beats", "misses", "results"));
        EVENT_KEYWORDS.put("LEGAL", Arrays.asList(
            "lawsuit", "litigation", "fine", "penalty", "court",
            "fraud", "arrested", "charges", "settlement", "damages"));
        EVENT_KEYWORDS.put("MERGER", Arrays.asList(
            "acquisition", "merger", "takeover", "deal",
            "buys", "acquired", "acquires", "buyout"));
        EVENT_KEYWORDS.put("MANAGEMENT", Arrays.asList(
            "ceo", "cfo", "cto", "resign", "appoints",
            "fired", "steps down", "leadership", "executive"));
        EVENT_KEYWORDS.put("REGULATION", Arrays.asList(
            "regulation", "regulatory", "antitrust", "investigation",
            "sec", "eu", "compliance", "scrutiny", "probe"));
        EVENT_KEYWORDS.put("PRODUCT", Arrays.asList(
            "launch", "product", "release", "announced",
            "breakthrough", "new", "unveils", "innovation"));
        EVENT_KEYWORDS.put("GUIDANCE", Arrays.asList(
            "guidance", "outlook", "forecast", "raises",
            "cuts", "warns", "expects", "projects", "target"));
        EVENT_KEYWORDS.put("MACRO", Arrays.asList(
            "inflation", "interest rate", "fed", "economy",
            "recession", "market", "gdp", "rates", "global"));
        EVENT_KEYWORDS.put("PARTNERSHIP", Arrays.asList(
            "partnership", "contract", "agreement",
            "collaboration", "joint", "alliance"));
    }

    private static final List<String> IMMEDIATE_KEYWORDS = Arrays.asList(
        "breaking", "crash", "surge", "collapse", "soars", "plunges",
        "halt", "emergency", "urgent", "alert"
    );

    private static final List<String> INTRADAY_KEYWORDS = Arrays.asList(
        "today", "session", "trading", "quarterly",
        "q1", "q2", "q3", "q4", "beats", "misses", "reports", "announces"
    );

    private static final List<String> SWING_KEYWORDS = Arrays.asList(
        "full-year", "annual", "strategic", "outlook",
        "guidance", "forecast", "long-term", "raises", "lowers"
    );

    private static final List<String> BREAKING_KEYWORDS = Arrays.asList(
        "breaking", "crash", "collapse", "fraud", "arrested",
        "bankruptcy", "halt", "recall", "emergency", "catastrophic"
    );

    public AnalysisResult analyse(String headline) {
        String normalised = NewsArticle.normalise(headline);

        // Score each event type
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry :
                EVENT_KEYWORDS.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (normalised.contains(keyword)) score++;
            }
            scores.put(entry.getKey(), score);
        }

        // Pick highest scoring event type
        String eventType = scores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .filter(e -> e.getValue() > 0)
            .map(Map.Entry::getKey)
            .orElse("OTHER");

        // Confidence based on keyword match strength
        int maxScore = scores.values().stream()
            .mapToInt(Integer::intValue)
            .max().orElse(0);
        double confidence = Math.min(0.95,
            maxScore > 0 ? 0.4 + (maxScore * 0.15) : 0.3);

        // Horizon
        String horizon = "UNKNOWN";
        if (IMMEDIATE_KEYWORDS.stream().anyMatch(normalised::contains)) {
            horizon = "IMMEDIATE";
        } else if (INTRADAY_KEYWORDS.stream()
                .anyMatch(normalised::contains)) {
            horizon = "INTRADAY";
        } else if (SWING_KEYWORDS.stream().anyMatch(normalised::contains)) {
            horizon = "SWING";
        }

        // isBreaking
        boolean isBreaking = BREAKING_KEYWORDS.stream()
            .anyMatch(normalised::contains);

        return new AnalysisResult(eventType, confidence,
                                  horizon, isBreaking);
    }

    public record AnalysisResult(
        String eventType,
        double confidence,
        String horizon,
        boolean isBreaking
    ) {}
}
