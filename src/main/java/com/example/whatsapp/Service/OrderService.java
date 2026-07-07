package com.example.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.whatsapp.client.BgsApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles order creation by calling the BGS backend API.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final String ORDER_ENDPOINT = "/orders/orders";

    @Autowired
    private BgsApiClient bgsApiClient;

    // ─────────────────────────────────────────────────────────────────────────

    public OrderResult createOrder(String phone,
                                   String productId,
                                   String productName,
                                   String price,
                                   String customerName,
                                   String address,
                                   String pincode,
                                   String paymentMethod,
                                   String userAuthToken) {
        try {
            // Generate a fallback local order ID (used only if BGS does not return one)
            // ThreadLocalRandom is preferred over new Random() in concurrent environments
            String localOrderId = "YOT-" + (100000 + ThreadLocalRandom.current().nextInt(900000));

            Map<String, Object> item = new HashMap<>();
            item.put("itemId", productId);

            Map<String, Object> shippingAddress = new HashMap<>();
            shippingAddress.put("name", customerName);
            shippingAddress.put("phone", phone);
            shippingAddress.put("addressLine1", address);
            shippingAddress.put("zipCode", pincode);
            shippingAddress.put("addressType", "HOME");

            Map<String, Object> paymentData = new HashMap<>();
            paymentData.put("method", paymentMethod);

            Map<String, Object> body = new HashMap<>();
            body.put("items", List.of(item));
            body.put("shippingAddress", shippingAddress);
            body.put("payment", paymentData);
            body.put("utmCampaign", "whatsapp_bot");
            body.put("utmSource", "whatsapp");
            body.put("utmMedium", "chat");

            log.info("[OrderService] Calling POST {} for phone={}, product='{}' (id={}), price={}",
                    ORDER_ENDPOINT, phone, productName, productId, price);

            JsonNode json = bgsApiClient.post(ORDER_ENDPOINT, body, userAuthToken);

            if (json != null) {
                String finalOrderId = extractOrderId(json, localOrderId);
                log.info("[OrderService] ✅ Order successfully created. ID: {}", finalOrderId);
                return OrderResult.success(finalOrderId);
            } else {
                log.warn("[OrderService] Backend returned null response for order creation");
                return OrderResult.failure("Failed to create order on BGS backend.");
            }

        } catch (Exception e) {
            log.error("[OrderService] ❌ Failed to create order for phone={}: {}", phone, e.getMessage(), e);
            return OrderResult.failure(e.getMessage());
        }
    }

    /**
     * Extracts the order ID from the BGS response.
     * BGS backend may return the ID in different locations/fields.
     * NOTE (BGS backend issue): BGS should standardise order ID field name.
     */
    private String extractOrderId(JsonNode json, String fallbackId) {
        // Check root-level fields
        for (String field : new String[]{"_id", "id", "orderId", "orderNumber"}) {
            if (json.has(field)) {
                String val = json.path(field).asText("").trim();
                if (!val.isEmpty() && !val.equals("null")) {
                    return val;
                }
            }
        }

        // Check nested under "data"
        if (json.has("data")) {
            JsonNode data = json.path("data");
            for (String field : new String[]{"_id", "id", "orderId", "orderNumber"}) {
                if (data.has(field)) {
                    String val = data.path(field).asText("").trim();
                    if (!val.isEmpty() && !val.equals("null")) {
                        return val;
                    }
                }
            }
        }

        log.warn("[OrderService] Could not extract order ID from response — using local fallback: {}", fallbackId);
        return fallbackId;
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
