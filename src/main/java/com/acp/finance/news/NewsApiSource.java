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

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(
    name = "NEWS_SOURCE",
    havingValue = "newsapi")
public class NewsApiSource implements NewsSource {

    private static final Logger log = LoggerFactory.getLogger(NewsApiSource.class);

    private static final String NEWS_URL =
            "https://newsapi.org/v2/everything?q={ticker}&apiKey={key}&language=en&sortBy=publishedAt&pageSize={count}";

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
            String url = NEWS_URL
                    .replace("{ticker}", ticker)
                    .replace("{key}", props.newsApiKey)
                    .replace("{count}", String.valueOf(count));

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
            log.error("[NewsApiSource] Error for ticker {}: {}", ticker, e.getMessage());
        }
        return result;
    }
}
