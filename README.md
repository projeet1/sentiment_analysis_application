# Finance Sentiment Pipeline — ACP CW3

A real-time financial news sentiment pipeline that streams headlines through
Kafka, scores them with an LLM (Anthropic Claude), aggregates rolling sentiment
per ticker in Redis, fires BUY/SELL signals to RabbitMQ, and persists them to DynamoDB.

## Architecture

```
NewsAPI → Kafka(news-raw) → LLM Scorer → Kafka(news-sentiment)
       → Redis rolling avg → threshold → RabbitMQ(buy/sell-signals)
                                       → DynamoDB(trade-signals)
```

A live dashboard at `http://localhost:8080` polls `/api/dashboard/*` every 5 seconds.

## Prerequisites

- Docker + Docker Compose
- A free [NewsAPI](https://newsapi.org) key (100 req/day free tier)
- An [Anthropic API](https://console.anthropic.com) key

## Running

```bash
# 1. Set your API keys
export NEWS_API_KEY=your_newsapi_key_here
export ANTHROPIC_API_KEY=your_anthropic_key_here

# 2. Start everything
docker-compose up --build

# 3. Open dashboard
open http://localhost:8080

# 4. Trigger a manual news poll (useful for demos — no need to wait 60s)
curl -X POST http://localhost:8080/api/dashboard/trigger-poll
```

## REST Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET  | `/` | Live dashboard UI |
| GET  | `/api/dashboard/scores` | Current rolling avg sentiment per ticker |
| GET  | `/api/dashboard/signals` | Recent BUY/SELL signals |
| GET  | `/api/dashboard/stats` | Per-ticker article count + last signal |
| POST | `/api/dashboard/trigger-poll` | Manually trigger a news poll |

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | RabbitMQ port |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `DYNAMODB_ENDPOINT` | _(real AWS)_ | Override for localstack |
| `DYNAMODB_TABLE` | `trade-signals` | DynamoDB table name |
| `NEWS_API_KEY` | _(required)_ | NewsAPI.org key |
| `ANTHROPIC_API_KEY` | _(required)_ | Anthropic API key |
| `TICKERS` | `AAPL,TSLA,MSFT,GOOGL` | Comma-separated tickers to track |
| `BUY_THRESHOLD` | `0.3` | Rolling avg above this → BUY signal |
| `SELL_THRESHOLD` | `-0.3` | Rolling avg below this → SELL signal |
| `POLL_INTERVAL_MS` | `60000` | News poll interval in milliseconds |

## Kafka Topics

- `news-raw` — raw headlines published by the poller
- `news-sentiment` — scored headlines (score + reasoning)

## RabbitMQ Queues

- `buy-signals` — JSON TradeSignal objects for bullish crossings
- `sell-signals` — JSON TradeSignal objects for bearish crossings

## Redis Keys (per ticker)

- `sentiment:{TICKER}:sum` — running sum of all scores
- `sentiment:{TICKER}:count` — total articles processed
- `sentiment:{TICKER}:avg` — current rolling average
- `sentiment:{TICKER}:last_signal` — cooldown: last signal type fired

## DynamoDB Schema (table: trade-signals)

| Attribute | Type | Description |
|-----------|------|-------------|
| `id` | String (PK) | UUID |
| `ticker` | String | e.g. AAPL |
| `signalType` | String | BUY or SELL |
| `sentimentScore` | String | Rolling avg at signal time |
| `headline` | String | Triggering article headline |
| `reasoning` | String | LLM reasoning (5 words) |
| `timestamp` | String | ISO-8601 |

## Useful Management UIs

- RabbitMQ Management: http://localhost:15672 (guest/guest)
- Dashboard: http://localhost:8080
