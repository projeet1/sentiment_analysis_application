package com.acp.finance.controller;

import com.acp.finance.config.AppProperties;
import com.acp.finance.service.AggregatorService;
import com.acp.finance.service.NewsPollerService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
public class DashboardController {

    private final AggregatorService aggregator;
    private final NewsPollerService poller;
    private final AppProperties props;

    public DashboardController(AggregatorService aggregator,
                               NewsPollerService poller,
                               AppProperties props) {
        this.aggregator = aggregator;
        this.poller = poller;
        this.props = props;
    }

    // Serve the dashboard HTML
    @GetMapping("/")
    public String dashboard() {
        return "dashboard";
    }

    // --- REST API ---

    @GetMapping("/api/dashboard/scores")
    @ResponseBody
    public ResponseEntity<?> getScores() {
        return ResponseEntity.ok(aggregator.getLatestScores());
    }

    @GetMapping("/api/dashboard/signals")
    @ResponseBody
    public ResponseEntity<?> getSignals(
            @RequestParam(defaultValue = "20") int limit) {
        var signals = aggregator.getRecentSignals();
        return ResponseEntity.ok(signals.subList(0, Math.min(limit, signals.size())));
    }

    @GetMapping("/api/dashboard/stats")
    @ResponseBody
    public ResponseEntity<?> getAllStats() {
        List<Map<String, Object>> stats = new ArrayList<>();
        for (String ticker : props.tickers.split(",")) {
            stats.add(aggregator.getTickerStats(ticker.trim()));
        }
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/api/dashboard/ticker/{ticker}/recent")
    @ResponseBody
    public ResponseEntity<?> getRecentArticles(@PathVariable String ticker) {
        return ResponseEntity.ok(aggregator.getRecentArticles(ticker));
    }

    // Manual poll trigger — useful for demos without waiting 60 seconds
    @PostMapping("/api/dashboard/trigger-poll")
    @ResponseBody
    public ResponseEntity<Map<String, String>> triggerPoll() {
        try {
            poller.pollNews();
            return ResponseEntity.ok(Map.of("status", "ok", "message", "Poll triggered successfully"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }
}
