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
 *
 * NOTE: fetchActiveOffersMessage() is defined for a future "Offers" menu option
 * and is not currently wired to any active menu flow.
 */
@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    @Autowired
    private BgsApiClient bgsApiClient;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches active offers and formats them for WhatsApp display.
     * Endpoint: GET /offers/offers/type/PRODUCT
     */
    public String fetchActiveOffersMessage() {
        JsonNode data = bgsGet("/offers/offers/type/PRODUCT");
        JsonNode list = resolveList(data);

        if (list == null || list.size() == 0) {
            data = bgsGet("/offers/offers/type/CATEGORY");
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
            
            // Parse OfferModel fields strictly according to documentation
            String id = o.path("id").asText(o.path("_id").asText(""));
            String type = o.path("type").asText("PRODUCT");
            String name = o.path("name").asText(o.path("title").asText(""));
            String couponCode = o.path("couponCode").asText(o.path("code").asText("OFFER" + (i + 1)));
            String discountType = o.path("discountType").asText("PERCENTAGE");
            String discountValue = o.path("discountValue").asText("");
            String maxDiscount = o.path("maxDiscount").asText("");
            String description = o.path("description").asText("");
            boolean active = o.path("active").asBoolean(true);
            boolean autoApplied = o.path("autoApplied").asBoolean(false);

            if (!active) continue;

            sb.append("----------------\n");
            if (!name.isEmpty()) sb.append("*").append(name).append("*\n");
            sb.append("Code: *").append(couponCode).append("*\n");

            if ("PERCENTAGE".equalsIgnoreCase(discountType)) {
                sb.append("*").append(discountValue).append("% OFF*\n");
                if (!maxDiscount.isEmpty()) sb.append("Up to ₹").append(maxDiscount).append("\n");
            } else {
                sb.append("*₹").append(discountValue).append(" OFF*\n");
            }
            
            if (autoApplied) sb.append("_Auto-applied at checkout_\n");
            if (!description.isEmpty()) sb.append("Note: ").append(description).append("\n");
            sb.append("\n");
        }
        sb.append("----------------\n");
        sb.append("0. Main Menu\n\n");
        sb.append("_Please reply with 0._");
        return sb.toString();
    }

    /**
     * Applies the best applicable offer/discount to a product.
     * Checks product-level discounted/sale price vs. original price.
     *
     * @param productDetails the full product JSON node from BGS
     * @return DiscountResult with final price and optional offer description
     */
    public DiscountResult applyBestOffer(JsonNode productDetails) {
        if (productDetails == null) {
            return DiscountResult.noDiscount("0");
        }

        // Base price: try price → mrp → "0"
        String originalStr = productDetails.path("price")
                .asText(productDetails.path("mrp").asText("0"));

        // Discounted price: try discountedPrice → salePrice → sellingPrice
        String discountedStr = productDetails.path("discountedPrice")
                .asText(productDetails.path("salePrice")
                        .asText(productDetails.path("sellingPrice").asText("")));

        // Override with Vara-style pricing object if present and valid (finalPrice > 0)
        JsonNode pricing = productDetails.path("pricing");
        if (!pricing.isMissingNode()) {
            String pFinal = pricing.path("finalPrice").asText("").trim();
            String pBase  = pricing.path("basePrice").asText(pricing.path("mrp").asText("")).trim();

            double pFinalVal = parsePrice(pFinal);
            if (pFinalVal > 0) {
                discountedStr = pFinal;
                originalStr   = (!pBase.isEmpty() && parsePrice(pBase) > 0) ? pBase : pFinal;
            }
        }

        double original   = parsePrice(originalStr);
        double discounted = discountedStr.isEmpty() ? original : parsePrice(discountedStr);

        // Inline product offer label (e.g. "10% off", "Sale")
        String offerLabel = productDetails.path("offerLabel")
                .asText(productDetails.path("badge").asText(""));

        if (discounted > 0 && discounted < original) {
            int pct = (int) Math.round((original - discounted) / original * 100);
            String offerText = offerLabel.isEmpty() ? pct + "% off" : offerLabel;
            return new DiscountResult(fmt(original), fmt(discounted), offerText, true);
        }

        // No discount — return original price
        String displayPrice = (original > 0) ? fmt(original) : (originalStr.isEmpty() ? "0" : originalStr);
        return DiscountResult.noDiscount(displayPrice);
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
        for (String key : new String[]{"data", "offers", "items", "results", "list"}) {
            if (node.has(key) && node.get(key).isArray()) return node.get(key);
        }
        return null;
    }

    private double parsePrice(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Double.parseDouble(s.replaceAll("[^\\d.]", ""));
        } catch (Exception e) {
            log.debug("[OfferService] Could not parse price value '{}': {}", s, e.getMessage());
            return 0;
        }
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
