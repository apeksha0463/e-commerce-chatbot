package com.example.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.whatsapp.client.BgsApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Fetches active offers from the BGS backend and resolves discounted prices.
 * Endpoint: GET /offers?status=ACTIVE  (or /offers/offers/type/PROMOTIONAL)
 */
@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    @Autowired
    private BgsApiClient bgsApiClient;

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
                    + "0. Main Menu\n\n"
                    + "_Please reply with 0._";
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

            sb.append("----------------\n");
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
        sb.append("----------------\n");
        sb.append("0. Main Menu\n\n");
        sb.append("_Please reply with 0._");
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

        // Prefer Vara-style pricing object first
        JsonNode pricing = productDetails.path("pricing");
        String originalStr = productDetails.path("price").asText(productDetails.path("mrp").asText("0"));
        String discountedStr = productDetails.path("discountedPrice")
                .asText(productDetails.path("salePrice").asText(productDetails.path("sellingPrice").asText("")));

        if (!pricing.isMissingNode()) {
            String pFinal = pricing.path("finalPrice").asText("");
            String pBase = pricing.path("basePrice").asText(pricing.path("mrp").asText("0"));
            if (!pFinal.isEmpty()) {
                discountedStr = pFinal;
                originalStr = pBase.isEmpty() ? pFinal : pBase;
            }
        }

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
            return bgsApiClient.get(path);
        } catch (Exception e) {
            log.warn("[OfferService] Error calling {}: {}", path, e.getMessage());
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
