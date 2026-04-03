package com.acp.finance.model;

import lombok.Data;

@Data
public class TradeSignal {
    private String id;
    private String ticker;
    private String signalType;
    private double sentimentScore;
    private String triggeringHeadline;
    private String reasoning;
    private String timestamp;
}
