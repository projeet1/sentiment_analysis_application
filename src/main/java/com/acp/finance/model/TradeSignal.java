package com.acp.finance.model;

public class TradeSignal {
    private String id;
    private String ticker;
    private String signalType;
    private double sentimentScore;
    private String triggeringHeadline;
    private String reasoning;
    private String timestamp;

    public String getId()                                   { return id; }
    public void   setId(String id)                         { this.id = id; }

    public String getTicker()                              { return ticker; }
    public void   setTicker(String ticker)                 { this.ticker = ticker; }

    public String getSignalType()                          { return signalType; }
    public void   setSignalType(String signalType)         { this.signalType = signalType; }

    public double getSentimentScore()                      { return sentimentScore; }
    public void   setSentimentScore(double sentimentScore) { this.sentimentScore = sentimentScore; }

    public String getTriggeringHeadline()                          { return triggeringHeadline; }
    public void   setTriggeringHeadline(String triggeringHeadline) { this.triggeringHeadline = triggeringHeadline; }

    public String getReasoning()                           { return reasoning; }
    public void   setReasoning(String reasoning)           { this.reasoning = reasoning; }

    public String getTimestamp()                           { return timestamp; }
    public void   setTimestamp(String timestamp)           { this.timestamp = timestamp; }
}
