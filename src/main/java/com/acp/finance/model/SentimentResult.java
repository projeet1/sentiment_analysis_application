package com.acp.finance.model;

import lombok.Data;

@Data
public class SentimentResult {
    private String ticker;
    private String headline;
    private double score;
    private String reasoning;
    private String publishedAt;
}
