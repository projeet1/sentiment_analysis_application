package com.acp.finance.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    public Queue buySignalsQueue() {
        return new Queue("buy-signals", true);
    }

    @Bean
    public Queue sellSignalsQueue() {
        return new Queue("sell-signals", true);
    }
}
