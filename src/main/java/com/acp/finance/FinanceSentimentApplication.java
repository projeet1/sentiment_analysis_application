package com.acp.finance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FinanceSentimentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinanceSentimentApplication.class, args);
    }
}
