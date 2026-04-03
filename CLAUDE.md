# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build JAR
mvn clean package

# Run locally (requires external services)
mvn spring-boot:run

# Run full stack via Docker (preferred)
docker-compose up --build

# Build Docker image only
mvn spring-boot:build-image
```

No test or lint plugins are configured in `pom.xml`.

## Architecture

This is a **Java 17 / Spring Boot 3.2.0** real-time financial news sentiment pipeline. The data flows through:

```
NewsAPI → Kafka(news-raw) → LLM Scorer → Kafka(news-sentiment)
                                        → Redis rolling averages
                                        → threshold check → RabbitMQ(buy/sell-signals)
                                                          → DynamoDB(trade-signals)
```

### Services

| Class | Role |
|---|---|
| `NewsPollerService` | Polls NewsAPI every 60s for AAPL/TSLA/MSFT/GOOGL; publishes `NewsArticle` JSON to Kafka `news-raw` |
| `SentimentScoringService` | Kafka consumer (`news-raw`); calls Anthropic Claude API for score (-1..1) + reasoning; publishes `SentimentResult` to `news-sentiment` |
| `AggregatorService` | Kafka consumer (`news-sentiment`); updates per-ticker rolling avg in Redis; fires `TradeSignal` to RabbitMQ + DynamoDB when score ≥ 0.3 (BUY) or ≤ -0.3 (SELL); caches up to 100 signals in-memory for the dashboard |
| `DashboardController` | Serves `dashboard.html` (Thymeleaf) + REST API at `/api/dashboard/*` |

### REST Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/` | Dashboard UI |
| GET | `/api/dashboard/scores` | Latest sentiment per ticker |
| GET | `/api/dashboard/signals` | Recent trade signals (`?limit=20`) |
| GET | `/api/dashboard/stats` | Per-ticker article count + last signal |
| POST | `/api/dashboard/trigger-poll` | Manually trigger NewsPoller |

### Infrastructure (Docker Compose)

- **Kafka** (ports 9092/29092) + Zookeeper (2181) — topics: `news-raw`, `news-sentiment`
- **RabbitMQ** (5672, management UI 15672) — queues: `buy-signals`, `sell-signals`
- **Redis** (6379) — keys: `sentiment:{TICKER}:sum/count/avg/last_signal`
- **LocalStack** (4566) — DynamoDB table `trade-signals` (PK: `id` UUID)
- **Spring app** (8080)

### Required Environment Variables

```
NEWS_API_KEY        # NewsAPI.org key
ANTHROPIC_API_KEY   # Anthropic Claude API key
```

### Key Missing Files

The repository references files that are not present but are required to run:
- `Dockerfile` (needed by `docker-compose.yml` `build: .`)
- `init-dynamodb.sh` (DynamoDB table initialization, mounted into LocalStack)
- Spring Boot `@SpringBootApplication` main class
- Model classes: `NewsArticle`, `SentimentResult`, `TradeSignal`
- `AppProperties` configuration class
- `application.properties` / `application.yml`
