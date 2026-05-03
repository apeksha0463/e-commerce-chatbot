package com.example.whatsapp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@CrossOrigin(origins = "*")
@RestController
public class WebhookController {

    @Autowired
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── Config from application.properties ───────────────────────────────────
    @Value("${bgs.base-url:https://be.bgsinfotech.com}")
    private String bgsBaseUrl;

    @Value("${bgs.tenant-id:697c756692a4f15176fefe8e}")
    private String bgsTenantId;

    @Value("${aisensy.api-key:577d0643178707e58a3b0}")
    private String aisensyApiKey;

    @Value("${aisensy.project-id:69da0b7a7dec1710f8a9db08}")
    private String aisensyProjectId;

    // ─── State constants ──────────────────────────────────────────────────────
    private static final String STATE_START = "START";
    private static final String STATE_MENU = "MENU";
    private static final String STATE_CATEGORIES = "CATEGORIES";
    private static final String STATE_SUBCATEGORIES = "SUBCATEGORIES";
    private static final String STATE_PRODUCTS = "PRODUCTS";
    private static final String STATE_PRODUCT_DETAILS = "PRODUCT_DETAILS";
    private static final String STATE_ORDER_NAME = "ORDER_NAME";
    private static final String STATE_ORDER_ADDRESS = "ORDER_ADDRESS";
    private static final String STATE_ORDER_PAYMENT = "ORDER_PAYMENT";
    private static final String STATE_ORDER_CONFIRM = "ORDER_CONFIRM";

    // ─── Intent constants ─────────────────────────────────────────────────────
    private enum Intent {
        BROWSE, OFFERS, BUY, NAVIGATION, UNKNOWN
    }

    // ─── In-memory state stores ───────────────────────────────────────────────
    private static final Map<String, String> userState = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> userData = new ConcurrentHashMap<>();
    private static final Set<String> completedUsers = ConcurrentHashMap.newKeySet();

    // ─── GET /messages/whatsapp — health check ────────────────────────────────
    @GetMapping("/messages/whatsapp")
    public String testWebhook() {
        return "Webhook is running ✅";
    }

    // ─── POST /messages/whatsapp — main handler ───────────────────────────────
    @PostMapping("/messages/whatsapp")
    public ResponseEntity<Map<String, String>> receiveMessage(@RequestBody JsonNode body) {
        System.out.println("=== Webhook HIT ===");
        System.out.println(body.toPrettyString());

        try {
            JsonNode msgNode = body.path("data").path("message");

            // Ignore outgoing bot messages
            String sender = msgNode.path("sender").asText("").trim().toUpperCase();
            if ("AGENT".equals(sender) || "API".equals(sender)) {
                System.out.println("[SKIP] Outgoing message ignored.");
                return ok("ignored - bot message");
            }

            String phone = msgNode.path("phone_number").asText("").trim();
            String rawText = msgNode.path("message_content").path("text").asText("").trim();

            // ── Input normalization: button payload OR text ───────────────
            String buttonPayload = msgNode.path("message_content").path("button_payload").asText("").trim();
            String text = buttonPayload.isEmpty() ? rawText : buttonPayload;

            System.out.println("[Phone]   : " + phone);
            System.out.println("[Message] : " + text);

            if (phone.isEmpty())
                return badRequest("Phone number missing");
            if (text.isEmpty())
                return ok("no text content");

            String state = userState.getOrDefault(phone, STATE_START);
            String reply;
            String next = state;

            // ── Normalized input for intent detection ────────────────────
            String input = text.toLowerCase().trim();
            Intent intent = detectIntent(input);

            // ── Global resets ────────────────────────────────────────────────
            if (intent == Intent.NAVIGATION) {
                reply = buildMenuMessage(phone);
                next = STATE_MENU;
                userData.remove(phone);

            } else {
                // ── State machine ────────────────────────────────────────────
                switch (state) {

                    case STATE_MENU: {
                        if (intent == Intent.BROWSE) {
                            reply = fetchCategories(phone);
                            next = reply.startsWith("⚠️") ? STATE_MENU : STATE_CATEGORIES;
                        } else if (intent == Intent.OFFERS) {
                            reply = fetchOffers();
                            next = STATE_MENU;
                        } else {
                            reply = smartFallback(state);
                        }
                        break;
                    }

                    case STATE_CATEGORIES: {
                        reply = handleCategorySelection(phone, text);
                        // next is set inside helper (SUBCATEGORIES or PRODUCTS)
                        next = userState.getOrDefault(phone, STATE_CATEGORIES);
                        break;
                    }

                    case STATE_SUBCATEGORIES: {
                        reply = handleSubCategorySelection(phone, text);
                        next = userState.getOrDefault(phone, STATE_SUBCATEGORIES);
                        break;
                    }

                    case STATE_PRODUCTS: {
                        reply = handleProductSelection(phone, text);
                        next = userState.getOrDefault(phone, STATE_PRODUCTS);
                        break;
                    }

                    case STATE_PRODUCT_DETAILS: {
                        if (intent == Intent.BUY) {
                            // Check stock before allowing buy
                            Boolean inStock = (Boolean) getUserData(phone).get("selectedInStock");
                            if (inStock != null && !inStock) {
                                reply = "❌ Sorry, this product is currently *out of stock*.\n\n"
                                        + "Try browsing other products — type *browse* or *back*.";
                            } else {
                                reply = "Great! Let's place your order. 🛒\n\nPlease enter your *Full Name*:";
                                next = STATE_ORDER_NAME;
                            }
                        } else {
                            reply = smartFallback(state);
                        }
                        break;
                    }

                    case STATE_ORDER_NAME: {
                        getUserData(phone).put("orderName", text);
                        reply = "✅ Got it!\n\nNow please enter your *Delivery Address*:";
                        next = STATE_ORDER_ADDRESS;
                        break;
                    }

                    case STATE_ORDER_ADDRESS: {
                        getUserData(phone).put("orderAddress", text);
                        reply = "📍 Address saved!\n\nChoose *Payment Method*:\n\n"
                                + "💵  *COD* — Cash on Delivery\n"
                                + "📱  *UPI* — UPI Payment\n"
                                + "💳  *Online* — Card / Net Banking\n\n"
                                + "_Type your preferred method (e.g. cod, upi, online)._";
                        next = STATE_ORDER_PAYMENT;
                        break;
                    }

                    case STATE_ORDER_PAYMENT: {
                        String method = resolvePaymentMethod(input);
                        if (method == null) {
                            reply = "Hmm, I didn't catch that. Please type *cod*, *upi*, or *online*.";
                        } else {
                            getUserData(phone).put("paymentMethod", method);
                            reply = buildOrderSummary(phone);
                            next = STATE_ORDER_CONFIRM;
                        }
                        break;
                    }

                    case STATE_ORDER_CONFIRM: {
                        if (input.contains("confirm")) {
                            reply = confirmOrder(phone);
                            next = STATE_MENU;
                        } else {
                            reply = "Type *confirm* to place the order or *back* to cancel.";
                        }
                        break;
                    }

                    default: {
                        reply = buildMenuMessage(phone);
                        next = STATE_MENU;
                        userData.remove(phone);
                    }
                }
            }

            userState.put(phone, next);
            System.out.println("[Reply] → " + reply);
            sendAiSensyReply(phone, reply);
            return ok("ok");

        } catch (Exception e) {
            System.err.println("[Error] " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BGS API HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════════

    /** Build shared headers for every BGS API call. */
    private HttpHeaders bgsHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Tenant-ID", bgsTenantId);
        return h;
    }

    /** GET any BGS endpoint and return the parsed JSON node. */
    private JsonNode bgsGet(String path) {
        String url = bgsBaseUrl + path;
        HttpEntity<Void> entity = new HttpEntity<>(bgsHeaders());
        try {
            ResponseEntity<JsonNode> resp = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            System.out.println("[BGS] GET " + path + " → " + resp.getStatusCode());
            return resp.getBody();
        } catch (Exception e) {
            System.err.println("[BGS] Error calling " + path + ": " + e.getMessage());
            return null;
        }
    }

    // ─── Fetch & format categories ────────────────────────────────────────────
    private String fetchCategories(String phone) {
        JsonNode data = bgsGet("/product/categories");
        if (data == null)
            return "⚠️ Service temporarily unavailable. Please try again later.";

        // Accept top-level array OR { data: [...] } / { categories: [...] }
        JsonNode list = data.isArray() ? data
                : (data.has("data") ? data.get("data")
                        : (data.has("categories") ? data.get("categories") : null));

        if (list == null || !list.isArray() || list.size() == 0) {
            return "No categories available right now.";
        }

        StringBuilder sb = new StringBuilder("📂 *Available Categories:*\n\n");
        List<Map<String, String>> cats = new ArrayList<>();
        int max = Math.min(list.size(), 5);

        for (int i = 0; i < max; i++) {
            JsonNode cat = list.get(i);
            String name = cat.path("name").asText("Category " + (i + 1));
            String id = cat.path("_id").asText(cat.path("id").asText(""));
            String slug = cat.path("seo").path("slug").asText(cat.path("slug").asText(""));
            sb.append("▸ ").append(name).append("\n");
            Map<String, String> m = new HashMap<>();
            m.put("id", id);
            m.put("slug", slug);
            m.put("name", name);
            cats.add(m);
        }
        sb.append("\n_Type a category name to explore._\n_Type *back* for main menu._");
        getUserData(phone).put("categories", cats.toString()); // store serialised

        // store structured list as JSON in userData
        try {
            getUserData(phone).put("categoriesJson", objectMapper.writeValueAsString(cats));
        } catch (Exception ignored) {
        }

        userState.put(phone, STATE_CATEGORIES);
        return sb.toString();
    }

    // ─── Handle category number selection ─────────────────────────────────────
    @SuppressWarnings("unchecked")
    private String handleCategorySelection(String phone, String text) {
        List<Map<String, String>> cats = getCatsFromState(phone, "categoriesJson");
        if (cats == null)
            return "Session expired. Type *hi* to start over.";

        Map<String, String> cat = matchByName(cats, text);
        if (cat == null)
            return "I couldn't find that category. Try typing a name from the list above, or type *back*.";

        // Try sub-categories
        JsonNode subData = bgsGet("/product/categories/" + cat.get("id") + "/children");
        JsonNode subList = resolveList(subData);

        if (subList != null && subList.size() > 0) {
            return buildSubCategoryResponse(phone, subList);
        }
        // No sub-cats → go to products
        return fetchProducts(phone, cat.get("slug"), cat.get("name"));
    }

    // ─── Fetch & format sub-categories ───────────────────────────────────────
    private String buildSubCategoryResponse(String phone, JsonNode list) {
        StringBuilder sb = new StringBuilder("📁 *Sub-categories:*\n\n");
        List<Map<String, String>> subs = new ArrayList<>();
        int max = Math.min(list.size(), 5);

        for (int i = 0; i < max; i++) {
            JsonNode s = list.get(i);
            String name = s.path("name").asText("Sub " + (i + 1));
            String id = s.path("_id").asText(s.path("id").asText(""));
            String slug = s.path("seo").path("slug").asText(s.path("slug").asText(""));
            sb.append("▸ ").append(name).append("\n");
            Map<String, String> m = new HashMap<>();
            m.put("id", id);
            m.put("slug", slug);
            m.put("name", name);
            subs.add(m);
        }
        sb.append("\n_Type a sub-category name to explore._\n_Type *back* for main menu._");

        try {
            getUserData(phone).put("subCatsJson", objectMapper.writeValueAsString(subs));
        } catch (Exception ignored) {
        }

        userState.put(phone, STATE_SUBCATEGORIES);
        return sb.toString();
    }

    // ─── Handle sub-category number selection ─────────────────────────────────
    private String handleSubCategorySelection(String phone, String text) {
        List<Map<String, String>> subs = getCatsFromState(phone, "subCatsJson");
        if (subs == null)
            return "Session expired. Type *hi* to start over.";

        Map<String, String> sub = matchByName(subs, text);
        if (sub == null)
            return "I couldn't find that sub-category. Try typing a name from the list above, or type *back*.";

        return fetchProducts(phone, sub.get("slug"), sub.get("name"));
    }

    // ─── Fetch & format product list ──────────────────────────────────────────
    private String fetchProducts(String phone, String slug, String categoryName) {
        JsonNode data = bgsGet("/product/items/category/slug/" + slug);
        JsonNode list = resolveList(data);

        if (list == null || list.size() == 0) {
            userState.put(phone, STATE_MENU);
            return "No products found in *" + categoryName + "*.\n_Type *back* to browse other categories._";
        }

        StringBuilder sb = new StringBuilder("🛍️ *Products in " + categoryName + ":*\n\n");
        List<Map<String, String>> prods = new ArrayList<>();
        int max = Math.min(list.size(), 5);

        for (int i = 0; i < max; i++) {
            JsonNode p = list.get(i);
            String name = p.path("name").asText("Product " + (i + 1));
            String id = p.path("_id").asText(p.path("id").asText(""));
            String price = p.path("price").asText(p.path("sellingPrice").asText("N/A"));
            boolean inSt = p.path("stock").asInt(0) > 0 || p.path("inStock").asBoolean(false);
            String thumb = p.path("thumbnails").isArray() && p.path("thumbnails").size() > 0
                    ? p.path("thumbnails").get(0).asText("")
                    : "";

            sb.append("*").append(name).append("*\n")
                    .append("   💰 ₹").append(price)
                    .append(" | ").append(inSt ? "✅ In Stock" : "❌ Out of Stock").append("\n\n");

            Map<String, String> m = new HashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("price", price);
            m.put("thumb", thumb);
            prods.add(m);
        }
        sb.append("_Type the product name to view details._\n_Type *back* for main menu._");

        try {
            getUserData(phone).put("productsJson", objectMapper.writeValueAsString(prods));
        } catch (Exception ignored) {
        }

        userState.put(phone, STATE_PRODUCTS);
        return sb.toString();
    }

    // ─── Handle product name-based selection ───────────────────────────────────
    private String handleProductSelection(String phone, String text) {
        List<Map<String, String>> prods = getCatsFromState(phone, "productsJson");
        if (prods == null)
            return "Session expired. Type *hi* to start over.";

        Map<String, String> prod = matchByName(prods, text);
        if (prod == null)
            return "I couldn't find that product. Try typing a name from the list above, or type *back*.";

        // Fetch full product details
        JsonNode details = bgsGet("/product/items/" + prod.get("id"));
        String name = details != null ? details.path("name").asText(prod.get("name")) : prod.get("name");
        String price = details != null
                ? details.path("price").asText(details.path("sellingPrice").asText(prod.get("price")))
                : prod.get("price");
        boolean inSt = details != null
                && (details.path("stock").asInt(0) > 0 || details.path("inStock").asBoolean(false));
        String rawDesc = details != null ? details.path("description").asText("No description.") : "";
        String desc = rawDesc.replaceAll("<[^>]*>", "");
        if (desc.length() > 250)
            desc = desc.substring(0, 250) + "...";

        // Handle variants (basic)
        String variantInfo = "";
        if (details != null && details.has("variants") && details.path("variants").isArray()
                && details.path("variants").size() > 0) {
            StringBuilder vb = new StringBuilder("\n🎨 *Variants available:*\n");
            for (JsonNode v : details.path("variants")) {
                vb.append("  ▸ ").append(v.path("name").asText(v.path("label").asText(""))).append("\n");
            }
            vb.append("_Type a variant name (e.g. size or color) to select._\n");
            variantInfo = vb.toString();
        }

        getUserData(phone).put("selectedName", name);
        getUserData(phone).put("selectedPrice", price);
        getUserData(phone).put("selectedInStock", inSt);
        userState.put(phone, STATE_PRODUCT_DETAILS);

        String stockLine = inSt ? "✅ In Stock" : "❌ Out of Stock";
        String buyPrompt = inSt
                ? "Type *buy* to order 🛒"
                : "⚠️ _This product is currently out of stock. Browse other products by typing *back*._";

        return "*" + name + "*\n\n"
                + "💰 Price : ₹" + price + "\n"
                + "📦 Stock : " + stockLine + "\n\n"
                + "📝 " + desc + "\n"
                + variantInfo + "\n"
                + buyPrompt + "\n"
                + "Type *back* to return.";
    }

    // ─── Fetch & format offers ────────────────────────────────────────────────
    private String fetchOffers() {
        JsonNode data = bgsGet("/offers/offers/type/PROMOTIONAL");
        JsonNode list = resolveList(data);

        if (list == null || list.size() == 0) {
            return "No active offers at the moment.\n_Type *hi* for main menu._";
        }

        StringBuilder sb = new StringBuilder("🎉 *Available Offers:*\n\n");
        int max = Math.min(list.size(), 5);
        for (int i = 0; i < max; i++) {
            JsonNode o = list.get(i);
            String code = o.path("code").asText(o.path("couponCode").asText("OFFER" + (i + 1)));
            sb.append(i + 1).append(". *").append(code).append("*\n");
            if ("PERCENTAGE".equals(o.path("discountType").asText())) {
                sb.append("   💸 ").append(o.path("discountValue").asText()).append("% off\n");
            } else if (!o.path("discountValue").isMissingNode()) {
                sb.append("   💸 ₹").append(o.path("discountValue").asText()).append(" off\n");
            }
            if (!o.path("description").isMissingNode()) {
                sb.append("   📌 ").append(o.path("description").asText()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("_Type *browse* to see categories or *back* for menu._");
        return sb.toString();
    }

    // ─── Build order summary ──────────────────────────────────────────────────
    private String buildOrderSummary(String phone) {
        Map<String, Object> d = getUserData(phone);
        String name = (String) d.getOrDefault("orderName", "N/A");
        String address = (String) d.getOrDefault("orderAddress", "N/A");
        String payment = (String) d.getOrDefault("paymentMethod", "N/A");
        String product = (String) d.getOrDefault("selectedName", "N/A");
        String price = (String) d.getOrDefault("selectedPrice", "N/A");

        return "📋 *Order Summary*\n\n"
                + "🛍️  Product : " + product + "\n"
                + "👤  Name    : " + name + "\n"
                + "📍  Address : " + address + "\n"
                + "💳  Payment : " + payment + "\n"
                + "💰  Total   : ₹" + price + "\n\n"
                + "Type *confirm* to place your order or *back* to cancel.";
    }

    // ─── Confirm order (demo mode) ────────────────────────────────────────────
    private String confirmOrder(String phone) {
        String orderId = "YOT-" + (100000 + new Random().nextInt(900000));
        String payment = (String) getUserData(phone).getOrDefault("paymentMethod", "COD");
        completedUsers.add(phone);
        userData.remove(phone);

        String msg = "🎉 *Order Placed Successfully!*\n\n"
                + "📦 Order ID : #" + orderId + "\n"
                + "Thank you for shopping with *YotMart*! 🛍️";
        if (!"COD".equals(payment)) {
            msg += "\n\n_Our team will share the payment link shortly._";
        }
        return msg;
    }

    // ─── Build welcome/menu message ───────────────────────────────────────────
    private String buildMenuMessage(String phone) {
        String prefix = !completedUsers.contains(phone)
                ? "🎁 *New User Offer!* Use code *WELCOME20* for 20% off!\n\n"
                : "";
        return prefix + "Welcome to *YotMart*! 🛍️\n\nWhat would you like to do?\n\n"
                + "🛒  *Browse* — View product categories\n"
                + "🎉  *Offers* — View deals & coupons\n\n"
                + "_You can type things like: *browse*, *show bags*, *view offers*, *laptop bags*_";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AiSensy REPLY
    // ══════════════════════════════════════════════════════════════════════════
    private void sendAiSensyReply(String phone, String text) {
        try {
            String url = "https://apis.aisensy.com/project-apis/v1/project/" + aisensyProjectId + "/messages";

            Map<String, Object> payload = new HashMap<>();
            payload.put("to", phone);
            payload.put("type", "text");
            payload.put("recipient_type", "individual");
            payload.put("text", Map.of("body", text));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-AiSensy-Project-API-Pwd", aisensyApiKey);

            ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, headers), JsonNode.class);
            System.out.println("[AiSensy] ✅ Status: " + resp.getStatusCode());

        } catch (Exception e) {
            System.err.println("[AiSensy] ❌ Failed: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UTILITY
    // ══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> getUserData(String phone) {
        return userData.computeIfAbsent(phone, k -> new ConcurrentHashMap<>());
    }

    /**
     * Resolve a JSON body that can be a bare array OR { data:[...] } OR {
     * offers:[...] } etc.
     */
    private JsonNode resolveList(JsonNode node) {
        if (node == null)
            return null;
        if (node.isArray())
            return node;
        for (String key : new String[] { "data", "items", "categories", "offers", "products" }) {
            if (node.has(key) && node.get(key).isArray())
                return node.get(key);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> getCatsFromState(String phone, String key) {
        Object raw = getUserData(phone).get(key);
        if (raw == null)
            return null;
        try {
            return objectMapper.readValue(raw.toString(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            return null;
        }
    }

    /** Match user input against a list of items by name (case-insensitive contains). */
    private Map<String, String> matchByName(List<Map<String, String>> items, String input) {
        if (items == null || input == null) return null;
        String lower = input.toLowerCase().trim();
        // Exact match first
        for (Map<String, String> item : items) {
            if (item.get("name").toLowerCase().equals(lower)) return item;
        }
        // Contains match
        for (Map<String, String> item : items) {
            if (item.get("name").toLowerCase().contains(lower)) return item;
        }
        // Reverse contains — input contains item name
        for (Map<String, String> item : items) {
            if (lower.contains(item.get("name").toLowerCase())) return item;
        }
        return null;
    }

    /** Detect user intent from normalized input. */
    private Intent detectIntent(String input) {
        if (input == null) return Intent.UNKNOWN;
        // Navigation
        if (List.of("hi", "hello", "hey", "start", "menu", "back", "home", "reset").contains(input))
            return Intent.NAVIGATION;
        // Offers
        if (input.contains("offer") || input.contains("discount") || input.contains("coupon")
                || input.contains("deal") || input.contains("promo"))
            return Intent.OFFERS;
        // Buy
        if (input.contains("buy") || input.contains("order") || input.contains("purchase")
                || input.contains("checkout"))
            return Intent.BUY;
        // Browse — broad category keywords
        if (input.contains("browse") || input.contains("categor") || input.contains("shop")
                || input.contains("show") || input.contains("view") || input.contains("explore")
                || input.contains("bag") || input.contains("backpack") || input.contains("laptop")
                || input.contains("college") || input.contains("school") || input.contains("product"))
            return Intent.BROWSE;
        return Intent.UNKNOWN;
    }

    /** Resolve payment method from user input text. */
    private String resolvePaymentMethod(String input) {
        if (input.contains("cod") || input.contains("cash")) return "COD";
        if (input.contains("upi")) return "UPI";
        if (input.contains("online") || input.contains("card") || input.contains("net")) return "Online/Card";
        return null;
    }

    /** Smart fallback: give contextual help instead of an error. */
    private String smartFallback(String state) {
        switch (state) {
            case STATE_MENU:
                return "I didn't quite get that. 🤔\n\n"
                        + "Try typing:\n"
                        + "▸ *browse* — to see product categories\n"
                        + "▸ *offers* — to view current deals\n"
                        + "▸ *back* — to return to the main menu";
            case STATE_CATEGORIES:
                return "Please type the *name* of a category from the list above.\n"
                        + "Or type *back* to return to the menu.";
            case STATE_SUBCATEGORIES:
                return "Please type the *name* of a sub-category from the list above.\n"
                        + "Or type *back* to return to the menu.";
            case STATE_PRODUCTS:
                return "Please type the *product name* from the list above to view details.\n"
                        + "Or type *back* to return to the menu.";
            case STATE_PRODUCT_DETAILS:
                return "You can:\n"
                        + "▸ Type *buy* to place an order\n"
                        + "▸ Type *back* to browse other products";
            default:
                return "I'm not sure what you mean. 🤔\n"
                        + "Type *hi* or *menu* to start fresh.";
        }
    }

    private ResponseEntity<Map<String, String>> ok(String status) {
        return ResponseEntity.ok(Map.of("status", status));
    }

    private ResponseEntity<Map<String, String>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("status", "error", "message", msg));
    }
}
