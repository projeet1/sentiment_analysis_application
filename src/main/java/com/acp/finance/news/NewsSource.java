package com.acp.finance.news;

import com.acp.finance.model.NewsArticle;

import java.util.List;

public interface NewsSource {
    List<NewsArticle> getLatestHeadlines(String ticker, int count);
}
