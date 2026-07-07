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

/**
 * HTTP client for all BGS backend API calls.
 *
 * IMPORTANT — AOP self-call note:
 * Spring AOP intercepts @Retryable and @CircuitBreaker only on calls made
 * THROUGH the proxy (i.e. from outside this class). Internal calls like
 * get(path) → get(path, null) bypass the proxy entirely, so annotations on
 * the no-auth overloads would be silently ignored.
 *
 * FIX: Only the full-signature methods (those with `customerToken`) carry
 * the AOP annotations. The convenience no-token overloads are plain delegates.
 */
@Service
public class BgsApiClient {

    private static final Logger log = LoggerFactory.getLogger(BgsApiClient.class);

    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    public BgsApiClient(RestTemplate restTemplate, AppProperties appProperties) {
        this.restTemplate = restTemplate;
        this.appProperties = appProperties;
    }

    // ── Header builders ──────────────────────────────────────────────────────

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

    // ── GET ──────────────────────────────────────────────────────────────────

    /**
     * Convenience overload — no auth token. Delegates to the annotated method
     * through the Spring context so AOP works correctly; use the injected bean
     * rather than calling this directly when you need retry/circuit-breaker on
     * anonymous calls.
     */
    public JsonNode get(String path) {
        return get(path, null);
    }

    /**
     * Full GET with optional auth token.
     * @Retryable and @CircuitBreaker are applied HERE (not on the overload above)
     * to avoid the Spring AOP self-invocation bypass.
     */
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
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("[BGS Client] GET {} returned non-2xx status: {}",
                        url, response.getStatusCode());
                return null;
            }
            return response.getBody();
        } catch (Exception e) {
            log.error("[BGS Client] GET request failed for URL {}: {}", url, e.getMessage());
            throw e;
        }
    }

    // ── POST ─────────────────────────────────────────────────────────────────

    /** Convenience overload — no auth token. */
    public JsonNode post(String path, Map<String, Object> body) {
        return post(path, body, null);
    }

    /**
     * Full POST with optional auth token.
     * Retry and circuit-breaker annotations live here only.
     */
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
                log.error("[BGS Client] POST {} returned non-2xx status: {}",
                        url, response.getStatusCode());
                return null;
            }
        } catch (Exception e) {
            log.error("[BGS Client] POST request failed for URL {}: {}", url, e.getMessage());
            throw e;
        }
    }

    // ── PUT ──────────────────────────────────────────────────────────────────

    /** Convenience overload — no auth token. */
    public JsonNode put(String path) {
        return put(path, null);
    }

    /**
     * Full PUT with optional auth token.
     * Retry and circuit-breaker annotations live here only.
     */
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
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("[BGS Client] PUT {} returned non-2xx status: {}",
                        url, response.getStatusCode());
                return null;
            }
            return response.getBody();
        } catch (Exception e) {
            log.error("[BGS Client] PUT request failed for URL {}: {}", url, e.getMessage());
            throw e;
        }
    }
}
