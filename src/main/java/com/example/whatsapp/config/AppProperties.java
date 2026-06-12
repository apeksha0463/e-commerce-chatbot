package com.example.whatsapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Bgs bgs = new Bgs();
    private final Aisensy aisensy = new Aisensy();
    private final Session session = new Session();
    private final Validation validation = new Validation();
    private final Rest rest = new Rest();

    public Bgs getBgs() { return bgs; }
    public Aisensy getAisensy() { return aisensy; }
    public Session getSession() { return session; }
    public Validation getValidation() { return validation; }
    public Rest getRest() { return rest; }

    public static class Bgs {
        private String baseUrl = "https://be.bgsinfotech.com";
        private String tenantId;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    }

    public static class Aisensy {
        private String apiKey;
        private String projectId;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
    }

    public static class Session {
        private int timeoutMinutes = 30;

        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
    }

    public static class Validation {
        private int minAddressLength = 10;
        private int pincodeLength = 6;

        public int getMinAddressLength() { return minAddressLength; }
        public void setMinAddressLength(int minAddressLength) { this.minAddressLength = minAddressLength; }
        public int getPincodeLength() { return pincodeLength; }
        public void setPincodeLength(int pincodeLength) { this.pincodeLength = pincodeLength; }
    }

    public static class Rest {
        private int connectTimeout = 5000;
        private int readTimeout = 10000;
        private int maxConnTotal = 100;
        private int maxConnPerRoute = 20;

        public int getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(int connectTimeout) { this.connectTimeout = connectTimeout; }
        public int getReadTimeout() { return readTimeout; }
        public void setReadTimeout(int readTimeout) { this.readTimeout = readTimeout; }
        public int getMaxConnTotal() { return maxConnTotal; }
        public void setMaxConnTotal(int maxConnTotal) { this.maxConnTotal = maxConnTotal; }
        public int getMaxConnPerRoute() { return maxConnPerRoute; }
        public void setMaxConnPerRoute(int maxConnPerRoute) { this.maxConnPerRoute = maxConnPerRoute; }
    }
}
