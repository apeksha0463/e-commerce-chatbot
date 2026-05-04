package com.example.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.logging.Logger;

/**
 * Fetches active offers from the BGS backend and resolves discounted prices.
 * Endpoint: GET /offers?status=ACTIVE  (or /offers/offers/type/PROMOTIONAL)
 */
@Service
public class OfferService {

    private static final Logger log = Logger.getLogger(OfferService.class.getName());

    @Autowired
    private RestTemplate restTemplate;

    @Value("${bgs.base-url:https://be.bgsinfotech.com}")
    private String bgsBaseUrl;

    @Value("${bgs.tenant-id:697c756692a4f15176fefe8e}")
    private String bgsTenantId;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches active offers and formats them for WhatsApp display.
     */
    public String fetchActiveOffersMessage() {
        // Try preferred endpoint first, fall back to promotional endpoint
        JsonNode data = bgsGet("/offers?status=ACTIVE");
        JsonNode list = resolveList(data);

        if (list == null || list.size() == 0) {
            // fallback to promotional
            data = bgsGet("/offers/offers/type/PROMOTIONAL");
            list = resolveList(data);
        }

        if (list == null || list.size() == 0) {
            log.info("[OfferService] No active offers found.");
            return "No active offers at the moment.\n"
                    + "Check back soon for great deals.\n\n"
                    + "_Type *browse* to explore products or *back* for main menu._";
        }

        StringBuilder sb = new StringBuilder("*Current Offers & Deals:*\n\n");
        int max = Math.min(list.size(), 8);
        for (int i = 0; i < max; i++) {
            JsonNode o = list.get(i);
            String code  = o.path("code").asText(o.path("couponCode").asText("OFFER" + (i + 1)));
            String title = o.path("title").asText(o.path("name").asText(""));
            String desc  = o.path("description").asText("");
            String dtype = o.path("discountType").asText("");
            String dval  = o.path("discountValue").asText("");
            String minOrd = o.path("minOrderValue").asText(o.path("minimumOrderAmount").asText(""));

            sb.append("━━━━━━━━━━━━━━━━\n");
            if (!title.isEmpty()) sb.append("*").append(title).append("*\n");
            sb.append("Code: *").append(code).append("*\n");

            if ("PERCENTAGE".equalsIgnoreCase(dtype)) {
                sb.append("*").append(dval).append("% OFF*\n");
            } else if ("FLAT".equalsIgnoreCase(dtype) || !dval.isEmpty()) {
                sb.append("*₹").append(dval).append(" OFF*\n");
            }
            if (!minOrd.isEmpty()) sb.append("Min order: ₹").append(minOrd).append("\n");
            if (!desc.isEmpty())   sb.append("Note: ").append(desc).append("\n");
            sb.append("\n");
        }
        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("_Type *browse* to shop or *back* for main menu._");
        return sb.toString();
    }

    /**
     * Applies the best applicable offer to a raw price.
     * Returns DiscountResult with final price and offer description.
     *
     * Currently: product-level discount via salePrice / discountedPrice fields.
     * Extend with coupon / cart-level logic as needed.
     */
    public DiscountResult applyBestOffer(JsonNode productDetails) {
        if (productDetails == null) {
            return DiscountResult.noDiscount("0");
        }

        // Prefer discountedPrice / salePrice from product itself
        String originalStr  = productDetails.path("price")
                .asText(productDetails.path("mrp").asText("0"));
        String discountedStr = productDetails.path("discountedPrice")
                .asText(productDetails.path("salePrice")
                        .asText(productDetails.path("sellingPrice").asText("")));

        double original   = parsePrice(originalStr);
        double discounted = discountedStr.isEmpty() ? original : parsePrice(discountedStr);

        // Inline product offer
        String offerLabel = productDetails.path("offerLabel")
                .asText(productDetails.path("badge").asText(""));

        if (discounted < original && discounted > 0) {
            int pct = (int) Math.round((original - discounted) / original * 100);
            String offerText = offerLabel.isEmpty() ? pct + "% off" : offerLabel;
            return new DiscountResult(
                    fmt(original),
                    fmt(discounted),
                    offerText,
                    true
            );
        }

        // No discount
        return DiscountResult.noDiscount(originalStr.isEmpty() ? "0" : originalStr);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private JsonNode bgsGet(String path) {
        try {
            String url = bgsBaseUrl + path;
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.set("X-Tenant-ID", bgsTenantId);
            ResponseEntity<JsonNode> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
            log.info("[OfferService] GET " + path + " → " + resp.getStatusCode());
            return resp.getBody();
        } catch (Exception e) {
            log.warning("[OfferService] Error calling " + path + ": " + e.getMessage());
            return null;
        }
    }

    private JsonNode resolveList(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) return node;
        for (String key : new String[]{"data", "offers", "items", "results"}) {
            if (node.has(key) && node.get(key).isArray()) return node.get(key);
        }
        return null;
    }

    private double parsePrice(String s) {
        try { return Double.parseDouble(s.replaceAll("[^\\d.]", "")); }
        catch (Exception e) { return 0; }
    }

    private String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    // ── DiscountResult ─────────────────────────────────────────────────────────

    public static class DiscountResult {
        public final String originalPrice;
        public final String finalPrice;
        public final String offerText;
        public final boolean hasDiscount;

        public DiscountResult(String originalPrice, String finalPrice,
                              String offerText, boolean hasDiscount) {
            this.originalPrice = originalPrice;
            this.finalPrice    = finalPrice;
            this.offerText     = offerText;
            this.hasDiscount   = hasDiscount;
        }

        public static DiscountResult noDiscount(String price) {
            return new DiscountResult(price, price, "", false);
        }

        /** Formatted price line for WhatsApp. */
        public String toWhatsAppLine() {
            if (hasDiscount) {
                return "Price: ₹" + finalPrice + " ~~₹" + originalPrice + "~~ (" + offerText + ")";
            }
            return "Price: ₹" + finalPrice;
        }
    }
}
