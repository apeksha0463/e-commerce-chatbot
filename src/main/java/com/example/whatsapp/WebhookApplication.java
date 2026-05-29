package com.example.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableRetry
public class WebhookApplication {

    public static void main(String[] args) {
        System.out.println("Starting WhatsApp Webhook on port 3002...");
        SpringApplication.run(WebhookApplication.class, args);
    }
}

