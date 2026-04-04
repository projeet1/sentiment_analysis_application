package com.acp.finance.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SentimentResult {

    // ── existing fields ──────────────────────────────────────────────────────
    private String ticker;
    private String headline;
    private double score;
    private String reasoning;
    private String publishedAt;

    // ── new fields (safe defaults) ────────────────────────────────────────────
    private String  eventType    = "OTHER";
    private String  horizon      = "UNKNOWN";
    private double  confidence   = 0.5;
    private boolean isBreaking   = false;
    private boolean deduped      = false;
    private String  articleId    = "";
    private String  contentHash  = "";
    private String  sourceMode   = "LOCAL";
    private String  analysisMode = "LOCAL_HEURISTIC";

    // ── existing getters / setters ────────────────────────────────────────────
    public String getTicker()                        { return ticker; }
    public void   setTicker(String ticker)           { this.ticker = ticker; }

    public String getHeadline()                      { return headline; }
    public void   setHeadline(String headline)       { this.headline = headline; }

    public double getScore()                         { return score; }
    public void   setScore(double score)             { this.score = score; }

    public String getReasoning()                     { return reasoning; }
    public void   setReasoning(String reasoning)     { this.reasoning = reasoning; }

    public String getPublishedAt()                   { return publishedAt; }
    public void   setPublishedAt(String publishedAt) { this.publishedAt = publishedAt; }

    // ── new getters / setters ─────────────────────────────────────────────────
    public String  getEventType()                        { return eventType; }
    public void    setEventType(String eventType)        { this.eventType = eventType; }

    public String  getHorizon()                          { return horizon; }
    public void    setHorizon(String horizon)            { this.horizon = horizon; }

    public double  getConfidence()                       { return confidence; }
    public void    setConfidence(double confidence)      { this.confidence = confidence; }

    public boolean isBreaking()                          { return isBreaking; }
    public void    setBreaking(boolean isBreaking)       { this.isBreaking = isBreaking; }

    public boolean isDeduped()                           { return deduped; }
    public void    setDeduped(boolean deduped)           { this.deduped = deduped; }

    public String  getArticleId()                        { return articleId; }
    public void    setArticleId(String articleId)        { this.articleId = articleId; }

    public String  getContentHash()                      { return contentHash; }
    public void    setContentHash(String contentHash)    { this.contentHash = contentHash; }

    public String  getSourceMode()                       { return sourceMode; }
    public void    setSourceMode(String sourceMode)      { this.sourceMode = sourceMode; }

    public String  getAnalysisMode()                     { return analysisMode; }
    public void    setAnalysisMode(String analysisMode)  { this.analysisMode = analysisMode; }
}
