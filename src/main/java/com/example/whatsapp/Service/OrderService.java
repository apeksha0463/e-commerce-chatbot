package com.example.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.whatsapp.client.BgsApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Handles order creation.
 * Calls the BGS backend API to place the order.
 */
@Service
@Slf4j
public class OrderService {

    @Autowired
    private BgsApiClient bgsApiClient;

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

            String url = "/orders/orders";

            // Build items array as per spec
            Map<String, Object> item = new HashMap<>();
            item.put("itemId", productId);

            Map<String, Object> body = new HashMap<>();
            body.put("items", List.of(item));
            body.put("utmCampaign", "whatsapp_bot");
            body.put("utmSource", "whatsapp");
            body.put("utmMedium", "chat");

            // Inject the collected address and user details
            Map<String, Object> shippingAddress = new HashMap<>();
            shippingAddress.put("name", customerName);
            shippingAddress.put("phone", phone);
            shippingAddress.put("addressLine1", address);
            shippingAddress.put("zipCode", pincode);
            shippingAddress.put("addressType", "HOME");
            body.put("shippingAddress", shippingAddress);

            // Inject payment method
            Map<String, Object> paymentData = new HashMap<>();
            paymentData.put("method", paymentMethod);
            body.put("payment", paymentData);

            log.info("[OrderService] Calling POST /orders/orders to create order for {}", phone);

            JsonNode json = bgsApiClient.post("/orders/orders", body, customerToken);

            if (json != null) {
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

                log.info("[OrderService] ✅ Order successfully created via API. ID: {}", finalOrderId);
                return OrderResult.success(finalOrderId);
            } else {
                log.warn("[OrderService] Backend returned null response or non-success code");
                return OrderResult.failure("Failed to create order on BGS backend.");
            }

        } catch (Exception e) {
            log.error("[OrderService] ❌ Failed to create order API call: {}", e.getMessage(), e);
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
