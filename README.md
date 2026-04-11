# Real-Time Macro Market Sentiment Pipeline

A real-time financial news pipeline that ingests headlines for five macro instruments
(SPY, QQQ, TLT, GLD, USO), classifies market events, scores sentiment across four
independent strategies, and compares them live on a dark-mode Thymeleaf dashboard.
Trade signals (BUY/SELL) are fired to RabbitMQ and persisted to DynamoDB the moment
the ensemble of strategies reaches a decision. The system runs fully offline by default
and can be switched to live news (Finnhub) and LLM scoring (Gemini) via environment
variables.

---

## Architecture

```
News Source
(template / newsapi / finnhub)
          │
          ▼
  NewsPollerService ──publishes──► Kafka: news-raw
  (@Scheduled, per ticker)                │
                                          ▼
                               SentimentScoringService
                               ├─ DedupeChecker (Redis: dedupe:{hash}, TTL 24h)
                               ├─ LocalHeuristicAnalyser (always runs — event type,
                               │   horizon, confidence, isBreaking)
                               └─ Gemini API (if USE_LLM_SENTIMENT=true)
                                   • Rate limiter (fixed 60-s window)
                                   • Kafka listener pause/resume
                                   • Fail-closed circuit breaker
                                          │
                                          ▼
                                Kafka: news-sentiment
                                          │
                                          ▼
                                  AggregatorService
                                  ├─ 4 strategies → Redis keys
                                  │   ├─ RollingAverage
                                  │   ├─ EMA (α = 0.3)
                                  │   ├─ WindowedAverage (last 10)
                                  │   └─ Momentum (EMA delta)
                                  ├─ EnsembleService (vote → signal)
                                  ├─ History snapshots → Redis (up to 100)
                                  ├─ Recent articles  → Redis (last 5, deduped)
                                  ├─ BUY/SELL ─────────► RabbitMQ
                                  │                       (buy-signals / sell-signals)
                                  └─ Trade signals ────► DynamoDB (trade-signals)
                                          │
                                          ▼
                            DashboardController (port 8080)
                            REST API + Thymeleaf dashboard
```

**Cross-cutting store: Redis** — strategy state, dedupe hashes, recent articles, history snapshots.

---

## Architecture Simpler 

```text
News Source
(template / finnhub)
        │
        ▼
NewsPollerService
        │
        ▼
Kafka: news-raw
        │
        ▼
SentimentScoringService
(deduplication, event classification,
 heuristic or Gemini sentiment)
        │
        ▼
Kafka: news-sentiment
        │
        ▼
AggregatorService
(4 strategies + ensemble signal)
   ├──────────────► Redis
   │                (state, recent articles, history)
   ├──────────────► RabbitMQ
   │                (BUY / SELL signals)
   └──────────────► DynamoDB
                    (signal audit log)
        │
        ▼
DashboardController
REST API + dashboard
```
---

## Technology Roles

| Technology | Role |
|---|---|
| **Kafka** | Event backbone. Two topics: `news-raw` (raw articles) and `news-sentiment` (scored results). Decouples polling → scoring → aggregation. |
| **Redis** | Strategy state (6 keys per ticker), dedupe hashes (24-h TTL), sentiment history snapshots (up to 100 per ticker), recent articles (last 5 per ticker). |
| **RabbitMQ** | Signal delivery. `buy-signals` and `sell-signals` durable queues receive trade signals for downstream consumption. |
| **DynamoDB** | Persistent signal audit log. `trade-signals` table (PK: `id` UUID) stores every BUY/SELL event with full enrichment fields. Backed by LocalStack locally. |
| **Spring Boot** | REST service on port 8080. Hosts Thymeleaf dashboard, all `/api/*` endpoints, and all background services. |

---

## News Source Modes

Selected by the `NEWS_SOURCE` environment variable.

| `NEWS_SOURCE` | Source | Notes |
|---|---|---|
| `template` (default) | `TemplateNewsSource` — synthetic headlines generated from per-ticker bias weights (`SPY_BIAS`, etc.) | No API key required; fully offline |
| `newsapi` | `NewsApiSource` — live macro headlines from newsapi.org | Requires `NEWS_API_KEY`; uses rich macro query strings per instrument |
| `finnhub` | `FinnhubNewsSource` — live company news from finnhub.io (past 2 days) | Requires `FINNHUB_API_KEY`; event injection (`/api/events/inject`) is disabled in this mode |

---

## Sentiment Scoring

All articles are first run through `LocalHeuristicAnalyser` (keyword-based event classification,
always runs). Optionally, Gemini is called for directional sentiment scoring.

| `USE_LLM_SENTIMENT` | `GEMINI_API_KEY` | Scoring path |
|---|---|---|
| `false` (default) | — | `LocalHeuristicAnalyser` only; `analysisMode = LOCAL_HEURISTIC` |
| `true` | Set | Gemini API with macro-instrument-aware prompt; local fallback on failure |
| `true` | Missing | Warns at startup; falls back to local for every message |

`MOCK_SENTIMENT=true` forces local scoring even when `USE_LLM_SENTIMENT=true`.

### Analysis Modes

| `analysisMode` | Meaning |
|---|---|
| `GEMINI` | Gemini classified the headline as relevant to the instrument |
| `GEMINI_IRRELEVANT` | Gemini judged headline irrelevant; score forced to 0.0 |
| `LOCAL_HEURISTIC` | Keyword-based local scoring |
| `LOCAL_HEURISTIC_FALLBACK` | Gemini call failed; fell back to local |
| `DEDUPED` | Duplicate headline; score 0.0, not fed to strategies |

### Gemini LLM Safety Controls

| Control | Env Var | Default | docker-compose |
|---|---|---|---|
| Master switch | `USE_LLM_SENTIMENT` | `false` | `true` |
| Rate cap | `MAX_LLM_CALLS_PER_MINUTE` | `8` | `12` |
| Fail-closed circuit breaker | `LLM_DISABLE_ON_ERROR` | `true` | `true` |

When the rate cap is reached, the Kafka listener is paused until the 60-second window resets,
so unprocessed messages stay buffered in Kafka rather than accumulating in heap.
The circuit breaker trips permanently (until restart) on HTTP 401 / 403 / 429 or billing-related
response bodies, and routes all subsequent messages to local scoring.

---

## Sentiment Strategies

All four strategies share the `SentimentStrategy` interface. Each independently produces
a score (−1 to +1) and a BUY/SELL/HOLD vote. `EnsembleService` combines them.

| # | Strategy | Display Name | Algorithm | Redis Keys |
|---|---|---|---|---|
| 1 | `rolling` | Rolling Average | Cumulative sum ÷ count — lifetime average | `sentiment:{T}:rolling:sum`, `sentiment:{T}:rolling:count` |
| 2 | `ema` | EMA (α=0.5) | Exponential moving average, α=0.5 — recent scores weighted more | `sentiment:{T}:ema:value` |
| 3 | `windowed` | Windowed (last 5) | Average of last 5 scores — short-term view | `sentiment:{T}:window` (Redis list) |
| 4 | `momentum` | Momentum | Delta between current EMA and previous EMA — rate of change | `sentiment:{T}:momentum:prev`, `sentiment:{T}:momentum:current` |

Note: Momentum uses fixed ±0.03 signal thresholds; the other three use `BUY_THRESHOLD` /
`SELL_THRESHOLD`.

### Ensemble Voting

```
Ensemble score = (rolling × 0.15) + (ema × 0.35) + (windowed × 0.30) + (momentum × 0.20)

BUY votes  = count of strategies where score ≥ BUY_THRESHOLD  (app default 0.3; docker-compose 0.15)
SELL votes = count of strategies where score ≤ SELL_THRESHOLD (app default −0.3; docker-compose −0.15)
Momentum votes use fixed ±0.03 threshold (not configurable)

3+ BUY votes  → STRONG BUY    2 BUY votes  → BUY
3+ SELL votes → STRONG SELL   2 SELL votes → SELL
otherwise     → HOLD
```

A BUY/SELL signal is fired to RabbitMQ and DynamoDB only when the ensemble recommendation
changes (i.e. the last fired signal was not already BUY or SELL respectively).

---

## Structured Event Analysis

Every article is enriched by `LocalHeuristicAnalyser` with:

### Event Types (10)

| Event Type | Keywords (partial list) |
|---|---|
| EARNINGS | earnings, revenue, profit, eps, beats, misses |
| LEGAL | lawsuit, fraud, fine, penalty, court |
| MERGER | acquisition, merger, takeover, deal |
| MANAGEMENT | ceo, cfo, resign, appoints, steps down |
| REGULATION | regulation, antitrust, sec, probe |
| PRODUCT | launch, product, release, breakthrough |
| GUIDANCE | guidance, outlook, forecast, raises, warns |
| MACRO | inflation, interest rate, fed, recession, gdp |
| PARTNERSHIP | partnership, agreement, joint, alliance |
| OTHER | catch-all for unclassified headlines |

### Additional Enrichment Fields

| Field | Values | Description |
|---|---|---|
| `horizon` | IMMEDIATE / INTRADAY / SWING / UNKNOWN | Expected time-to-impact |
| `confidence` | 0.3 – 0.95 | Keyword hit density; 0.4 + (maxKeywordHits × 0.15) |
| `isBreaking` | true / false | Matched breaking/crash/collapse/fraud/bankruptcy etc. |
| `deduped` | true / false | Duplicate of a previously seen article (SHA-256 content hash, 24-h Redis TTL) |

---

## How to Run

### Prerequisites

- Docker + Docker Compose
- (Optional) [Finnhub](https://finnhub.io) API key — only needed if `NEWS_SOURCE=finnhub`
- (Optional) [NewsAPI](https://newsapi.org) key — only needed if `NEWS_SOURCE=newsapi`
- (Optional) [Gemini](https://aistudio.google.com) API key — only needed if `USE_LLM_SENTIMENT=true`

### Steps

```bash
# 1. Clone the repo
git clone <repo-url>
cd cw3

# 2. Create .env file (defaults run fully on mock data — no API keys needed)
cat > .env <<EOF
FINNHUB_API_KEY=your_key_or_leave_blank
NEWS_API_KEY=your_key_or_leave_blank
GEMINI_API_KEY=your_key_or_leave_blank
EOF

# 3. Start the full stack
docker-compose up --build
```

Wait ~30 seconds for Kafka and RabbitMQ to initialise, then:

| URL | What you see |
|---|---|
| `http://localhost:8080` | Live dashboard |
| `http://localhost:15672` | RabbitMQ management UI (guest / guest) |

### Trigger a Manual Poll

```bash
curl -X POST http://localhost:8080/api/dashboard/trigger-poll
```

### Inject a Market Event

```bash
# Inject 5 CRASH articles for SPY
curl -X POST http://localhost:8080/api/events/inject \
  -H "Content-Type: application/json" \
  -d '{"ticker":"SPY","sentiment":"CRASH","articleCount":5}'
```

Sentiment values: `CRASH`, `BEARISH`, `NEUTRAL`, `BULLISH`, `EUPHORIA`

### Reset Ticker State

```bash
# Reset all tickers (clears Redis strategy keys; does NOT clear history snapshots)
curl -X POST http://localhost:8080/api/events/reset \
  -H "Content-Type: application/json" \
  -d '{"ticker":"ALL"}'
```

---

## Configuration Reference

All variables can be set in `.env` or as environment variables. The App Default is what
the Spring Boot application code uses when no override is provided. Where docker-compose
explicitly overrides a default, both values are shown.

| Variable | App Default | docker-compose | Required | Description |
|---|---|---|---|---|
| `NEWS_SOURCE` | `template` | `finnhub` | No | `template`, `newsapi`, or `finnhub` |
| `NEWS_API_KEY` | _(empty)_ | _(empty)_ | Only if `NEWS_SOURCE=newsapi` | newsapi.org API key |
| `FINNHUB_API_KEY` | _(empty)_ | _(empty)_ | Only if `NEWS_SOURCE=finnhub` | finnhub.io API key |
| `GEMINI_API_KEY` | _(empty)_ | _(empty)_ | Only if `USE_LLM_SENTIMENT=true` | Gemini API key (model: `gemini-3.1-flash-lite-preview`) |
| `USE_LLM_SENTIMENT` | `false` | `true` | No | `true` = call Gemini API; `false` = local scoring only |
| `MOCK_SENTIMENT` | `false` | `false` | No | `true` forces local scoring even if `USE_LLM_SENTIMENT=true` |
| `MAX_LLM_CALLS_PER_MINUTE` | `8` | `12` | No | Hard cap on Gemini calls per 60-second window |
| `LLM_DISABLE_ON_ERROR` | `true` | `true` | No | Trip circuit breaker on 401/403/429 or billing errors |
| `TICKERS` | `SPY,QQQ,TLT,GLD,USO` | `SPY,QQQ,TLT,GLD,USO` | No | Comma-separated instruments to track |
| `SPY_BIAS` | `0.65` | `0.65` | No | Template mode bullish probability for SPY (0=all bearish, 1=all bullish) |
| `QQQ_BIAS` | `0.6` | `0.6` | No | Template mode bullish probability for QQQ |
| `TLT_BIAS` | `0.45` | `0.45` | No | Template mode bullish probability for TLT |
| `GLD_BIAS` | `0.55` | `0.55` | No | Template mode bullish probability for GLD |
| `USO_BIAS` | `0.4` | `0.4` | No | Template mode bullish probability for USO |
| `BUY_THRESHOLD` | `0.3` | `0.15` | No | Ensemble score above which a BUY signal fires |
| `SELL_THRESHOLD` | `-0.3` | `-0.15` | No | Ensemble score below which a SELL signal fires |
| `POLL_INTERVAL_MS` | `60000` | `60000` | No | How often NewsPollerService polls (ms) |
| `POLL_ARTICLES_PER_TICKER` | `1` | `2` | No | Articles fetched per ticker per poll cycle |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `kafka:9093` | No | Kafka broker address |
| `REDIS_HOST` | `localhost` | `redis` | No | Redis hostname |
| `REDIS_PORT` | `6379` | `6379` | No | Redis port |
| `RABBITMQ_HOST` | `localhost` | `rabbitmq` | No | RabbitMQ hostname |
| `RABBITMQ_PORT` | `5672` | `5672` | No | RabbitMQ port |
| `DYNAMODB_ENDPOINT` | _(none)_ | `http://localstack:4566` | No | DynamoDB endpoint (LocalStack locally; AWS endpoint in production) |
| `DYNAMODB_TABLE` | `trade-signals` | `trade-signals` | No | DynamoDB table name |
| `AWS_ACCESS_KEY` | `test` | `test` | No | Dummy value for LocalStack; use real credentials for AWS |
| `AWS_SECRET_KEY` | `test` | `test` | No | Dummy value for LocalStack |

---

## API Endpoints

### Dashboard API (`/api/dashboard`)

| Method | Path | Description |
|---|---|---|
| GET | `/` | Thymeleaf dashboard HTML |
| GET | `/api/dashboard/scores` | Latest ensemble score per ticker — `Map<String, Double>` |
| GET | `/api/dashboard/signals?limit=20` | Recent trade signals (in-memory, since startup) — `List<TradeSignal>` |
| GET | `/api/dashboard/stats` | Per-ticker stats: ensembleSignal, ensembleScore, buyVotes, sellVotes, holdVotes, strategies, articleCount, lastSignal |
| GET | `/api/dashboard/ticker/{ticker}/recent` | Last 5 unique scored articles for a ticker — `List<SentimentResult>` |
| POST | `/api/dashboard/trigger-poll` | Manually trigger a news poll cycle |

### History API (`/api/history`)

| Method | Path | Description |
|---|---|---|
| GET | `/api/history/ticker/{ticker}/snapshots` | Up to 100 ensemble history snapshots from Redis (newest first) |
| GET | `/api/history/signals` | All signals from DynamoDB scan (limit 50, sorted newest first) |
| GET | `/api/history/signals/{ticker}` | Signals for a specific ticker from DynamoDB (limit 20, sorted newest first) |
| GET | `/api/history/eventtypes/{ticker}` | Event type distribution for a ticker — `Map<String, Integer>` |

### Events API (`/api/events`)

| Method | Path | Body | Description |
|---|---|---|---|
| GET | `/api/events/scenarios` | — | List of 4 pre-configured demo scenarios |
| POST | `/api/events/inject` | `{ticker, sentiment, articleCount}` | Inject synthetic articles into the pipeline (disabled when `NEWS_SOURCE=finnhub`) |
| POST | `/api/events/reset` | `{ticker}` ("ALL" or specific ticker) | Reset Redis strategy state for ticker(s) |

---

## Demo Scenarios

Four pre-configured macro scenarios are available via the dashboard or the API:

| Scenario | Target | Sentiment | Count | Effect |
|---|---|---|---|---|
| Fed Dovish Pivot | ALL | BULLISH | 8 | Boost equities, bonds; softer Fed tone |
| Fed Hawkish Surprise | ALL | BEARISH | 8 | Pressure risk assets and duration |
| Flight to Safety | GLD | BULLISH | 8 | Drive gold demand on market stress |
| Oil Supply Shock | USO | EUPHORIA | 8 | Spike crude on supply disruption |

---

## Redis Key Reference

| Key Pattern | Type | Description |
|---|---|---|
| `sentiment:{T}:rolling:sum` | String (double) | Cumulative sum of all scores for Rolling Average |
| `sentiment:{T}:rolling:count` | String (long) | Total article count for Rolling Average |
| `sentiment:{T}:ema:value` | String (double) | Current EMA value |
| `sentiment:{T}:window` | List | Last 10 scores for Windowed Average (trimmed to 10) |
| `sentiment:{T}:momentum:prev` | String (double) | Previous EMA value (for Momentum delta) |
| `sentiment:{T}:momentum:current` | String (double) | Current EMA value alias used by Momentum |
| `sentiment:{T}:last_signal` | String | Last fired signal type (BUY / SELL) |
| `recent:{T}` | List | Last 5 `SentimentResult` JSON objects (trimmed to 5 on write; 30 read + deduped on retrieval) |
| `history:{T}:snapshots` | List | Up to 100 ensemble snapshot JSON objects (newest first); **not** cleared by the reset endpoint |
| `dedupe:{hash}` | String | Deduplication marker (value: "1"), TTL: 24 hours |

---

## Switching to External Providers

| What to change | How |
|---|---|
| NewsAPI headlines | Set `NEWS_SOURCE=newsapi`, provide `NEWS_API_KEY` |
| Finnhub headlines | Set `NEWS_SOURCE=finnhub`, provide `FINNHUB_API_KEY`; event injection disabled |
| LLM sentiment scoring | Set `USE_LLM_SENTIMENT=true`, provide `GEMINI_API_KEY` |
| Real AWS DynamoDB | Set `DYNAMODB_ENDPOINT` to your AWS regional endpoint; provide real credentials |

---

## Local-First Design Philosophy

The stack runs 100% offline by default — no API keys, no external calls, no rate limits.

- Demos work without network access or paid API credits
- Strategies and ensemble logic can be tested deterministically with bias-controlled mock data
- The architecture is identical regardless of provider — swapping is one config change

---

## Known Issues and Constraints

- **Java 17 required** — `pom.xml` sets `<java.version>17</java.version>`.
- **No authentication** — all endpoints are open by design.
- **DynamoDB table must exist before app starts** — `init-dynamodb.sh` handles this for LocalStack. If the table is missing, `AggregatorService` will throw on the first signal.
- **Redis dedupe TTL is 24 hours** — to clear dedupe state: `docker exec -it redis redis-cli DEL dedupe:{hash}` or restart Redis.
- **History snapshots not cleared on reset** — `POST /api/events/reset` clears all strategy Redis keys but does not delete `history:{T}:snapshots`. To clear: `redis-cli DEL history:{T}:snapshots`.
- **Event injection disabled in Finnhub mode** — `POST /api/events/inject` returns HTTP 400 when `NEWS_SOURCE=finnhub`.
- **Momentum signal thresholds are fixed** — Momentum uses ±0.03 regardless of `BUY_THRESHOLD` / `SELL_THRESHOLD`.
- **`mock:{ticker}:pointer`** — reset endpoint clears this key, but no code writes it; it is an inert legacy artifact.
- **`MOCK_NEWS` in application.properties** — this property is defined but not read by any Java class; it is inert.
