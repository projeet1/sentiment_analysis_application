package com.acp.finance.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NewsArticle {

    // ── existing fields ──────────────────────────────────────────────────────
    private String ticker;
    private String title;
    private String description;
    private String publishedAt;
    private String source;

    // ── new fields ───────────────────────────────────────────────────────────
    private String articleId;
    private String ingestedAt;
    private String contentHash;
    private String sourceMode;

    // ── existing getters / setters ────────────────────────────────────────────
    public String getTicker()                        { return ticker; }
    public void   setTicker(String ticker)           { this.ticker = ticker; }

    public String getTitle()                         { return title; }
    public void   setTitle(String title)             { this.title = title; }

    public String getDescription()                   { return description; }
    public void   setDescription(String description) { this.description = description; }

    public String getPublishedAt()                   { return publishedAt; }
    public void   setPublishedAt(String publishedAt) { this.publishedAt = publishedAt; }

    public String getSource()                        { return source; }
    public void   setSource(String source)           { this.source = source; }

    // ── new getters / setters ─────────────────────────────────────────────────
    public String getArticleId()                       { return articleId; }
    public void   setArticleId(String articleId)       { this.articleId = articleId; }

    public String getIngestedAt()                      { return ingestedAt; }
    public void   setIngestedAt(String ingestedAt)     { this.ingestedAt = ingestedAt; }

    public String getContentHash()                     { return contentHash; }
    public void   setContentHash(String contentHash)   { this.contentHash = contentHash; }

    public String getSourceMode()                      { return sourceMode; }
    public void   setSourceMode(String sourceMode)     { this.sourceMode = sourceMode; }

    // ── static helpers ────────────────────────────────────────────────────────
    public static String normalise(String headline) {
        if (headline == null) return "";
        return headline.toLowerCase()
            .replaceAll("[^a-z0-9 ]", "")
            .trim();
    }

    public static String computeHash(String normalised) {
        try {
            java.security.MessageDigest md =
                java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(
                normalised.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes)
                sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 16);
        } catch (Exception e) {
            return normalised.substring(0, Math.min(16, normalised.length()));
        }
    }
}
