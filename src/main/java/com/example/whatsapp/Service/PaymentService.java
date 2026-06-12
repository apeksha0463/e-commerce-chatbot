package com.example.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.whatsapp.client.BgsApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

/**
 * Generates payment links for UPI / Card / Online orders.
 * COD orders skip this entirely.
 *
 * Integrate with a real payment gateway (Razorpay, PayU, etc.) by
 * replacing the stub implementation below.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    @Autowired
    private BgsApiClient bgsApiClient;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates a payment link for the given order.
     *
     * @param orderId       the order ID returned by the backend
     * @param phone         customer phone
     * @param amount        final amount as string (e.g. "1299")
     * @param paymentMethod UPI | Online/Card
     * @return PaymentResult with success flag and payment link (or error)
     */
    @Retryable(
            value = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public PaymentResult generatePaymentLink(String orderId,
                                             String phone,
                                             String amount,
                                             String paymentMethod,
                                             String userAuthToken) {
        try {
            log.info("[PaymentService] Generating link for order {}", orderId);

            // ── Try BGS backend payment endpoint first ─────────────────
            String link = callBgsPaymentEndpoint(orderId, phone, amount, paymentMethod, userAuthToken);
            if (link != null && !link.isBlank()) {
                log.info("[PaymentService] ✅ Link from BGS: {}", link);
                return PaymentResult.success(link);
            }

            log.warn("[PaymentService] Yielded no link.");

        } catch (Exception e) {
            log.error("[PaymentService] ❌ Failed: {}", e.getMessage(), e);
            throw new RuntimeException("Payment generation failed", e);
        }
        return PaymentResult.failure("Payment gateway did not return a link.");
    }

    // ── BGS backend ───────────────────────────────────────────────────────────

    private String callBgsPaymentEndpoint(String orderId,
                                           String phone,
                                           String amount,
                                           String method,
                                           String userAuthToken) {
        try {
            String mappedMethod = "UPI";
            if ("ONLINE".equalsIgnoreCase(method)) {
                mappedMethod = "ONLINE";
            } else if ("UPI".equalsIgnoreCase(method)) {
                mappedMethod = "UPI";
            }

            String path = "/orders/orders/initiate-payment/" + orderId + "?method=" + mappedMethod;

            log.info("[PaymentService] Initiating BGS payment PUT request: {}", path);
            JsonNode json = bgsApiClient.put(path, userAuthToken);

            String sessionId = null;
            if (json != null) {
                JsonNode cfResp = json.path("cashFreeResponse");
                if (cfResp.isMissingNode() && json.has("data")) {
                    cfResp = json.path("data").path("cashFreeResponse");
                }
                
                if (!cfResp.isMissingNode() && cfResp.has("payment_session_id")) {
                    sessionId = cfResp.path("payment_session_id").asText();
                    if (sessionId != null && sessionId.equals("null")) {
                        sessionId = null;
                    }
                }
            }

            if (sessionId == null || sessionId.isBlank()) {
                log.info("[PaymentService] Session ID not returned immediately, starting polling...");
                sessionId = pollForSessionId(orderId, userAuthToken);
            }

            if (sessionId != null && !sessionId.isBlank()) {
                return getCashfreeCheckoutUrl(sessionId);
            }
        } catch (Exception e) {
            log.warn("[PaymentService] BGS payment endpoint error: {}", e.getMessage());
        }
        return null;
    }

    private String pollForSessionId(String trackingId, String userAuthToken) {
        String path = "/payments/api/payments/cashfree/payment/" + trackingId;
        for (int i = 1; i <= 6; i++) {
            try {
                log.info("[PaymentService] Polling payment session (attempt {}/6) for: {}", i, trackingId);
                JsonNode json = bgsApiClient.get(path, userAuthToken);

                if (json != null) {
                    JsonNode cfResp = json.path("cashFreeResponse");
                    if (cfResp.isMissingNode() && json.has("data")) {
                        cfResp = json.path("data").path("cashFreeResponse");
                    }
                    
                    if (!cfResp.isMissingNode() && cfResp.has("payment_session_id")) {
                        String sessionId = cfResp.path("payment_session_id").asText();
                        if (sessionId != null && !sessionId.isBlank() && !sessionId.equals("null")) {
                            log.info("[PaymentService] Found payment session ID: {}", sessionId);
                            return sessionId;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[PaymentService] Error during session polling: {}", e.getMessage());
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private String getCashfreeCheckoutUrl(String sessionId) {
        return "https://payments.cashfree.com/order/#" + sessionId;
    }


    // ── Result wrapper ────────────────────────────────────────────────────────

    public static class PaymentResult {
        public final boolean success;
        public final String paymentLink;
        public final String errorMessage;

        private PaymentResult(boolean success, String paymentLink, String errorMessage) {
            this.success      = success;
            this.paymentLink  = paymentLink;
            this.errorMessage = errorMessage;
        }

        public static PaymentResult success(String link) {
            return new PaymentResult(true, link, null);
        }

        public static PaymentResult failure(String msg) {
            return new PaymentResult(false, null, msg);
        }
    }
}
