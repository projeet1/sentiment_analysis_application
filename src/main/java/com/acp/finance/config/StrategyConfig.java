package com.acp.finance.config;

import com.acp.finance.strategy.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class StrategyConfig {

    @Bean
    public RollingAverageStrategy rollingAverageStrategy() {
        return new RollingAverageStrategy();
    }

    @Bean
    public EmaStrategy emaStrategy() {
        return new EmaStrategy();
    }

    @Bean
    public WindowedAverageStrategy windowedAverageStrategy() {
        return new WindowedAverageStrategy();
    }

    @Bean
    public MomentumStrategy momentumStrategy() {
        return new MomentumStrategy();
    }

    @Bean
    public EnsembleService ensembleService(List<SentimentStrategy> strategies) {
        return new EnsembleService(strategies);
    }
}
