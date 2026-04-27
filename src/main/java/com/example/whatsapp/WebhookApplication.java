package com.example.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class WebhookApplication {

    public static void main(String[] args) {
        System.out.println("Starting WhatsApp Webhook on port 3002...");
        SpringApplication.run(WebhookApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        System.out.println("[BEAN] Creating RestTemplate bean...");
        return new RestTemplate();
    }
}

