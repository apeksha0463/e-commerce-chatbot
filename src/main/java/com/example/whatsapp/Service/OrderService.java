package com.example.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Handles order creation.
 * Calls the BGS backend API to place the order.
 */
@Service
public class OrderService {

    private static final Logger log = Logger.getLogger(OrderService.class.getName());

    @Autowired
    private RestTemplate restTemplate;

    @Value("${bgs.base-url:https://be.bgsinfotech.com}")
    private String bgsBaseUrl;

    @Value("${bgs.tenant-id:697c756692a4f15176fefe8e}")
    private String bgsTenantId;

    @Value("${bgs.customer-token:}")
    private String customerToken;

    // ─────────────────────────────────────────────────────────────────────────

    public OrderResult createOrder(String phone,
                                   String productId,
                                   String productName,
                                   String price,
                                   String customerName,
                                   String address,
                                   String pincode,
                                   String paymentMethod) {
        try {
            // Generate a fallback local order ID just in case
            String localOrderId = "YOT-" + (100000 + new Random().nextInt(900000));

            String url = bgsBaseUrl + "/orders/orders";

            // Build items array as per spec
            Map<String, Object> item = new HashMap<>();
            item.put("itemId", productId);

            Map<String, Object> body = new HashMap<>();
            body.put("items", List.of(item));
            body.put("utmCampaign", "whatsapp_bot");
            body.put("utmSource", "whatsapp");
            body.put("utmMedium", "chat");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Tenant-ID", bgsTenantId);

            // Add Authorization header if customerToken is provided
            if (customerToken != null && !customerToken.isBlank()) {
                headers.set("Authorization", "Bearer " + customerToken);
            }

            log.info("[OrderService] Calling POST " + url + " to create order for " + phone);

            ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                    url, new HttpEntity<>(body, headers), JsonNode.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                JsonNode json = resp.getBody();
                String finalOrderId = localOrderId;
                
                // If backend returns a specific _id or orderId, use it for subsequent payment calls
                if (json.has("data")) {
                    JsonNode data = json.path("data");
                    if (data.has("_id")) {
                        finalOrderId = data.path("_id").asText();
                    } else if (data.has("id")) {
                        finalOrderId = data.path("id").asText();
                    } else if (data.has("orderId")) {
                        finalOrderId = data.path("orderId").asText();
                    }
                } else if (json.has("_id")) {
                    finalOrderId = json.path("_id").asText();
                } else if (json.has("orderId")) {
                    finalOrderId = json.path("orderId").asText();
                }

                log.info("[OrderService] ✅ Order successfully created via API. ID: " + finalOrderId);
                return OrderResult.success(finalOrderId);
            } else {
                log.warning("[OrderService] Backend returned non-success code: " + resp.getStatusCode());
                return OrderResult.failure("Failed to create order on BGS backend.");
            }

        } catch (Exception e) {
            log.severe("[OrderService] ❌ Failed to create order API call: " + e.getMessage());
            return OrderResult.failure(e.getMessage());
        }
    }

    // ── Result wrapper ────────────────────────────────────────────────────────

    public static class OrderResult {
        public final boolean success;
        public final String orderId;
        public final String errorMessage;

        private OrderResult(boolean success, String orderId, String errorMessage) {
            this.success      = success;
            this.orderId      = orderId;
            this.errorMessage = errorMessage;
        }

        public static OrderResult success(String orderId) {
            return new OrderResult(true, orderId, null);
        }

        public static OrderResult failure(String msg) {
            return new OrderResult(false, null, msg);
        }
    }
}
