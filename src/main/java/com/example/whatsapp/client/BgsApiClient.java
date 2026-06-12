package com.example.whatsapp.client;

import com.example.whatsapp.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class BgsApiClient {

    private static final Logger log = LoggerFactory.getLogger(BgsApiClient.class);

    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    public BgsApiClient(RestTemplate restTemplate, AppProperties appProperties) {
        this.restTemplate = restTemplate;
        this.appProperties = appProperties;
    }

    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-ID", appProperties.getBgs().getTenantId());
        return headers;
    }

    private HttpHeaders getHeadersWithAuth(String token) {
        HttpHeaders headers = getHeaders();
        if (token != null && !token.isBlank()) {
            headers.set("Authorization", "Bearer " + token);
        }
        return headers;
    }

    @Retryable(
            value = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    @CircuitBreaker(name = "bgsApi")
    public JsonNode get(String path) {
        return get(path, null);
    }

    @Retryable(
            value = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    @CircuitBreaker(name = "bgsApi")
    public JsonNode get(String path, String customerToken) {
        String url = appProperties.getBgs().getBaseUrl() + path;
        HttpEntity<Void> entity = new HttpEntity<>(getHeadersWithAuth(customerToken));
        
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("[BGS Client] GET request failed for path {}: {}", path, e.getMessage());
            throw e;
        }
    }

    @Retryable(
            value = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    @CircuitBreaker(name = "bgsApi")
    public JsonNode post(String path, Map<String, Object> body) {
        return post(path, body, null);
    }

    @Retryable(
            value = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    @CircuitBreaker(name = "bgsApi")
    public JsonNode post(String path, Map<String, Object> body, String customerToken) {
        String url = appProperties.getBgs().getBaseUrl() + path;
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, getHeadersWithAuth(customerToken));

        try {
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(url, entity, JsonNode.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            } else {
                log.error("[BGS Client] POST request returned non-2xx status for path {}: {}", path, response.getStatusCode());
                return null;
            }
        } catch (Exception e) {
            log.error("[BGS Client] POST request failed for path {}: {}", path, e.getMessage());
            throw e;
        }
    }

    @Retryable(
            value = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    @CircuitBreaker(name = "bgsApi")
    public JsonNode put(String path) {
        return put(path, null);
    }

    @Retryable(
            value = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    @CircuitBreaker(name = "bgsApi")
    public JsonNode put(String path, String customerToken) {
        String url = appProperties.getBgs().getBaseUrl() + path;
        HttpEntity<Void> entity = new HttpEntity<>(getHeadersWithAuth(customerToken));
        
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.PUT, entity, JsonNode.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("[BGS Client] PUT request failed for path {}: {}", path, e.getMessage());
            throw e;
        }
    }
}
