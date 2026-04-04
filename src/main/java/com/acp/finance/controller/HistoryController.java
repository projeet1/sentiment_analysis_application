package com.acp.finance.controller;

import com.acp.finance.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private static final Logger log =
        LoggerFactory.getLogger(HistoryController.class);

    private final StringRedisTemplate redis;
    private final DynamoDbClient dynamoDbClient;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public HistoryController(StringRedisTemplate redis,
                             DynamoDbClient dynamoDbClient,
                             AppProperties props,
                             ObjectMapper mapper) {
        this.redis = redis;
        this.dynamoDbClient = dynamoDbClient;
        this.props = props;
        this.mapper = mapper;
    }

    @GetMapping("/ticker/{ticker}/snapshots")
    public ResponseEntity<List<Map<String, Object>>> getSnapshots(
            @PathVariable String ticker) {
        try {
            List<String> raw = redis.opsForList()
                .range("history:" + ticker + ":snapshots", 0, 99);
            if (raw == null || raw.isEmpty())
                return ResponseEntity.ok(List.of());

            List<Map<String, Object>> result = new ArrayList<>();
            for (String json : raw) {
                try {
                    result.add(mapper.readValue(json,
                        new com.fasterxml.jackson.core.type
                            .TypeReference<Map<String, Object>>() {}));
                } catch (Exception e) {
                    log.warn("[History] Failed to parse snapshot: {}",
                        e.getMessage());
                }
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[History] Error fetching snapshots for {}: {}",
                ticker, e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/signals")
    public ResponseEntity<List<Map<String, Object>>> getAllSignals() {
        try {
            ScanResponse response = dynamoDbClient.scan(
                ScanRequest.builder()
                    .tableName(props.dynamoDbTable)
                    .limit(50)
                    .build());

            List<Map<String, Object>> signals =
                response.items().stream()
                    .map(this::itemToMap)
                    .sorted(Comparator.comparing(
                        m -> String.valueOf(
                            m.getOrDefault("timestamp", "")),
                        Comparator.reverseOrder()))
                    .limit(50)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(signals);
        } catch (Exception e) {
            log.error("[History] Error fetching signals: {}",
                e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/signals/{ticker}")
    public ResponseEntity<List<Map<String, Object>>> getSignalsByTicker(
            @PathVariable String ticker) {
        try {
            ScanResponse response = dynamoDbClient.scan(
                ScanRequest.builder()
                    .tableName(props.dynamoDbTable)
                    .filterExpression("ticker = :t")
                    .expressionAttributeValues(Map.of(
                        ":t", AttributeValue.builder()
                            .s(ticker).build()))
                    .build());

            List<Map<String, Object>> signals =
                response.items().stream()
                    .map(this::itemToMap)
                    .sorted(Comparator.comparing(
                        m -> String.valueOf(
                            m.getOrDefault("timestamp", "")),
                        Comparator.reverseOrder()))
                    .limit(20)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(signals);
        } catch (Exception e) {
            log.error("[History] Error fetching signals for {}: {}",
                ticker, e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/eventtypes/{ticker}")
    public ResponseEntity<Map<String, Integer>> getEventTypes(
            @PathVariable String ticker) {
        try {
            List<String> raw = redis.opsForList()
                .range("history:" + ticker + ":snapshots", 0, 99);
            if (raw == null || raw.isEmpty())
                return ResponseEntity.ok(Map.of());

            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String json : raw) {
                try {
                    Map<String, Object> snapshot =
                        mapper.readValue(json,
                            new com.fasterxml.jackson.core.type
                                .TypeReference<Map<String, Object>>() {});
                    String et = String.valueOf(
                        snapshot.getOrDefault("eventType", "OTHER"));
                    counts.merge(et, 1, Integer::sum);
                } catch (Exception e) {
                    log.warn("[History] Parse error: {}",
                        e.getMessage());
                }
            }
            return ResponseEntity.ok(counts);
        } catch (Exception e) {
            log.error("[History] Error fetching event types for {}: {}",
                ticker, e.getMessage());
            return ResponseEntity.ok(Map.of());
        }
    }

    private Map<String, Object> itemToMap(
            Map<String, AttributeValue> item) {
        Map<String, Object> map = new LinkedHashMap<>();
        item.forEach((k, v) -> {
            if (v.s() != null) map.put(k, v.s());
            else if (v.n() != null) map.put(k, v.n());
            else if (v.bool() != null) map.put(k, v.bool());
            else map.put(k, v.toString());
        });
        return map;
    }
}
