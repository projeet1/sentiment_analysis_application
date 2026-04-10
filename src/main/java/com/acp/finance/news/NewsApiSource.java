package com.acp.finance.news;

import com.acp.finance.config.AppProperties;
import com.acp.finance.model.NewsArticle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(
    name = "NEWS_SOURCE",
    havingValue = "newsapi")
public class NewsApiSource implements NewsSource {

    private static final Logger log = LoggerFactory.getLogger(NewsApiSource.class);

    private static final String NEWS_BASE_URL =
            "https://newsapi.org/v2/everything";

    /**
     * Richer query strings per instrument to surface relevant macro headlines.
     * The ticker field on NewsArticle remains the instrument symbol.
     */
    private static final Map<String, String> INSTRUMENT_QUERIES = Map.of(
        "SPY", "(S&P 500 OR US stock market OR US equities OR Wall Street) AND (stocks OR market OR equities)",
        "QQQ", "(Nasdaq 100 OR big tech OR growth stocks OR tech stocks) AND (market OR stocks OR equities)",
        "TLT", "(US Treasury yields OR long-term Treasury bonds OR 10-year yield OR bond market OR Federal Reserve) AND (yields OR bonds OR rates)",
        "GLD", "(gold prices OR gold market OR safe haven demand OR inflation hedge) AND (gold OR bullion OR inflation)",
        "USO", "(oil prices OR crude oil OR WTI OR OPEC OR energy market) AND (oil OR crude OR energy)"
    );

    private final AppProperties props;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public NewsApiSource(AppProperties props) {
        this.props = props;
    }

    @Override
    public List<NewsArticle> getLatestHeadlines(String ticker, int count) {
        List<NewsArticle> result = new ArrayList<>();
        try {
            String query = INSTRUMENT_QUERIES.getOrDefault(ticker, ticker);

            String url = UriComponentsBuilder.fromHttpUrl(NEWS_BASE_URL)
                    .queryParam("q", query)
                    .queryParam("apiKey", props.newsApiKey)
                    .queryParam("language", "en")
                    .queryParam("sortBy", "publishedAt")
                    .queryParam("pageSize", count)
                    .build(false)   // do not encode — query already contains parens/quotes
                    .toUriString();

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = mapper.readTree(response);
            JsonNode articles = root.path("articles");

            if (!articles.isArray()) return result;

            for (JsonNode node : articles) {
                String title = node.path("title").asText("").trim();
                if (title.isEmpty() || title.equals("[Removed]")) continue;

                NewsArticle article = new NewsArticle();
                article.setTicker(ticker);
                article.setTitle(title);
                article.setDescription(node.path("description").asText(""));
                article.setPublishedAt(node.path("publishedAt").asText(""));
                article.setSource(node.path("source").path("name").asText(""));
                result.add(article);
            }
        } catch (Exception e) {
            log.error("[NewsApiSource] Error for instrument {}: {}", ticker, e.getMessage());
        }
        return result;
    }
}
