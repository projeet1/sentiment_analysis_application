package com.acp.finance.service;

import com.acp.finance.config.AppProperties;
import com.acp.finance.model.NewsArticle;
import com.acp.finance.news.NewsSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class NewsPollerService {

    private static final Logger log = LoggerFactory.getLogger(NewsPollerService.class);

    private final AppProperties props;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final NewsSource newsSource;
    private final ObjectMapper mapper = new ObjectMapper();

    public NewsPollerService(AppProperties props,
                             KafkaTemplate<String, String> kafkaTemplate,
                             NewsSource newsSource) {
        this.props = props;
        this.kafkaTemplate = kafkaTemplate;
        this.newsSource = newsSource;
    }

    // Also callable manually from the controller for demos
    @Scheduled(fixedDelayString = "${app.poll.interval-ms:60000}")
    public void pollNews() {
        Arrays.stream(props.tickers.split(","))
              .map(String::trim)
              .filter(t -> !t.isBlank())
              .forEach(this::pollTicker);
    }

    private void pollTicker(String ticker) {
        List<NewsArticle> articles = newsSource.getLatestHeadlines(ticker, props.pollArticlesPerTicker);
        for (NewsArticle article : articles) {
            try {
                kafkaTemplate.send(props.kafkaTopicRaw, ticker, mapper.writeValueAsString(article));
                log.info("[NewsPoller] {} -> {}", ticker, article.getTitle());
            } catch (Exception e) {
                log.error("[NewsPoller] Error publishing {}: {}", ticker, e.getMessage());
            }
        }
    }
}
