# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Summary

Real-Time Market Event Intelligence and Strategy Comparison Platform. Ingests financial news
headlines (live or mock), deduplicates and classifies them by event type and horizon, scores
them with four independent sentiment strategies and an ensemble, fires BUY/SELL signals to
RabbitMQ, and persists them to DynamoDB. A live Thymeleaf dashboard at port 8080 shows all
four strategies side-by-side, their ensemble vote, history chart, and event type distribution.

## Tech Stack

- **Java 17**, **Spring Boot 3.2.0** — main framework; `@EnableScheduling` for poller
- **Apache Kafka** (`apache/kafka-native`) — event backbone (news-raw, news-sentiment topics)
- **Redis 7** — strategy state, dedupe hashes, history snapshots, recent articles
- **RabbitMQ 3** — signal delivery (buy-signals, sell-signals durable queues)
- **DynamoDB via LocalStack** — persistent signal audit log (trade-signals table)
- **Docker Compose** — full local stack (kafka, rabbitmq, redis, localstack, app)
- **Chart.js 4.4.0** — history chart in the dropdown panel of dashboard.html
- **Thymeleaf** — dashboard.html templating
- **AWS SDK v2 (2.21.0)** — DynamoDbClient
- **Lombok** — model boilerplate

## Package Structure

```
com.acp.finance
├── FinanceSentimentApplication.java      — @SpringBootApplication + @EnableScheduling
│
├── config/
│   ├── AppProperties.java                — all @Value-injected env vars as a @Component
│   ├── DynamoDbConfig.java               — DynamoDbClient bean (points to DYNAMODB_ENDPOINT)
│   ├── RabbitConfig.java                 — declares buy-signals + sell-signals Queue beans
│   └── StrategyConfig.java               — registers 4 Strategy beans + EnsembleService
│
├── model/
│   ├── NewsArticle.java                  — raw article (ticker, title, description, publishedAt,
│   │                                       source, articleId, ingestedAt, contentHash, sourceMode)
│   │                                       + static normalise() and computeHash() helpers
│   ├── SentimentResult.java              — scored article (adds score, reasoning, eventType,
│   │                                       horizon, confidence, isBreaking, deduped,
│   │                                       articleId, contentHash, sourceMode, analysisMode)
│   └── TradeSignal.java                  — fired signal (id UUID, ticker, signalType,
│                                           sentimentScore, triggeringHeadline, reasoning, timestamp)
│
├── news/
│   ├── NewsSource.java                   — interface: getLatestHeadlines(ticker, count) → List<NewsArticle>
│   ├── NewsApiSource.java                — newsapi.org; active when NEWS_SOURCE=newsapi
│   ├── FinnhubNewsSource.java            — finnhub.io company news (past 2 days); active when NEWS_SOURCE=finnhub
│   └── TemplateNewsSource.java           — synthetic headlines; active when NEWS_SOURCE=template (default)
│                                           uses AAPL_BIAS / TSLA_BIAS / MSFT_BIAS / GOOGL_BIAS
│
├── analysis/
│   ├── LocalHeuristicAnalyser.java       — keyword-based event classification (10 types),
│   │                                       horizon detection, confidence, isBreaking;
│   │                                       always runs regardless of MOCK_SENTIMENT setting
│   └── DedupeChecker.java                — SHA-256 content hash → Redis "dedupe:{hash}" key, TTL 24h
│
├── strategy/
│   ├── SentimentStrategy.java            — interface: getName, getDisplayName, update, getScore, getSignal
│   ├── RollingAverageStrategy.java       — lifetime sum/count in Redis; keys: rolling:sum, rolling:count
│   ├── EmaStrategy.java                  — EMA α=0.3; key: ema:value
│   ├── WindowedAverageStrategy.java      — last 10 scores Redis list; key: window
│   ├── MomentumStrategy.java             — EMA delta; keys: momentum:prev, momentum:current
│   └── EnsembleService.java              — aggregates votes → STRONG BUY/BUY/SELL/STRONG SELL/HOLD
│                                           weighted score: rolling×0.15 + ema×0.35 + windowed×0.30 + momentum×0.20
│
└── service/ + controller/
    ├── NewsPollerService.java            — @Scheduled(fixedDelay); polls NewsSource; publishes to news-raw
    ├── SentimentScoringService.java      — @KafkaListener(news-raw); dedupes; scores; publishes to news-sentiment
    ├── AggregatorService.java            — @KafkaListener(news-sentiment); updates strategies; fires signals
    ├── DashboardController.java          — GET /  + all /api/dashboard/* endpoints
    ├── HistoryController.java            — all /api/history/* endpoints (snapshots, signals, eventtypes)
    └── EventInjectorController.java      — all /api/events/* endpoints (inject, scenarios, reset)
```

## Key Design Decisions

### NewsSource interface
Three implementations are selected at startup via `@ConditionalOnProperty(name="NEWS_SOURCE", havingValue=...)`:
- `havingValue="template", matchIfMissing=true` → `TemplateNewsSource` (default)
- `havingValue="newsapi"` → `NewsApiSource`
- `havingValue="finnhub"` → `FinnhubNewsSource`

To add a new news source: implement `NewsSource`, annotate with `@ConditionalOnProperty(name="NEWS_SOURCE", havingValue="your-value")`, and add the value to the `NEWS_SOURCE` documentation.

### Strategy pattern
Four strategies exist so the dashboard can compare behavior side by side. Each strategy is
fully independent — no strategy reads another's Redis keys. EnsembleService is injected with
all four as a `List<SentimentStrategy>` and does majority voting. Strategy weights are
hardcoded in EnsembleService (not configurable by env var — intentional simplicity).

### Redis key naming
All strategy keys use the prefix `sentiment:{TICKER}:`. Recent articles use `recent:{TICKER}`.
History uses `history:{TICKER}:snapshots`. Dedupe uses `dedupe:{hash}`.
Do not add new key patterns without updating both AggregatorService and EventInjectorController
(the reset endpoint must clear all keys for a ticker).

### Why RabbitMQ for signals, not Kafka
Signals are meant to trigger downstream actions (e.g., order execution systems). RabbitMQ's
queue-based routing with durable queues and per-message acknowledgement is more appropriate
than Kafka for this "command" pattern. Kafka handles the streaming data pipeline.

### Why DynamoDB, not Redis
Redis is ephemeral (cleared on reset). DynamoDB is the persistent audit log of all signals.
HistoryController reads from DynamoDB for the `/api/history/signals` endpoint.

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
LRANGE history:AAPL:snapshots 0 -1

# Trigger a manual news poll
curl -X POST http://localhost:8080/api/dashboard/trigger-poll

# Reset all ticker state (clears Redis strategy keys)
curl -X POST http://localhost:8080/api/events/reset \
  -H "Content-Type: application/json" \
  -d '{"ticker":"ALL"}'

# Inject a test event
curl -X POST http://localhost:8080/api/events/inject \
  -H "Content-Type: application/json" \
  -d '{"ticker":"TSLA","sentiment":"CRASH","articleCount":5}'

# Check DynamoDB signal log
curl http://localhost:8080/api/history/signals | jq .
```

## REST Endpoints Reference

### /api/dashboard
| Method | Path | Returns |
|---|---|---|
| GET | `/` | dashboard.html (Thymeleaf) |
| GET | `/api/dashboard/scores` | `Map<String, Double>` ensemble score per ticker |
| GET | `/api/dashboard/signals?limit=20` | `List<TradeSignal>` |
| GET | `/api/dashboard/stats` | `List<Map>` — ticker, ensembleSignal, ensembleScore, buyVotes, sellVotes, holdVotes, strategies, articleCount, lastSignal |
| GET | `/api/dashboard/ticker/{ticker}/recent` | `List<SentimentResult>` (last 5) |
| POST | `/api/dashboard/trigger-poll` | `{status, message}` |

### /api/history
| Method | Path | Returns |
|---|---|---|
| GET | `/api/history/ticker/{ticker}/snapshots` | `List<Map>` — up to 100 snapshots (newest first) |
| GET | `/api/history/signals` | `List<Map>` — DynamoDB scan, limit 50, sorted newest first |
| GET | `/api/history/signals/{ticker}` | `List<Map>` — DynamoDB filter by ticker, limit 20 |
| GET | `/api/history/eventtypes/{ticker}` | `Map<String, Integer>` — event type counts from snapshots |

### /api/events
| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/events/scenarios` | — | `List` of 6 pre-configured scenarios |
| POST | `/api/events/inject` | `{ticker, sentiment, articleCount}` | `{status, ticker, sentiment, articlesInjected, message}` |
| POST | `/api/events/reset` | `{ticker}` ("ALL" or specific) | `{status, message}` |

## Redis Key Reference

| Key | Type | Description |
|---|---|---|
| `sentiment:{T}:rolling:sum` | String | Cumulative score sum |
| `sentiment:{T}:rolling:count` | String | Total article count |
| `sentiment:{T}:ema:value` | String | Current EMA (α=0.3) |
| `sentiment:{T}:window` | List | Last 10 scores (trimmed) |
| `sentiment:{T}:momentum:prev` | String | Previous EMA for delta calc |
| `sentiment:{T}:momentum:current` | String | Current EMA alias |
| `sentiment:{T}:last_signal` | String | Last signal type (BUY/SELL/HOLD) |
| `recent:{T}` | List | Last 5 SentimentResult JSON objects |
| `history:{T}:snapshots` | List | Last 100 ensemble snapshot JSON objects |
| `dedupe:{hash}` | String | Dedup marker ("1"), TTL 24 h |

## Environment Variables

| Variable | App Default | docker-compose | Required | Notes |
|---|---|---|---|---|
| `NEWS_SOURCE` | `template` | `finnhub` | No | `template`, `newsapi`, or `finnhub` |
| `NEWS_API_KEY` | _(empty)_ | _(empty)_ | Only if `NEWS_SOURCE=newsapi` | newsapi.org |
| `FINNHUB_API_KEY` | _(empty)_ | _(empty)_ | Only if `NEWS_SOURCE=finnhub` | finnhub.io |
| `ANTHROPIC_API_KEY` | _(empty)_ | _(empty)_ | Only if `MOCK_SENTIMENT=false` | claude-haiku-4-5-20251001 |
| `MOCK_SENTIMENT` | `false` | `true` | No | `true` = LocalHeuristicAnalyser only |
| `AAPL_BIAS` | `0.8` | `0.8` | No | Template mode sentiment bias (0–1 scale) |
| `TSLA_BIAS` | `0.2` | `0.2` | No | Template mode sentiment bias |
| `MSFT_BIAS` | `0.65` | `0.65` | No | Template mode sentiment bias |
| `GOOGL_BIAS` | `0.5` | `0.5` | No | Template mode sentiment bias |
| `BUY_THRESHOLD` | `0.3` | `0.15` | No | Ensemble score to trigger BUY signal |
| `SELL_THRESHOLD` | `-0.3` | `-0.15` | No | Ensemble score to trigger SELL signal |
| `POLL_INTERVAL_MS` | `60000` | `30000` | No | Polling interval in ms |
| `TICKERS` | `AAPL,TSLA,MSFT,GOOGL` | `AAPL,TSLA,MSFT,GOOGL` | No | Comma-separated list |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `kafka:9093` | No | Set by docker-compose |
| `REDIS_HOST` | `localhost` | `redis` | No | Set by docker-compose |
| `RABBITMQ_HOST` | `localhost` | `rabbitmq` | No | Set by docker-compose |
| `DYNAMODB_ENDPOINT` | _(none)_ | `http://localstack:4566` | No | Set by docker-compose |
| `AWS_ACCESS_KEY` | `test` | `test` | No | Dummy value for LocalStack |
| `AWS_SECRET_KEY` | `test` | `test` | No | Dummy value for LocalStack |

## Known Issues and Constraints

- **Java 17 required** — pom.xml sets `<java.version>17</java.version>`. Do not change this.
- **No authentication** — all endpoints are open by design (automarker requirement). Do not add Spring Security.
- **DynamoDB table must exist before app starts** — `init-dynamodb.sh` handles this for LocalStack. If the table is missing, `AggregatorService` will throw on the first signal.
- **Redis dedupe TTL is 24 hours** — to clear dedupe state without restarting Redis, use `docker exec -it redis redis-cli DEL dedupe:{hash}` or restart the Redis container.
- **No test plugins in pom.xml** — there are no configured test or lint plugins. Do not run `mvn test` expecting output.
- **Kafka topic auto-creation is enabled** — `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` is set in docker-compose. Do not use the Kafka Admin API to create topics manually.
- **Event injection disabled in Finnhub mode** — `POST /api/events/inject` returns HTTP 400 when `NEWS_SOURCE=finnhub`. Switch to `template` mode to use the injector.
- **History snapshots not cleared on reset** — `POST /api/events/reset` clears all strategy Redis keys but does not delete `history:{T}:snapshots`. To clear history, use `redis-cli DEL history:{T}:snapshots` directly.

## What NOT to Change

- **Kafka topic names** — `news-raw` and `news-sentiment` are hardcoded in `AppProperties` defaults and `@KafkaListener` group IDs. Changing them breaks the pipeline.
- **RabbitMQ queue names** — `buy-signals` and `sell-signals` are declared as `Queue` beans in `RabbitConfig`. Changing them orphans existing messages.
- **Port 8080** — hardcoded in docker-compose and expected by the automarker.
- **Authentication** — do not add Spring Security or any auth filter.
- **Kafka Admin API** — topics are auto-created; do not add `KafkaAdmin` or `NewTopic` beans.
- **DynamoDB table name** — `trade-signals` is the default; changing it requires updating both `AppProperties` and `init-dynamodb.sh`.
