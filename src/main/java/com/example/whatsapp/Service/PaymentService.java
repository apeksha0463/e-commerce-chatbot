package com.example.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.whatsapp.client.BgsApiClient;
import com.example.whatsapp.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Generates payment links for UPI / Card / Online orders.
 * COD orders skip this entirely.
 *
 * NOTE: @Retryable is intentionally NOT placed here.
 * BgsApiClient already applies @Retryable (maxAttempts=3) on all its HTTP
 * calls,
 * adding another @Retryable here would result in up to 9 total attempts (3 ×
 * 3),
 * causing excessive delays and load on the BGS backend.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final int POLL_MAX_ATTEMPTS = 6;
    private static final long POLL_DELAY_MS = 2000L;

    @Autowired
    private BgsApiClient bgsApiClient;

    @Autowired
    private AppProperties appProperties;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates a Cashfree payment link for the given order.
     *
     * @param orderId       the order ID returned by the backend
     * @param phone         customer phone
     * @param amount        final amount as string (e.g. "1299")
     * @param paymentMethod UPI | ONLINE
     * @param userAuthToken BGS bearer token for the customer (may be null — server
     *                      token used as fallback)
     * @return PaymentResult with success flag and payment link (or error)
     */
    public PaymentResult generatePaymentLink(String orderId,
            String phone,
            String amount,
            String paymentMethod,
            String userAuthToken) {
        try {
            log.info("[PaymentService] ▶ Initiating payment — order={}, phone={}, method={}, amount={}",
                    orderId, phone, paymentMethod, amount);

            String link = callBgsPaymentEndpoint(orderId, phone, paymentMethod, userAuthToken);

            if (link != null && !link.isBlank()) {
                log.info("[PaymentService] ✅ Payment link obtained for order {}: {}", orderId, link);
                return PaymentResult.success(link);
            }

            log.error("[PaymentService] ❌ BGS did not return a Cashfree session ID for order {}. "
                    + "Possible causes: (1) BGS_SERVER_TOKEN missing/expired → BGS returns 401; "
                    + "(2) orderId '{}' not found on BGS backend → BGS returns 404; "
                    + "(3) app.bgs.payment-redirect-url is not set; "
                    + "(4) BGS async delay exceeded polling window.",
                    orderId, orderId);

        } catch (Exception e) {
            log.error("[PaymentService] ❌ Exception generating payment link for order {}: {}",
                    orderId, e.getMessage(), e);
        }

        return PaymentResult.failure("Payment gateway did not return a link. Please try again or choose COD.");
    }

    // ── BGS backend ───────────────────────────────────────────────────────────

    private String callBgsPaymentEndpoint(String orderId,
            String phone,
            String method,
            String userAuthToken) {
        // Map chatbot payment method to BGS API expected value
        String mappedMethod = "ONLINE".equalsIgnoreCase(method) ? "ONLINE" : "UPI";

        // Build WhatsApp deep link so Cashfree redirects the user back to THIS chat
        // after payment.
        // It must be percent-encoded because it becomes a value inside the BGS query
        // string.
        String redirectUrl = buildWhatsAppRedirectUrl(phone);
        log.info("[PaymentService] WhatsApp redirect URL for phone={}: {}", phone, redirectUrl);

        String encodedRedirectUrl;
        try {
            encodedRedirectUrl = java.net.URLEncoder.encode(redirectUrl, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[PaymentService] Failed to encode redirect URL, using empty string: {}", e.getMessage());
            encodedRedirectUrl = "";
        }

        String path = "/orders/orders/initiate-payment/" + orderId
                + "?method=" + mappedMethod
                + "&redirectUrlForOnlinePayment=" + encodedRedirectUrl;
        log.info("[PaymentService] Calling PUT {} for order={} method={}", path, orderId, mappedMethod);

        try {
            JsonNode json = bgsApiClient.put(path, userAuthToken);

            if (json == null) {
                log.error("[PaymentService] BGS returned null for PUT {}. "
                        + "Likely cause: BGS_SERVER_TOKEN missing/expired (401) or orderId='{}' not found (404).",
                        path, orderId);
                return null;
            }

            log.debug("[PaymentService] BGS initiate-payment raw response for order {}: {}", orderId, json);

            String sessionId = extractSessionId(json);

            if (sessionId == null || sessionId.isBlank()) {
                // BGS may return the payment session ID asynchronously — poll for up to 12s
                log.info("[PaymentService] Session ID not in immediate response — polling BGS for up to {}s...",
                        (POLL_MAX_ATTEMPTS * POLL_DELAY_MS / 1000));
                sessionId = pollForSessionId(orderId, userAuthToken);
            }

            if (sessionId != null && !sessionId.isBlank()) {
                log.info("[PaymentService] Cashfree session ID obtained for order {}: {}", orderId, sessionId);
                return buildCashfreeCheckoutUrl(sessionId);
            }

            log.error("[PaymentService] No Cashfree session ID after polling for order '{}'. BGS response: {}",
                    orderId, json);

        } catch (Exception e) {
            log.error("[PaymentService] ❌ Exception calling BGS payment endpoint for order='{}': {}",
                    orderId, e.getMessage(), e);
        }

        return null;
    }

    /**
     * Extracts payment_session_id from a BGS payment response.
     * Checks both root-level and nested data.cashFreeResponse.
     */
    private String extractSessionId(JsonNode json) {
        if (json == null)
            return null;

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
     * Polls the BGS payment status endpoint until a session ID appears or attempts
     * are exhausted.
     *
     * WARNING: This method blocks the calling thread for up to
     * POLL_MAX_ATTEMPTS × POLL_DELAY_MS = 12 seconds.
     * Ensure RestTemplate readTimeout is configured > 12 seconds in
     * application.properties.
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

    /**
     * Builds a WhatsApp deep link that sends the user back to THIS specific chat
     * after Cashfree payment is complete.
     *
     * Format: https://wa.me/<phone>?text=<pre-filled message>
     * The phone is the customer's full international number (e.g. 919876543210).
     */
    private String buildWhatsAppRedirectUrl(String phone) {
        try {
            // URL-encode the pre-filled message so it survives as a query param
            String message = java.net.URLEncoder.encode(
                    "Hi! I just completed my payment. Please confirm my order status.",
                    java.nio.charset.StandardCharsets.UTF_8);
            return "https://wa.me/" + phone + "?text=" + message;
        } catch (Exception e) {
            log.warn("[PaymentService] Failed to build WhatsApp redirect URL for phone={}: {}", phone, e.getMessage());
            return "https://wa.me/" + phone;
        }
    }

    private String buildCashfreeCheckoutUrl(String sessionId) {
        // Cashfree uses URL fragment (#) for payment session routing — per Cashfree JS
        // SDK docs
        return "https://payments.cashfree.com/order/#" + sessionId;
    }

    // ── Result wrapper ────────────────────────────────────────────────────────

    public static class PaymentResult {
        public final boolean success;
        public final String paymentLink;
        public final String errorMessage;

        private PaymentResult(boolean success, String paymentLink, String errorMessage) {
            this.success = success;
            this.paymentLink = paymentLink;
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
