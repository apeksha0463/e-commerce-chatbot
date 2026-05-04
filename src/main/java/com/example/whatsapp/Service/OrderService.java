package com.example.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Handles order creation against the BGS backend API.
 * Guarantees: order is persisted BEFORE any confirmation is sent.
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

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates an order in the BGS backend.
     *
     * @param phone         customer phone (WhatsApp number)
     * @param productId     selected product _id
     * @param productName   selected product name
     * @param price         final (discounted) price as a string
     * @param customerName  full name provided by user
     * @param address       delivery address
     * @param pincode       delivery pincode
     * @param paymentMethod COD | UPI | Online/Card
     * @return OrderResult with success flag, orderId, and optional error message
     */
    public OrderResult createOrder(String phone,
                                   String productId,
                                   String productName,
                                   String price,
                                   String customerName,
                                   String address,
                                   String pincode,
                                   String paymentMethod) {
        try {
            String url = bgsBaseUrl + "/orders";

            Map<String, Object> body = new HashMap<>();
            body.put("phone", phone);
            body.put("productId", productId);
            body.put("productName", productName);
            body.put("price", price);
            body.put("customerName", customerName);
            body.put("address", address + ", " + pincode);
            body.put("pincode", pincode);
            body.put("paymentMethod", paymentMethod);
            body.put("source", "WHATSAPP");

            HttpHeaders headers = buildHeaders();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<JsonNode> resp = restTemplate.postForEntity(url, entity, JsonNode.class);

            log.info("[OrderService] POST /orders → " + resp.getStatusCode());

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                JsonNode json = resp.getBody();
                // Resolve orderId from several possible field names
                String orderId = resolveOrderId(json);
                return OrderResult.success(orderId);
            } else {
                log.warning("[OrderService] Non-2xx response: " + resp.getStatusCode());
                return OrderResult.failure("Order API returned status " + resp.getStatusCode());
            }

        } catch (Exception e) {
            log.severe("[OrderService] ❌ Order creation failed: " + e.getMessage());
            return OrderResult.failure(e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private String resolveOrderId(JsonNode json) {
        for (String key : new String[]{"orderId", "order_id", "_id", "id"}) {
            if (json.has(key) && !json.path(key).asText("").isEmpty()) {
                return json.path(key).asText();
            }
        }
        // Nested under data
        JsonNode data = json.path("data");
        if (!data.isMissingNode()) {
            for (String key : new String[]{"orderId", "order_id", "_id", "id"}) {
                if (data.has(key) && !data.path(key).asText("").isEmpty()) {
                    return data.path(key).asText();
                }
            }
        }
        // Fallback: generate a local reference so the flow isn't broken
        return "YOT-" + (100000 + new Random().nextInt(900000));
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Tenant-ID", bgsTenantId);
        return h;
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
