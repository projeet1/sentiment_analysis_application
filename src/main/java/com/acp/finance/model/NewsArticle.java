package com.acp.finance.model;

import lombok.Data;

@Data
public class NewsArticle {
    private String ticker;
    private String title;
    private String description;
    private String publishedAt;
    private String source;
}
