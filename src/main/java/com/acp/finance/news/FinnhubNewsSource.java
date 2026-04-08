package com.acp.finance.news;

import com.acp.finance.config.AppProperties;
import com.acp.finance.model.NewsArticle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "NEWS_SOURCE", havingValue = "finnhub")
public class FinnhubNewsSource implements NewsSource {

    private static final Logger log =
        LoggerFactory.getLogger(FinnhubNewsSource.class);

    private static final String BASE_URL =
        "https://finnhub.io/api/v1/company-news" +
        "?symbol=%s&from=%s&to=%s&token=%s";

    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public FinnhubNewsSource(AppProperties props) {
        this.props = props;
    }

    @Override
    public List<NewsArticle> getLatestHeadlines(
            String ticker, int count) {

        List<NewsArticle> articles = new ArrayList<>();

        if (props.finnhubApiKey == null ||
                props.finnhubApiKey.isBlank()) {
            log.warn("[Finnhub] FINNHUB_API_KEY not set " +
                "— skipping {}", ticker);
            return articles;
        }

        try {
            String from = LocalDate.now()
                .minusDays(2).toString();
            String to = LocalDate.now().toString();

            String url = String.format(BASE_URL,
                ticker, from, to, props.finnhubApiKey);

            String response = restTemplate
                .getForObject(url, String.class);

            if (response == null || response.isBlank()) {
                log.warn("[Finnhub] Empty response " +
                    "for {}", ticker);
                return articles;
            }

            JsonNode root = mapper.readTree(response);

            if (!root.isArray()) {
                log.warn("[Finnhub] Unexpected response " +
                    "format for {}: {}", ticker,
                    response.substring(0,
                        Math.min(100, response.length())));
                return articles;
            }

            int added = 0;
            for (JsonNode node : root) {
                if (added >= count) break;

                String headline = node
                    .path("headline").asText("").trim();
                if (headline.isEmpty()) continue;

                long epochSecs = node
                    .path("datetime").asLong(0);
                String publishedAt = epochSecs > 0
                    ? Instant.ofEpochSecond(epochSecs)
                        .toString()
                    : Instant.now().toString();

                String source = node
                    .path("source").asText("Finnhub");
                String summary = node
                    .path("summary").asText("");

                NewsArticle article = new NewsArticle();
                article.setTicker(ticker);
                article.setTitle(headline);
                article.setDescription(summary);
                article.setPublishedAt(publishedAt);
                article.setSource(source);
                article.setSourceMode("EXTERNAL");
                article.setArticleId(
                    UUID.randomUUID().toString());
                article.setIngestedAt(
                    Instant.now().toString());
                article.setContentHash(
                    NewsArticle.computeHash(
                        NewsArticle.normalise(headline)));

                articles.add(article);
                added++;
            }

            log.info("[Finnhub] Fetched {} articles " +
                "for {}", articles.size(), ticker);

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("[Finnhub] Rate limited for " +
                    "{} — skipping this cycle", ticker);
            } else {
                log.error("[Finnhub] HTTP error for " +
                    "{}: {} {}", ticker,
                    e.getStatusCode(), e.getMessage());
            }
        } catch (Exception e) {
            log.error("[Finnhub] Error fetching news " +
                "for {}: {}", ticker, e.getMessage());
        }

        return articles;
    }
}
