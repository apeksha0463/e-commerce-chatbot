package com.example.whatsapp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app")
@Data
public class AppProperties {

    private final Bgs bgs = new Bgs();
    private final Aisensy aisensy = new Aisensy();
    private final Session session = new Session();
    private final Validation validation = new Validation();
    private final Rest rest = new Rest();

    @Data
    public static class Bgs {
        private String baseUrl = "https://be.bgsinfotech.com";
        private String tenantId;
    }

    @Data
    public static class Aisensy {
        private String apiKey;
        private String projectId;
    }

    @Data
    public static class Session {
        private int timeoutMinutes = 30;
    }

    @Data
    public static class Validation {
        private int minAddressLength = 10;
        private int pincodeLength = 6;
    }

    @Data
    public static class Rest {
        private int connectTimeout = 5000;
        private int readTimeout = 10000;
        private int maxConnTotal = 100;
        private int maxConnPerRoute = 20;
    }
}
