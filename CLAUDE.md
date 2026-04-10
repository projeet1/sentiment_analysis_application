# CLAUDE.md

This file is the authoritative technical reference for Claude Code (and any developer) working
on this repository. Read it before proposing or making any changes.

---

## Project Summary

Real-Time Macro Market Sentiment Pipeline. Ingests financial news headlines for five macro
instruments (SPY, QQQ, TLT, GLD, USO) from a pluggable news source, deduplicates and
classifies them by event type and horizon, scores them with four independent sentiment
strategies and an ensemble, fires BUY/SELL trade signals to RabbitMQ, and persists them
to DynamoDB. A live Thymeleaf dashboard at port 8080 shows all four strategies side-by-side,
their ensemble vote, history chart, and event type distribution. The system is part of the
ACP (Advanced Cloud Programming) coursework (CW3).

---

## Tech Stack

- **Java 17**, **Spring Boot 3.2.0** — `@EnableScheduling` for the news poller
- **Apache Kafka** (`apache/kafka-native`) — event backbone (`news-raw`, `news-sentiment` topics)
- **Redis 7** — strategy state, dedupe hashes, history snapshots, recent articles
- **RabbitMQ 3** — signal delivery (`buy-signals`, `sell-signals` durable queues)
- **DynamoDB via LocalStack** — persistent signal audit log (`trade-signals` table)
- **Docker Compose** — full local stack (kafka, rabbitmq, redis, localstack, app)
- **Chart.js 4.4.0** — history chart in the dropdown panel of dashboard.html
- **Thymeleaf** — dashboard.html template rendering
- **AWS SDK v2 (2.21.0)** — `DynamoDbClient`
- **Lombok** — model boilerplate
- **Jackson** — JSON serialisation / deserialisation throughout
- **Gemini API** (`gemini-3.1-flash-lite-preview`) — optional external LLM sentiment scoring

---

## Package / Class Map

```
com.acp.finance
├── FinanceSentimentApplication          @SpringBootApplication + @EnableScheduling; main entry point
│
├── config/
│   ├── AppProperties                    @Component; all @Value-injected env vars in one place
│   ├── DynamoDbConfig                   @Configuration; DynamoDbClient bean pointing at DYNAMODB_ENDPOINT
│   ├── RabbitConfig                     @Configuration; declares buy-signals + sell-signals Queue beans
│   └── StrategyConfig                   @Configuration; 4 strategy beans + EnsembleService bean
│
├── model/
│   ├── NewsArticle                      Raw article DTO; static normalise() + computeHash() helpers
│   ├── SentimentResult                  Scored article DTO; includes eventType, horizon, analysisMode
│   └── TradeSignal                      Fired signal DTO; persisted to DynamoDB + published to RabbitMQ
│
├── news/
│   ├── NewsSource                       Interface: getLatestHeadlines(ticker, count) → List<NewsArticle>
│   ├── TemplateNewsSource               @ConditionalOnProperty NEWS_SOURCE=template (default); synthetic headlines
│   ├── NewsApiSource                    @ConditionalOnProperty NEWS_SOURCE=newsapi; newsapi.org live headlines
│   └── FinnhubNewsSource                @ConditionalOnProperty NEWS_SOURCE=finnhub; finnhub.io company news
│
├── analysis/
│   ├── LocalHeuristicAnalyser           @Component; keyword-based event classification (10 types),
│   │                                    horizon detection, confidence scoring, isBreaking flag
│   └── DedupeChecker                    @Component; SHA-256 content hash → Redis "dedupe:{hash}", TTL 24h
│
├── strategy/
│   ├── SentimentStrategy                Interface: getName, getDisplayName, update, getScore, getSignal
│   ├── RollingAverageStrategy           Lifetime sum/count in Redis; keys: rolling:sum, rolling:count
│   ├── EmaStrategy                      EMA α=0.5; key: ema:value
│   ├── WindowedAverageStrategy          Last 5 scores Redis list; key: window
│   ├── MomentumStrategy                 EMA delta; keys: momentum:prev, momentum:current
│   └── EnsembleService                  Aggregates 4 strategy votes → STRONG BUY/BUY/SELL/STRONG SELL/HOLD
│                                        Weighted score: rolling×0.15 + ema×0.35 + windowed×0.30 + momentum×0.20
│
└── service/ + controller/
    ├── NewsPollerService                 @Scheduled(fixedDelayString); polls NewsSource per ticker → news-raw
    ├── SentimentScoringService           @KafkaListener(news-raw); dedupes; classifies; Gemini or local → news-sentiment
    ├── AggregatorService                 @KafkaListener(news-sentiment); updates strategies; fires signals
    ├── DashboardController               GET / + all /api/dashboard/* endpoints
    ├── HistoryController                 All /api/history/* endpoints (snapshots, signals, eventtypes)
    └── EventInjectorController           All /api/events/* endpoints (inject, scenarios, reset)
```

---

## Data Flow — Single Article End-to-End

1. **NewsPollerService** fires on schedule (default 60 s). For each ticker in `TICKERS`, it calls
   `newsSource.getLatestHeadlines(ticker, pollArticlesPerTicker)` and publishes each `NewsArticle`
   as JSON to Kafka topic `news-raw` (key = ticker).

2. **SentimentScoringService** consumes from `news-raw` (group `sentiment-scorer`, id `sentiment-scorer`).
   - Populates missing fields: `contentHash` (SHA-256 of normalised title), `articleId` (UUID),
     `ingestedAt` (ISO-8601 now), `sourceMode` ("LOCAL" if source is TemplateNews/MacroEventSimulator,
     else "EXTERNAL").
   - Calls `DedupeChecker.checkAndMark(contentHash)`. If duplicate → publishes a zero-score
     `SentimentResult` with `deduped=true`, `analysisMode=DEDUPED`, and returns early.
   - Calls `LocalHeuristicAnalyser.analyse(title)` → always produces eventType, horizon,
     confidence, isBreaking (regardless of LLM setting).
   - Calls `chooseScoringPath()`:
     - If `!useLlmSentiment || mockSentiment` → `scoreLocally()` → `LOCAL_HEURISTIC`
     - If `geminiApiKey` blank → `scoreLocally()` → `LOCAL_HEURISTIC`
     - If `llmDisabled` circuit breaker open → `scoreLocally()` → `LOCAL_HEURISTIC`
     - If rate cap reached → pause Kafka listener; `scoreLocally()` → `LOCAL_HEURISTIC`
     - Otherwise → `callGeminiApi()`:
       - Builds macro-instrument-aware prompt; POSTs to Gemini REST API.
       - Parses `candidates[0].content.parts[0].text` → strips markdown → parses JSON.
       - `relevant=false` → score forced to 0.0, `analysisMode=GEMINI_IRRELEVANT`.
       - `relevant=true` → `analysisMode=GEMINI`.
       - Any error → `scoreLocally().withMode("LOCAL_HEURISTIC_FALLBACK")`.
   - Publishes `SentimentResult` as JSON to Kafka topic `news-sentiment` (key = ticker).

3. **AggregatorService** consumes from `news-sentiment` (group `aggregator`).
   - If `result.isDeduped()`: stores in `recent:{ticker}` (leftPush + trim to 5) and returns.
   - Otherwise:
     - Calls `strategy.update(ticker, score, redis)` on all 4 strategies.
     - Calls `ensembleService.evaluate(ticker, redis, buyThreshold, sellThreshold)`.
     - Stores ensemble snapshot in `history:{ticker}:snapshots` (leftPush + trim to 100).
     - Stores article in `recent:{ticker}` (leftPush + trim to 5).
     - Reads `sentiment:{ticker}:last_signal`. Fires BUY signal if ensemble is BUY/STRONG BUY
       and last was not BUY. Fires SELL signal if ensemble is SELL/STRONG SELL and last was not SELL.
     - On signal: publishes `TradeSignal` JSON to RabbitMQ queue; persists 12-attribute item to
       DynamoDB; caches in in-memory `recentSignals` list.

4. **DashboardController** serves the Thymeleaf dashboard and REST endpoints. The dashboard
   polls `/api/dashboard/stats` and `/api/dashboard/signals` every 3 seconds via JavaScript.
   Recent articles are fetched from `/api/dashboard/ticker/{ticker}/recent` which calls
   `AggregatorService.getRecentArticles()` — reads 30 items from Redis, dedupes by `contentHash`
   (or normalised headline if hash absent), returns first 5 unique.

---

## Key Configuration

All properties are read via `AppProperties` (@Component) using `@Value` annotations with defaults.

| Property / Env Var | Default | Reader | Notes |
|---|---|---|---|
| `NEWS_SOURCE` | `template` | `@ConditionalOnProperty` on news source classes | Selects active news source |
| `NEWS_API_KEY` | _(empty)_ | `AppProperties.newsApiKey` → `NewsApiSource` | |
| `FINNHUB_API_KEY` | _(empty)_ | `AppProperties.finnhubApiKey` → `FinnhubNewsSource` | |
| `GEMINI_API_KEY` | _(empty)_ | `AppProperties.geminiApiKey` → `SentimentScoringService` | |
| `TICKERS` | `SPY,QQQ,TLT,GLD,USO` | `AppProperties.tickers` → `NewsPollerService`, `DashboardController`, `EventInjectorController` | Comma-separated |
| `app.kafka.topic.raw` | `news-raw` | `AppProperties.kafkaTopicRaw` → `NewsPollerService`, `SentimentScoringService` (listener), `EventInjectorController` | |
| `app.kafka.topic.sentiment` | `news-sentiment` | `AppProperties.kafkaTopicSentiment` → `SentimentScoringService` (producer), `AggregatorService` (listener) | |
| `BUY_THRESHOLD` | `0.3` | `AppProperties.buyThreshold` → `AggregatorService` → `EnsembleService` | docker-compose: 0.15 |
| `SELL_THRESHOLD` | `-0.3` | `AppProperties.sellThreshold` → `AggregatorService` → `EnsembleService` | docker-compose: -0.15 |
| `RABBIT_QUEUE_BUY` | `buy-signals` | `AppProperties.rabbitQueueBuy` → `AggregatorService` | Must match `RabbitConfig` |
| `RABBIT_QUEUE_SELL` | `sell-signals` | `AppProperties.rabbitQueueSell` → `AggregatorService` | Must match `RabbitConfig` |
| `DYNAMODB_TABLE` | `trade-signals` | `AppProperties.dynamoDbTable` → `AggregatorService`, `HistoryController` | |
| `USE_LLM_SENTIMENT` | `false` | `AppProperties.useLlmSentiment` → `SentimentScoringService` | Master LLM switch |
| `MOCK_SENTIMENT` | `false` | `@Value` directly in `SentimentScoringService` | Bypasses LLM even if enabled |
| `MAX_LLM_CALLS_PER_MINUTE` | `8` | `AppProperties.maxLlmCallsPerMinute` → `SentimentScoringService` | docker-compose: 12 |
| `LLM_DISABLE_ON_ERROR` | `true` | `AppProperties.llmDisableOnError` → `SentimentScoringService` | Trips circuit breaker on auth/quota errors |
| `POLL_ARTICLES_PER_TICKER` | `1` | `AppProperties.pollArticlesPerTicker` → `NewsPollerService` | docker-compose: 2 |
| `app.poll.interval-ms` | `60000` | `NewsPollerService` `@Scheduled(fixedDelayString=...)` | Via `POLL_INTERVAL_MS` env var |
| `SPY_BIAS` / `QQQ_BIAS` / `TLT_BIAS` / `GLD_BIAS` / `USO_BIAS` | 0.65/0.6/0.45/0.55/0.4 | `TemplateNewsSource` via `@Value` | 0=all bearish, 1=all bullish |

---

## Infrastructure

| Service | Image | Ports | Depends-on | Purpose |
|---|---|---|---|---|
| `kafka` | `apache/kafka-native` | 9092 (HOST), 9093 (DOCKER) | — | Event backbone; `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` |
| `rabbitmq` | `rabbitmq:3-management` | 5672 (AMQP), 15672 (management) | — | Signal queues; healthcheck: `rabbitmq-diagnostics ping` |
| `redis` | `redis:7` | 6379 | — | Strategy state, dedupe, snapshots, recent articles |
| `localstack` | `localstack/localstack:latest` | 4566 | — | DynamoDB (SERVICES=dynamodb); `init-dynamodb.sh` creates table on ready |
| `app` | Built from `Dockerfile` | 8080 | rabbitmq (healthy), kafka, redis, localstack | Spring Boot application |

**Docker network:** `acp_network` (bridge)

**Dockerfile:** Two-stage build.
- Stage 1: `maven:3.9-amazoncorretto-17` — builds `finance-sentiment-1.0.0.jar` via `mvn clean package -DskipTests`.
- Stage 2: `amazoncorretto:17` — copies JAR as `app.jar`, `EXPOSE 8080`, `ENTRYPOINT java -jar app.jar`.

**IMPORTANT:** The base image is `amazoncorretto:17`, not `eclipse-temurin:17`. Do not change this.

---

## Redis Key Schema

| Key Pattern | Type | TTL | Written by | Read by | Description |
|---|---|---|---|---|---|
| `sentiment:{T}:rolling:sum` | String | none | `RollingAverageStrategy.update()` | `RollingAverageStrategy.getScore()` | Cumulative score sum |
| `sentiment:{T}:rolling:count` | String | none | `RollingAverageStrategy.update()` | `RollingAverageStrategy.getScore()`, `AggregatorService.getTickerStats()` | Total article count |
| `sentiment:{T}:ema:value` | String | none | `EmaStrategy.update()` | `EmaStrategy.getScore()`, `MomentumStrategy.update()` | Current EMA (6 decimal places) |
| `sentiment:{T}:window` | List | none | `WindowedAverageStrategy.update()` | `WindowedAverageStrategy.getScore()` | Last 5 scores (trimmed to 5 via LTRIM) |
| `sentiment:{T}:momentum:prev` | String | none | `MomentumStrategy.update()` | `MomentumStrategy.getScore()` | Previous EMA snapshot |
| `sentiment:{T}:momentum:current` | String | none | `MomentumStrategy.update()` | `MomentumStrategy.getScore()` | Current EMA snapshot |
| `sentiment:{T}:last_signal` | String | none | `AggregatorService.aggregate()` | `AggregatorService.aggregate()`, `getTickerStats()` | Last fired signal (BUY or SELL) |
| `recent:{T}` | List | none | `AggregatorService.aggregate()` | `AggregatorService.getRecentArticles()` | Last 5 `SentimentResult` JSON objects (trimmed to 5 on write; 30 read + deduped on read) |
| `history:{T}:snapshots` | List | none | `AggregatorService.aggregate()` | `HistoryController.getSnapshots()`, `getEventTypes()` | Up to 100 ensemble snapshot JSON objects (newest first, trimmed to 100) |
| `dedupe:{hash}` | String | 24 hours | `DedupeChecker.checkAndMark()` | `DedupeChecker.isDuplicate()` | Dedup marker (value "1") |

`{T}` = ticker symbol (SPY, QQQ, TLT, GLD, USO).

**Keys cleared by `POST /api/events/reset`:** `sentiment:{T}:rolling:sum`, `rolling:count`, `ema:value`, `window`, `momentum:prev`, `momentum:current`, `last_signal`, `recent:{T}`, `mock:{T}:pointer` (legacy, never written).

**Keys NOT cleared by reset:** `history:{T}:snapshots`, `dedupe:{hash}` keys.

---

## DynamoDB Schema

**Table name:** `trade-signals` (configurable via `DYNAMODB_TABLE`)

**Billing mode:** `PAY_PER_REQUEST`

| Attribute | Type | Description |
|---|---|---|
| `id` | String (PK) | UUID — partition key |
| `ticker` | String | Instrument symbol (SPY, QQQ, etc.) |
| `signalType` | String | `BUY` or `SELL` |
| `sentimentScore` | String | Ensemble score formatted to 4 decimal places |
| `headline` | String | Triggering article headline |
| `reasoning` | String | Score reasoning (from LLM or local scorer) |
| `timestamp` | String | ISO-8601 UTC instant |
| `ensembleSignal` | String | STRONG BUY / BUY / STRONG SELL / SELL |
| `eventType` | String | EARNINGS / LEGAL / MERGER / MANAGEMENT / REGULATION / PRODUCT / GUIDANCE / MACRO / PARTNERSHIP / OTHER |
| `confidence` | String | Classifier confidence formatted to 2 decimal places |
| `isBreaking` | String | "true" or "false" |
| `horizon` | String | IMMEDIATE / INTRADAY / SWING / UNKNOWN |

---

## RabbitMQ Topology

| Queue | Durable | Publisher | Consumer | Message |
|---|---|---|---|---|
| `buy-signals` | yes | `AggregatorService.fireSignal()` | External downstream (not in this app) | `TradeSignal` JSON |
| `sell-signals` | yes | `AggregatorService.fireSignal()` | External downstream (not in this app) | `TradeSignal` JSON |

No custom exchange — messages sent directly to queue name via `rabbitTemplate.convertAndSend(queue, json)`.
Both queues are declared as durable `Queue` beans in `RabbitConfig`. Default direct exchange is used.

---

## Kafka Topics

| Topic | Producer | Consumer | Key | Value |
|---|---|---|---|---|
| `news-raw` | `NewsPollerService`, `EventInjectorController` | `SentimentScoringService` (group: `sentiment-scorer`, id: `sentiment-scorer`) | ticker symbol | `NewsArticle` JSON |
| `news-sentiment` | `SentimentScoringService` | `AggregatorService` (group: `aggregator`) | ticker symbol | `SentimentResult` JSON |

Topics are auto-created (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`). Do not add `KafkaAdmin` or `NewTopic` beans.

Inside Docker the app connects to Kafka on `kafka:9093` (DOCKER listener).
Outside Docker it connects to `localhost:9092` (HOST listener).

---

## Sentiment Strategy Details

### SentimentStrategy Interface

```java
String getName();           // machine key: "rolling", "ema", "windowed", "momentum"
String getDisplayName();    // human label for dashboard
void update(String ticker, double score, StringRedisTemplate redis);
double getScore(String ticker, StringRedisTemplate redis);
String getSignal(String ticker, StringRedisTemplate redis, double buyThreshold, double sellThreshold);
```

### Strategy Implementations

| Name | Key(s) | Score formula | Signal rule |
|---|---|---|---|
| `rolling` | `rolling:sum`, `rolling:count` | sum / count | BUY if ≥ buyThreshold; SELL if ≤ sellThreshold |
| `ema` | `ema:value` | (new × 0.5) + (prev × 0.5) | BUY if ≥ buyThreshold; SELL if ≤ sellThreshold |
| `windowed` | `window` (list) | avg of last 5 | BUY if ≥ buyThreshold; SELL if ≤ sellThreshold |
| `momentum` | `momentum:prev`, `momentum:current` | current_ema − prev_ema | BUY if delta ≥ 0.03; SELL if delta ≤ −0.03 (FIXED — ignores buyThreshold/sellThreshold) |

### Ensemble Voting (EnsembleService)

```
ensembleScore = (rolling × 0.15) + (ema × 0.35) + (windowed × 0.30) + (momentum × 0.20)

3+ BUY votes  → STRONG BUY
2  BUY votes  → BUY
3+ SELL votes → STRONG SELL
2  SELL votes → SELL
otherwise     → HOLD
```

### Signal Firing Logic (AggregatorService)

A trade signal is published to RabbitMQ and DynamoDB only on transition:
- `ensembleSignal ∈ {BUY, STRONG BUY}` AND `lastSignal != "BUY"` → fire BUY
- `ensembleSignal ∈ {SELL, STRONG SELL}` AND `lastSignal != "SELL"` → fire SELL

`lastSignal` is persisted in Redis key `sentiment:{ticker}:last_signal`.

---

## LLM (Gemini) Integration Details

**URL:** `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent?key={GEMINI_API_KEY}`

**Request format:**
```json
{ "contents": [{ "parts": [{ "text": "<prompt>" }] }] }
```

**Response navigation:** `candidates[0].content.parts[0].text` → strip markdown fences → parse as JSON

**Expected JSON from model:**
```json
{
  "relevant": true,
  "instrument": "SPY",
  "sentiment": "bullish",
  "score": 0.6,
  "reasoning": "rate cut boosts equity demand"
}
```

**Contract enforced in `parseGeminiResponse()`:**
- `relevant=false` → score forced to 0.0; `analysisMode=GEMINI_IRRELEVANT`
- Score clamped to [−1.0, 1.0]
- Any parse/navigation failure → `scoreLocally().withMode("LOCAL_HEURISTIC_FALLBACK")`

**Instrument context map** (injected into prompt):
```
SPY → "broad US equity market / S&P 500 index"
QQQ → "Nasdaq 100 / big tech / growth stocks"
TLT → "long-duration US Treasury bonds / long-term interest rates"
GLD → "gold / safe-haven asset / inflation hedge"
USO → "crude oil / energy market"
```

**Rate limiter:** Fixed 60-second window. `synchronized tryAcquireLlmSlot()` resets counter
when `now − windowStart ≥ 60 s`. When cap is reached: pause Kafka listener via
`KafkaListenerEndpointRegistry.getListenerContainer("sentiment-scorer")`, schedule resume
after `msUntilWindowReset() + 1000 ms`.

**Circuit breaker:** `AtomicBoolean llmDisabled`. Trips (permanently until restart) when
`props.llmDisableOnError=true` AND HTTP status is 401/403/429 OR response body contains
"quota", "billing", "credit", "limit", "insufficient", or "overload".

---

## LocalHeuristicAnalyser Details

**Event type keyword scoring:** For each of 9 categories (OTHER is catch-all), count keyword
matches in the normalised headline. Highest-scoring category wins. Falls back to OTHER if all
scores are 0.

**Confidence formula:** `min(0.95, maxKeywordHits > 0 ? 0.4 + (maxKeywordHits × 0.15) : 0.3)`

**Horizon detection (first match wins):**
1. IMMEDIATE: breaking, crash, surge, collapse, soars, plunges, halt, emergency, urgent, alert
2. INTRADAY: today, session, trading, quarterly, q1-q4, beats, misses, reports, announces
3. SWING: full-year, annual, strategic, outlook, guidance, forecast, long-term, raises, lowers
4. UNKNOWN (default)

**isBreaking keywords:** breaking, crash, collapse, fraud, arrested, bankruptcy, halt, recall,
emergency, catastrophic

**Normalisation** (`NewsArticle.normalise()`): lowercase, remove all non-alphanumeric characters.

---

## DedupeChecker Details

- Hash key: `dedupe:{contentHash}` where `contentHash = NewsArticle.computeHash(normalise(title))`
- `computeHash()`: SHA-256 of normalised string → hex → first 16 characters
- TTL: 24 hours
- `checkAndMark()`: atomic check-then-set; returns `true` if already seen
- Catch-all error handling: returns `false` (non-duplicate) on any Redis error to avoid data loss

---

## NewsSource Implementations

### Switching logic

```java
@ConditionalOnProperty(name="NEWS_SOURCE", havingValue="template", matchIfMissing=true)
// TemplateNewsSource — active when NEWS_SOURCE=template or env var absent

@ConditionalOnProperty(name="NEWS_SOURCE", havingValue="newsapi")
// NewsApiSource

@ConditionalOnProperty(name="NEWS_SOURCE", havingValue="finnhub")
// FinnhubNewsSource
```

### TemplateNewsSource

Generates synthetic headlines using per-instrument `BULLISH_TEMPLATES`, `BEARISH_TEMPLATES`,
`NEUTRAL_TEMPLATES` maps (7/7/4 headlines per instrument). For each article:
- 10% probability → NEUTRAL pool
- Otherwise → BULLISH if `random < bias`, else BEARISH

`source` field: `"TemplateNews"` → SentimentScoringService maps this to `sourceMode="LOCAL"`.

### NewsApiSource

Uses `INSTRUMENT_QUERIES` map with rich macro query strings per instrument.
Uses `UriComponentsBuilder.build(false)` to avoid double-encoding query syntax.
Does NOT set `sourceMode` on articles; SentimentScoringService fallback assigns `"EXTERNAL"`
because the source name is not "TemplateNews" or "MacroEventSimulator".

### FinnhubNewsSource

Fetches company-news for the last 2 days via Finnhub REST API.
Sets `sourceMode="EXTERNAL"`, `articleId` (UUID), `ingestedAt`, `contentHash` at fetch time.
Silently skips the cycle on HTTP 429 (rate limit).

---

## Dashboard Frontend Details

- **Template:** `src/main/resources/templates/dashboard.html` (Thymeleaf + vanilla JS)
- **Polling:** JavaScript polls `/api/dashboard/stats` and `/api/dashboard/signals` every 3 seconds
- **Chart.js:** History line chart in dropdown panel (`animation: false` to avoid flicker on refresh)
- **`statsRendered` flag:** prevents duplicate event listener binding across poll cycles
- **Gemini Analysis indicator:** dot activates when any recent article has
  `analysisMode === 'GEMINI'` or `analysisMode === 'GEMINI_IRRELEVANT'`
- **Event simulator panel:** buttons for 4 pre-configured scenarios; shows alert if in Finnhub mode

---

## REST Endpoints Quick Reference

### DashboardController

| Method | Path | Returns |
|---|---|---|
| GET | `/` | dashboard.html |
| GET | `/api/dashboard/scores` | `Map<String, Double>` |
| GET | `/api/dashboard/signals?limit=20` | `List<TradeSignal>` (in-memory, since startup) |
| GET | `/api/dashboard/stats` | `List<Map>` (ticker, ensembleSignal, ensembleScore, buyVotes, sellVotes, holdVotes, strategies, articleCount, lastSignal) |
| GET | `/api/dashboard/ticker/{ticker}/recent` | `List<SentimentResult>` (5 unique, deduped) |
| POST | `/api/dashboard/trigger-poll` | `{status, message}` |

### HistoryController

| Method | Path | Returns |
|---|---|---|
| GET | `/api/history/ticker/{ticker}/snapshots` | `List<Map>` (up to 100, newest first) |
| GET | `/api/history/signals` | `List<Map>` (DynamoDB scan, limit 50, newest first) |
| GET | `/api/history/signals/{ticker}` | `List<Map>` (DynamoDB filter by ticker, limit 20) |
| GET | `/api/history/eventtypes/{ticker}` | `Map<String, Integer>` |

### EventInjectorController

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/events/scenarios` | — | `List` of 4 scenario maps |
| POST | `/api/events/inject` | `{ticker, sentiment, articleCount}` | `{status, ticker, sentiment, articlesInjected, message}` — HTTP 400 if `NEWS_SOURCE=finnhub` |
| POST | `/api/events/reset` | `{ticker}` | `{status, message}` |

---

## Build and Run Commands

```bash
# Start full stack (rebuild everything)
docker-compose up --build

# Rebuild only the Spring app (faster iteration)
docker-compose up --build app

# View live app logs
docker logs cw3-app -f

# Check Redis keys
docker exec -it redis redis-cli
KEYS *
LRANGE history:SPY:snapshots 0 -1
GET sentiment:SPY:ema:value

# Trigger a manual news poll
curl -X POST http://localhost:8080/api/dashboard/trigger-poll

# Reset all ticker state (clears Redis strategy keys; does NOT clear history snapshots)
curl -X POST http://localhost:8080/api/events/reset \
  -H "Content-Type: application/json" \
  -d '{"ticker":"ALL"}'

# Inject a test event (disabled when NEWS_SOURCE=finnhub)
curl -X POST http://localhost:8080/api/events/inject \
  -H "Content-Type: application/json" \
  -d '{"ticker":"GLD","sentiment":"EUPHORIA","articleCount":8}'

# Check DynamoDB signal log
curl http://localhost:8080/api/history/signals | jq .

# Clear history snapshots for a ticker (not done by reset endpoint)
docker exec -it redis redis-cli DEL history:SPY:snapshots
```

---

## Known Gotchas

1. **Apple Silicon / Corretto base image** — Dockerfile uses `amazoncorretto:17`. Do not
   switch to `eclipse-temurin:17`; it has known ARM compatibility issues in this context.

2. **RabbitMQ healthcheck ordering** — The `app` service has `condition: service_healthy`
   on RabbitMQ. If RabbitMQ takes more than 50 s to start (5 retries × 10 s interval),
   Docker will abort. On slow machines, increase `retries`.

3. **Kafka listener ID** — `SentimentScoringService` declares `@KafkaListener(id = "sentiment-scorer", ...)`.
   The `LISTENER_ID` constant in the same class must match this string exactly — it is used
   to look up the container in `KafkaListenerEndpointRegistry` for pause/resume.

4. **Kafka topic names** — `news-raw` and `news-sentiment` are the defaults in `AppProperties`.
   They are not explicitly set in docker-compose (they use code defaults). Do not rename them
   without updating both `AppProperties` defaults and all `@KafkaListener` groupId references.

5. **Momentum threshold** — `MomentumStrategy.getSignal()` uses hardcoded ±0.03 thresholds,
   not `buyThreshold` / `sellThreshold`. This is intentional — Momentum measures rate of change.

6. **History snapshots not cleared on reset** — `POST /api/events/reset` does NOT delete
   `history:{ticker}:snapshots` keys. Clear manually via `redis-cli DEL`.

7. **`mock:{ticker}:pointer`** — The reset endpoint deletes this key, but no code ever writes it.
   It is a harmless legacy artifact.

8. **`MOCK_NEWS` in application.properties** — Defined but not read by any Java class. Inert.

9. **DynamoDB table must pre-exist** — `init-dynamodb.sh` creates the `trade-signals` table via
   LocalStack's init hook. If LocalStack starts but the script fails, `AggregatorService` will
   throw `ResourceNotFoundException` on the first signal.

10. **No Spring Security** — All endpoints are open by design (automarker requirement). Do not
    add Spring Security or any auth filter.

11. **No test plugins in pom.xml** — `mvn test` will not produce useful output. Do not run it.

12. **Chart.js polling pitfall** — `animation: false` is set on the history chart; without it,
    the chart re-animates on every 3-second poll, causing visual flicker.

---

## How to Add a New NewsSource

1. Create a class implementing `NewsSource` in `com.acp.finance.news`.
2. Annotate with `@Component` and `@ConditionalOnProperty(name="NEWS_SOURCE", havingValue="your-value")`.
3. Implement `getLatestHeadlines(String ticker, int count)`.
4. Set `article.setSourceMode("EXTERNAL")` (or `"LOCAL"` if synthetic).
5. Add the new `havingValue` to the `NEWS_SOURCE` documentation in README.md and CLAUDE.md.
6. No other changes required — Spring auto-wires the new implementation.

---

## How to Add a New Aggregation Strategy

1. Create a class implementing `SentimentStrategy` in `com.acp.finance.strategy`.
2. Choose new Redis key(s) following the `sentiment:{ticker}:yourname:*` convention.
3. Implement `getName()` (machine key), `getDisplayName()` (dashboard label), `update()`,
   `getScore()`, and `getSignal()`.
4. Register the new strategy as a `@Bean` in `StrategyConfig`. Spring auto-injects
   `List<SentimentStrategy>` into `EnsembleService` and `AggregatorService`.
5. Update `EventInjectorController.reset()` to delete the new Redis key(s).
6. Update ensemble score weights in `EnsembleService.evaluate()` to include the new strategy.
7. Update the Redis Key Schema in README.md and CLAUDE.md.

---

## What NOT to Change

- **Kafka topic names** (`news-raw`, `news-sentiment`) — hardcoded as defaults; changing breaks the pipeline.
- **RabbitMQ queue names** (`buy-signals`, `sell-signals`) — declared as durable beans; changing orphans messages.
- **Port 8080** — hardcoded in docker-compose and expected by the automarker.
- **Authentication** — do not add Spring Security or any auth filter.
- **DynamoDB table name** (`trade-signals`) — must match `init-dynamodb.sh`.
- **Kafka topic auto-creation** — do not add `KafkaAdmin` or `NewTopic` beans.
- **Java version** — pom.xml requires Java 17.
- **`@KafkaListener(id = "sentiment-scorer", ...)` ID** — must match `LISTENER_ID` constant in
  `SentimentScoringService` for pause/resume to work.
- **Gemini model name** (`gemini-3.1-flash-lite-preview`) — hardcoded in `GEMINI_URL_TEMPLATE`
  in `SentimentScoringService`; update both the constant and documentation if changing.
