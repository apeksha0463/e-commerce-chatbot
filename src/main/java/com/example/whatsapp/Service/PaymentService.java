package com.example.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.whatsapp.client.BgsApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Generates payment links for UPI / Card / Online orders.
 * COD orders skip this entirely.
 *
 * NOTE: @Retryable is intentionally NOT placed here.
 * BgsApiClient already applies @Retryable (maxAttempts=3) on all its HTTP calls,
 * adding another @Retryable here would result in up to 9 total attempts (3 × 3),
 * causing excessive delays and load on the BGS backend.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final int POLL_MAX_ATTEMPTS   = 6;
    private static final long POLL_DELAY_MS      = 2000L;

    @Autowired
    private BgsApiClient bgsApiClient;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates a payment link for the given order.
     *
     * @param orderId       the order ID returned by the backend
     * @param phone         customer phone
     * @param amount        final amount as string (e.g. "1299")
     * @param paymentMethod UPI | ONLINE
     * @param userAuthToken BGS bearer token for the customer
     * @return PaymentResult with success flag and payment link (or error)
     */
    public PaymentResult generatePaymentLink(String orderId,
                                             String phone,
                                             String amount,
                                             String paymentMethod,
                                             String userAuthToken) {
        try {
            log.info("[PaymentService] Generating payment link for order={}, phone={}, method={}",
                    orderId, phone, paymentMethod);

            String link = callBgsPaymentEndpoint(orderId, paymentMethod, userAuthToken);

            if (link != null && !link.isBlank()) {
                log.info("[PaymentService] ✅ Payment link obtained for order {}: {}", orderId, link);
                return PaymentResult.success(link);
            }

            log.warn("[PaymentService] BGS did not return a payment link for order {}", orderId);

        } catch (Exception e) {
            log.error("[PaymentService] ❌ Failed to generate payment link for order {}: {}",
                    orderId, e.getMessage(), e);
        }

        return PaymentResult.failure("Payment gateway did not return a link.");
    }

    // ── BGS backend ───────────────────────────────────────────────────────────

    private String callBgsPaymentEndpoint(String orderId,
                                          String method,
                                          String userAuthToken) {
        // Map chatbot payment method to BGS API expected value
        String mappedMethod = "ONLINE".equalsIgnoreCase(method) ? "ONLINE" : "UPI";

        String path = "/orders/orders/initiate-payment/" + orderId + "?method=" + mappedMethod;
        log.info("[PaymentService] PUT {} for order {}", path, orderId);

        try {
            JsonNode json = bgsApiClient.put(path, userAuthToken);
            String sessionId = extractSessionId(json);

            if (sessionId == null || sessionId.isBlank()) {
                // NOTE: BGS returns the payment session ID asynchronously.
                // Polling blocks the servlet thread for up to POLL_MAX_ATTEMPTS × POLL_DELAY_MS ms.
                // This is a BGS backend limitation — chatbot cannot fix the async response.
                log.info("[PaymentService] Session ID not returned immediately — polling (max {}s)...",
                        (POLL_MAX_ATTEMPTS * POLL_DELAY_MS / 1000));
                sessionId = pollForSessionId(orderId, userAuthToken);
            }

            if (sessionId != null && !sessionId.isBlank()) {
                return buildCashfreeCheckoutUrl(sessionId);
            }

        } catch (Exception e) {
            log.warn("[PaymentService] BGS payment endpoint error for order {}: {}", orderId, e.getMessage());
        }

        return null;
    }

    /**
     * Extracts payment_session_id from a BGS payment response.
     * Checks both root-level and nested data.cashFreeResponse.
     */
    private String extractSessionId(JsonNode json) {
        if (json == null) return null;

        // Try root-level cashFreeResponse first
        JsonNode cfResp = json.path("cashFreeResponse");
        if (cfResp.isMissingNode() && json.has("data")) {
            cfResp = json.path("data").path("cashFreeResponse");
        }

        if (!cfResp.isMissingNode() && cfResp.has("payment_session_id")) {
            String sessionId = cfResp.path("payment_session_id").asText("").trim();
            if (!sessionId.isEmpty() && !sessionId.equals("null")) {
                return sessionId;
            }
        }

        return null;
    }

    /**
     * Polls the BGS payment status endpoint until a session ID appears or attempts exhausted.
     *
     * WARNING: This method blocks the calling thread for up to
     * POLL_MAX_ATTEMPTS × POLL_DELAY_MS = 12 seconds.
     * This is a known trade-off due to the BGS backend's asynchronous payment init.
     * Ensure RestTemplate readTimeout is configured > 12 seconds in application.properties.
     */
    private String pollForSessionId(String trackingId, String userAuthToken) {
        String path = "/payments/api/payments/cashfree/payment/" + trackingId;

        for (int attempt = 1; attempt <= POLL_MAX_ATTEMPTS; attempt++) {
            try {
                log.info("[PaymentService] Polling payment session (attempt {}/{}) for order: {}",
                        attempt, POLL_MAX_ATTEMPTS, trackingId);

                JsonNode json = bgsApiClient.get(path, userAuthToken);
                String sessionId = extractSessionId(json);

                if (sessionId != null && !sessionId.isBlank()) {
                    log.info("[PaymentService] Found payment session ID on attempt {}: {}", attempt, sessionId);
                    return sessionId;
                }

            } catch (Exception e) {
                log.warn("[PaymentService] Error during polling attempt {}/{} for order {}: {}",
                        attempt, POLL_MAX_ATTEMPTS, trackingId, e.getMessage());
            }

            if (attempt < POLL_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(POLL_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[PaymentService] Polling interrupted for order {}", trackingId);
                    break;
                }
            }
        }

        log.warn("[PaymentService] Payment session ID not obtained after {} attempts for order {}",
                POLL_MAX_ATTEMPTS, trackingId);
        return null;
    }

    private String buildCashfreeCheckoutUrl(String sessionId) {
        // Cashfree uses URL fragment (#) for payment session routing — this is intentional per Cashfree docs
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
