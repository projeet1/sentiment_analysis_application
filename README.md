# Real-Time Market Event Intelligence and Strategy Comparison Platform

A real-time financial news pipeline that ingests headlines, classifies market events, scores
sentiment across four independent strategies, and compares them live on a dark-mode dashboard.
Trade signals (BUY/SELL) are fired to RabbitMQ and persisted to DynamoDB the moment the
ensemble of strategies reaches a decision.

**Who benefits:** quant analysts comparing strategy behavior under live market events, fund
managers tracking multi-ticker sentiment at a glance, and retail investors who want structured
event classification without a Bloomberg terminal.

---

## Architecture

```
NewsAPI / Mock
     │
     ▼
NewsPollerService ──publishes──► Kafka: news-raw
                                     │
                                     ▼
                             SentimentScoringService
                             ├─ DedupeChecker (Redis: dedupe:{hash})
                             ├─ LocalHeuristicAnalyser (always runs)
                             └─ Anthropic Claude API (if MOCK_SENTIMENT=false)
                                     │
                                     ▼
                             Kafka: news-sentiment
                                     │
                                     ▼
                               AggregatorService
                               ├─ 4 strategies → Redis keys
                               ├─ EnsembleService (vote → signal)
                               ├─ History snapshots → Redis
                               ├─ Recent articles  → Redis
                               ├─ BUY/SELL ────────► RabbitMQ
                               └─ Trade signals ───► DynamoDB
                                     │
                                     ▼
                          DashboardController (port 8080)
                          REST API + Thymeleaf dashboard
```

---

## ACP Technology Usage

| Technology | Role |
|---|---|
| **Kafka** | Event backbone. Two topics: `news-raw` (raw articles) and `news-sentiment` (scored results). Decouples polling from scoring and scoring from aggregation. |
| **Redis** | Strategy state (6 keys per ticker), dedupe hashes (24 h TTL), sentiment history snapshots (up to 100 per ticker), recent articles (last 5 per ticker), event-type cache. |
| **RabbitMQ** | Signal delivery. `buy-signals` and `sell-signals` durable queues receive trade signals for downstream consumption. |
| **DynamoDB** | Persistent signal audit log. `trade-signals` table (PK: `id` UUID) stores every BUY/SELL event with full enrichment fields. Backed by LocalStack locally. |
| **Spring Boot** | REST service on port 8080. Hosts Thymeleaf dashboard, all `/api/*` endpoints, and all background services. |

---

## Provider Modes

News source is selected by the `NEWS_SOURCE` environment variable. Sentiment scoring is controlled separately by `MOCK_SENTIMENT`.

| `NEWS_SOURCE` | News Source | Notes |
|---|---|---|
| `template` (default) | `TemplateNewsSource` — synthetic headlines generated from per-ticker bias weights (`AAPL_BIAS`, etc.) | No API key required; fully offline |
| `newsapi` | `NewsApiSource` — live headlines from newsapi.org | Requires `NEWS_API_KEY` |
| `finnhub` | `FinnhubNewsSource` — live company news from finnhub.io (past 2 days) | Requires `FINNHUB_API_KEY`; event injection via `/api/events/inject` is disabled in this mode |

| `MOCK_SENTIMENT` | Sentiment Scoring |
|---|---|
| `true` | `LocalHeuristicAnalyser` — keyword-based scoring, always runs |
| `false` | Anthropic Claude (`claude-haiku-4-5-20251001`) — structured JSON response; `LocalHeuristicAnalyser` still provides event classification fallback; requires `ANTHROPIC_API_KEY` |

---

## Sentiment Strategies

All four strategies share the same `SentimentStrategy` interface. Each independently produces
a score (−1 to +1) and a BUY/SELL/HOLD vote. The `EnsembleService` combines them.

| # | Strategy | Display Name | Algorithm | Redis Keys |
|---|---|---|---|---|
| 1 | `rolling` | Rolling Average | Cumulative sum ÷ count — lifetime average | `sentiment:{T}:rolling:sum`, `sentiment:{T}:rolling:count` |
| 2 | `ema` | EMA (α=0.3) | Exponential moving average, α=0.3 — recent scores weighted more | `sentiment:{T}:ema:value` |
| 3 | `windowed` | Windowed (last 10) | Average of last 10 scores — short-term view | `sentiment:{T}:window` (Redis list) |
| 4 | `momentum` | Momentum | Delta between current EMA and previous EMA — rate of change | `sentiment:{T}:momentum:prev`, `sentiment:{T}:momentum:current` |

### Ensemble Voting

```
Weighted score = (rolling × 0.15) + (ema × 0.35) + (windowed × 0.30) + (momentum × 0.20)

Buy votes  = count of strategies where score ≥ BUY_THRESHOLD  (app default 0.3; docker-compose 0.15)
Sell votes = count of strategies where score ≤ SELL_THRESHOLD (app default −0.3; docker-compose −0.15)
Momentum votes use ±0.05 threshold

3+ buy votes  → STRONG BUY    2 buy votes  → BUY
3+ sell votes → STRONG SELL   2 sell votes → SELL
otherwise     → HOLD
```

---

## Structured Event Analysis

Every article is run through `LocalHeuristicAnalyser` (or the Anthropic API) and enriched with:

### Event Types (10)

| Event Type | Description |
|---|---|
| EARNINGS | Quarterly results, EPS, revenue beats/misses |
| LEGAL | Lawsuits, regulatory penalties, investigations |
| MERGER | M&A activity, acquisitions, takeovers |
| MANAGEMENT | Executive changes, board decisions |
| REGULATION | Policy changes, government rules |
| PRODUCT | Product launches, recalls, major updates |
| GUIDANCE | Forward guidance, analyst upgrades/downgrades |
| MACRO | Macro-economic events, interest rates, inflation |
| PARTNERSHIP | Joint ventures, licensing deals, alliances |
| OTHER | Catch-all for unclassified headlines |

### Additional Enrichment Fields

| Field | Values | Description |
|---|---|---|
| `horizon` | IMMEDIATE / INTRADAY / SWING / UNKNOWN | Expected time-to-impact of the event |
| `confidence` | 0.3 – 0.95 | Confidence in the classification (keyword hit density) |
| `isBreaking` | true / false | Detected as a breaking news headline |
| `deduped` | true / false | Duplicate of a previously seen article (content hash match) |

**Dedupe layer:** Headlines are normalized (lowercase, stripped punctuation) and hashed (SHA-256,
first 16 chars). The hash is stored in Redis as `dedupe:{hash}` with a 24-hour TTL.
Duplicate articles are flagged and their scores excluded from strategy updates.

---

## How to Run Locally

### Prerequisites

- Docker + Docker Compose
- (Optional) [NewsAPI](https://newsapi.org) key — only needed if `NEWS_SOURCE=newsapi`
- (Optional) [Finnhub](https://finnhub.io) key — only needed if `NEWS_SOURCE=finnhub`
- (Optional) Anthropic API key — only needed if `MOCK_SENTIMENT=false`

### Steps

```bash
# 1. Clone the repo
git clone <repo-url>
cd cw3

# 2. Create .env file (defaults run fully on mock data — no API keys needed)
cat > .env <<EOF
NEWS_API_KEY=your_key_or_leave_blank
ANTHROPIC_API_KEY=your_key_or_leave_blank
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
# Inject 5 CRASH articles for TSLA
curl -X POST http://localhost:8080/api/events/inject \
  -H "Content-Type: application/json" \
  -d '{"ticker":"TSLA","sentiment":"CRASH","articleCount":5}'
```

Sentiment values: `CRASH`, `BEARISH`, `NEUTRAL`, `BULLISH`, `EUPHORIA`

---

## Configuration Reference

All variables can be set in `.env` or as environment variables in `docker-compose.yml`. The defaults below are the application code defaults (from `AppProperties.java` and `@Value` annotations). Where the provided `docker-compose.yml` explicitly overrides a default, both values are shown.

| Variable | App Default | docker-compose | Required | Description |
|---|---|---|---|---|
| `NEWS_SOURCE` | `template` | `finnhub` | No | Selects news source: `template`, `newsapi`, or `finnhub` |
| `NEWS_API_KEY` | _(empty)_ | _(empty)_ | Only if `NEWS_SOURCE=newsapi` | newsapi.org API key |
| `FINNHUB_API_KEY` | _(empty)_ | _(empty)_ | Only if `NEWS_SOURCE=finnhub` | finnhub.io API key |
| `ANTHROPIC_API_KEY` | _(empty)_ | _(empty)_ | Only if `MOCK_SENTIMENT=false` | Anthropic Claude API key |
| `MOCK_SENTIMENT` | `false` | `true` | No | `true` = LocalHeuristicAnalyser only; `false` = call Anthropic Claude API |
| `AAPL_BIAS` | `0.8` | `0.8` | No | Positive sentiment bias for AAPL in template mode (0=very bearish, 1=very bullish) |
| `TSLA_BIAS` | `0.2` | `0.2` | No | Positive sentiment bias for TSLA in template mode |
| `MSFT_BIAS` | `0.65` | `0.65` | No | Positive sentiment bias for MSFT in template mode |
| `GOOGL_BIAS` | `0.5` | `0.5` | No | Positive sentiment bias for GOOGL in template mode |
| `BUY_THRESHOLD` | `0.3` | `0.15` | No | Ensemble score above which a BUY signal fires |
| `SELL_THRESHOLD` | `-0.3` | `-0.15` | No | Ensemble score below which a SELL signal fires |
| `POLL_INTERVAL_MS` | `60000` | `30000` | No | How often NewsPollerService polls (ms) |
| `TICKERS` | `AAPL,TSLA,MSFT,GOOGL` | `AAPL,TSLA,MSFT,GOOGL` | No | Comma-separated tickers to track |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `kafka:9093` | No | Kafka broker address |
| `REDIS_HOST` | `localhost` | `redis` | No | Redis hostname |
| `RABBITMQ_HOST` | `localhost` | `rabbitmq` | No | RabbitMQ hostname |
| `DYNAMODB_ENDPOINT` | _(none)_ | `http://localstack:4566` | No | DynamoDB endpoint (LocalStack for local; AWS endpoint for production) |

---

## API Endpoints

### Dashboard API (`/api/dashboard`)

| Method | Path | Description |
|---|---|---|
| GET | `/api/dashboard/scores` | Latest ensemble score per ticker — `Map<String, Double>` |
| GET | `/api/dashboard/signals?limit=20` | Recent trade signals — `List<TradeSignal>` |
| GET | `/api/dashboard/stats` | Per-ticker stats: ensembleSignal, ensembleScore, votes, strategies, articleCount, lastSignal |
| GET | `/api/dashboard/ticker/{ticker}/recent` | Last 5 scored articles for a ticker — `List<SentimentResult>` |
| POST | `/api/dashboard/trigger-poll` | Manually trigger news poll |

### History API (`/api/history`)

| Method | Path | Description |
|---|---|---|
| GET | `/api/history/ticker/{ticker}/snapshots` | Up to 100 ensemble history snapshots (newest first) |
| GET | `/api/history/signals` | All signals from DynamoDB (limit 50, sorted newest first) |
| GET | `/api/history/signals/{ticker}` | Signals for a specific ticker (limit 20) |
| GET | `/api/history/eventtypes/{ticker}` | Event type distribution for a ticker — `Map<String, Integer>` |

### Events API (`/api/events`)

| Method | Path | Description |
|---|---|---|
| GET | `/api/events/scenarios` | List of 6 pre-configured test scenarios |
| POST | `/api/events/inject` | Inject synthetic articles — body: `{ticker, sentiment, articleCount}` |
| POST | `/api/events/reset` | Reset state for a ticker or all — body: `{ticker: "ALL"}` |

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
| `sentiment:{T}:last_signal` | String | Last fired signal type (BUY/SELL/HOLD) |
| `recent:{T}` | List | Last 5 `SentimentResult` JSON objects |
| `history:{T}:snapshots` | List | Up to 100 ensemble snapshot JSON objects (newest first); **not** cleared by the reset endpoint |
| `dedupe:{hash}` | String | Deduplication marker (value: "1"), TTL: 24 hours |
| `mock:{T}:pointer` | String | Internal pointer used by template mode; cleared on reset |

---

## Demo Flow (CW4 Video)

1. **Start stack** — `docker-compose up --build`. Wait for "Started FinanceSentimentApplication".
2. **Open dashboard** — `http://localhost:8080`. Show all four tickers live with HOLD signals.
3. **Explain strategies** — Click any ticker to expand the dropdown. Point out the four strategy
   rows, the weighted ensemble score, vote bar (BUY/SELL/HOLD breakdown), and the history chart.
4. **Inject EUPHORIA for AAPL** — click the "Apple Euphoria" scenario button (or use `/api/events/inject`).
   Watch AAPL strategies swing toward BUY, ensemble flip to STRONG BUY, and a signal appear in the
   Signals panel.
5. **Inject CRASH for TSLA** — click "Tesla Crash". Watch TSLA strategies swing toward SELL,
   ensemble flip to STRONG SELL. Point out that AAPL and TSLA are now diverging.
6. **Show RabbitMQ** — open `http://localhost:15672`, navigate to Queues, show messages in
   `buy-signals` and `sell-signals`.
7. **Show DynamoDB** — `curl http://localhost:8080/api/history/signals | jq .` to show persistent
   trade signal log with event type, horizon, confidence, isBreaking fields.
8. **Inject Market Crash (ALL)** — all tickers flip simultaneously. Show ensemble voting collapse.
9. **Reset** — hit Reset All on the dashboard. Show all signals clear and strategies return to 0.
10. **Switch to external LLM** — explain one-line config change (`MOCK_SENTIMENT=false`) to route
    sentiment through Anthropic Claude. Dashboard shows "External LLM Analysis" indicator.

---

## Local-First Design Philosophy

The stack runs 100% offline by default — no API keys, no external calls, no rate limits. This is
**intentional**, not a limitation:

- Demos work without network access or paid API credits
- Strategies and ensemble logic can be tested deterministically with bias-controlled mock data
- The architecture is identical to production — swapping the provider is one config change

### Switching to External Providers

| What to change | How |
|---|---|
| NewsAPI headlines | Set `NEWS_SOURCE=newsapi`, provide `NEWS_API_KEY` |
| Finnhub headlines | Set `NEWS_SOURCE=finnhub`, provide `FINNHUB_API_KEY`; note that event injection (`/api/events/inject`) is disabled in this mode |
| LLM sentiment scoring | Set `MOCK_SENTIMENT=false`, provide `ANTHROPIC_API_KEY` |
| Real AWS DynamoDB | Change `DYNAMODB_ENDPOINT` to your AWS region endpoint and set real `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` |

When `MOCK_SENTIMENT=false`, the `analysisMode` field in scored articles switches from
`LOCAL_HEURISTIC` to `ANTHROPIC`. The `LocalHeuristicAnalyser` still runs in both modes to
provide event classification as a fallback.
