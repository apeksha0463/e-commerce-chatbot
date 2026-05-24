package com.example.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Generates payment links for UPI / Card / Online orders.
 * COD orders skip this entirely.
 *
 * Integrate with a real payment gateway (Razorpay, PayU, etc.) by
 * replacing the stub implementation below.
 */
@Service
public class PaymentService {

    private static final Logger log = Logger.getLogger(PaymentService.class.getName());

    private static final int MAX_RETRIES = 2;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${bgs.base-url:https://be.bgsinfotech.com}")
    private String bgsBaseUrl;

    @Value("${bgs.tenant-id:697c756692a4f15176fefe8e}")
    private String bgsTenantId;

    // Payment gateway base URL and key (set in application.properties)
    @Value("${payment.gateway-url:https://api.razorpay.com/v1}")
    private String gatewayUrl;

    @Value("${payment.api-key:}")
    private String paymentApiKey;

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
    public PaymentResult generatePaymentLink(String orderId,
                                             String phone,
                                             String amount,
                                             String paymentMethod) {
        int attempts = 0;
        while (attempts <= MAX_RETRIES) {
            try {
                attempts++;
                log.info("[PaymentService] Attempt " + attempts
                        + " – generating link for order " + orderId);

                // ── Try BGS backend payment endpoint first ─────────────────
                String link = callBgsPaymentEndpoint(orderId, phone, amount, paymentMethod);
                if (link != null && !link.isBlank()) {
                    log.info("[PaymentService] ✅ Link from BGS: " + link);
                    return PaymentResult.success(link);
                }

                // ── Fallback: generate link via payment gateway directly ───
                if (paymentApiKey != null && !paymentApiKey.isBlank()) {
                    link = callGatewayDirectly(orderId, phone, amount);
                    if (link != null && !link.isBlank()) {
                        log.info("[PaymentService] ✅ Link from gateway: " + link);
                        return PaymentResult.success(link);
                    }
                }

                log.warning("[PaymentService] Attempt " + attempts + " yielded no link.");

            } catch (Exception e) {
                log.severe("[PaymentService] ❌ Attempt " + attempts + " failed: " + e.getMessage());
                if (attempts > MAX_RETRIES) {
                    return PaymentResult.failure(e.getMessage());
                }
            }
        }
        return PaymentResult.failure("Payment gateway did not return a link after "
                + MAX_RETRIES + " attempts.");
    }

    // ── BGS backend ───────────────────────────────────────────────────────────

    private String callBgsPaymentEndpoint(String orderId,
                                           String phone,
                                           String amount,
                                           String method) {
        try {
            String mappedMethod = "UPI";
            if ("Online/Card".equalsIgnoreCase(method)) {
                mappedMethod = "ONLINE";
            } else if ("UPI".equalsIgnoreCase(method)) {
                mappedMethod = "UPI";
            }

            String url = bgsBaseUrl + "/orders/orders/initiate-payment/" + orderId + "?method=" + mappedMethod;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Tenant-ID", bgsTenantId);

            log.info("[PaymentService] Initiating BGS payment PUT request: " + url);
            ResponseEntity<JsonNode> resp = restTemplate.exchange(
                    url, HttpMethod.PUT, new HttpEntity<>(headers), JsonNode.class);

            String sessionId = null;
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                JsonNode json = resp.getBody();
                JsonNode cfResp = json.path("cashFreeResponse");
                if (!cfResp.isMissingNode() && cfResp.has("payment_session_id")) {
                    sessionId = cfResp.path("payment_session_id").asText();
                    if (sessionId != null && sessionId.equals("null")) {
                        sessionId = null;
                    }
                }
            }

            if (sessionId == null || sessionId.isBlank()) {
                log.info("[PaymentService] Session ID not returned immediately, starting polling...");
                sessionId = pollForSessionId(orderId, headers);
            }

            if (sessionId != null && !sessionId.isBlank()) {
                return getCashfreeCheckoutUrl(sessionId);
            }
        } catch (Exception e) {
            log.warning("[PaymentService] BGS payment endpoint error: " + e.getMessage());
        }
        return null;
    }

    private String pollForSessionId(String trackingId, HttpHeaders headers) {
        String url = bgsBaseUrl + "/payments/api/payments/cashfree/payment/" + trackingId;
        for (int i = 1; i <= 6; i++) {
            try {
                log.info("[PaymentService] Polling payment session (attempt " + i + "/6) for: " + trackingId);
                ResponseEntity<JsonNode> resp = restTemplate.exchange(
                        url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                    JsonNode json = resp.getBody();
                    JsonNode cfResp = json.path("cashFreeResponse");
                    if (!cfResp.isMissingNode() && cfResp.has("payment_session_id")) {
                        String sessionId = cfResp.path("payment_session_id").asText();
                        if (sessionId != null && !sessionId.isBlank() && !sessionId.equals("null")) {
                            log.info("[PaymentService] Found payment session ID: " + sessionId);
                            return sessionId;
                        }
                    }
                }
            } catch (Exception e) {
                log.warning("[PaymentService] Error during session polling: " + e.getMessage());
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
        if (bgsBaseUrl.contains("bgsinfotech.com") || bgsBaseUrl.contains("sandbox") || bgsBaseUrl.contains("staging")) {
            return "https://sandbox.cashfree.com/order/#" + sessionId;
        }
        return "https://payments.cashfree.com/order/#" + sessionId;
    }

    // ── Direct gateway (Razorpay-style) ──────────────────────────────────────

    private String callGatewayDirectly(String orderId, String phone, String amount) {
        try {
            // Convert amount to paise (Razorpay expects smallest currency unit)
            long amountPaise = Math.round(Double.parseDouble(amount) * 100);

            String url = gatewayUrl + "/payment_links";

            Map<String, Object> body = new HashMap<>();
            body.put("amount", amountPaise);
            body.put("currency", "INR");
            body.put("description", "Order #" + orderId);
            body.put("reference_id", orderId);
            Map<String, String> customer = new HashMap<>();
            customer.put("contact", phone);
            body.put("customer", customer);
            body.put("notify", Map.of("sms", false, "email", false));
            body.put("reminder_enable", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (paymentApiKey.contains(":")) {
                String[] parts = paymentApiKey.split(":", 2);
                headers.setBasicAuth(parts[0], parts[1]);
            } else {
                headers.setBasicAuth(paymentApiKey, "");
            }

            ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                    url, new HttpEntity<>(body, headers), JsonNode.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                JsonNode json = resp.getBody();
                return json.path("short_url").asText(json.path("url").asText(""));
            }
        } catch (Exception e) {
            log.warning("[PaymentService] Gateway call error: " + e.getMessage());
        }
        return null;
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
