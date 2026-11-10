package com.kai.dinnerbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DinnerBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(DinnerBotApplication.class, args);
    }
}
