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
            String text = msgNode.path("message_content").path("text").asText("").trim();

            System.out.println("[Phone]   : " + phone);
            System.out.println("[Message] : " + text);

            if (phone.isEmpty())
                return badRequest("Phone number missing");
            if (text.isEmpty())
                return ok("no text content");

            String state = userState.getOrDefault(phone, STATE_START);
            String reply;
            String next = state;

            // ── Global resets ────────────────────────────────────────────────
            String textLower = text.toLowerCase().trim();
            if (List.of("hi", "hello", "start", "menu", "back").contains(textLower)) {
                reply = buildMenuMessage(phone);
                next = STATE_MENU;
                userData.remove(phone);

            } else {
                // ── State machine ────────────────────────────────────────────
                switch (state) {

                    case STATE_MENU: {
                        if ("1".equals(text)) {
                            reply = fetchCategories(phone);
                            next = reply.startsWith("⚠️") ? STATE_MENU : STATE_CATEGORIES;
                        } else if ("2".equals(text)) {
                            reply = fetchOffers();
                            next = STATE_MENU;
                        } else {
                            reply = "Please reply with *1* to browse categories or *2* for offers.";
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
                        if ("1".equals(text)) {
                            reply = "Great! Let's place your order. 🛒\n\nPlease enter your *Full Name*:";
                            next = STATE_ORDER_NAME;
                        } else {
                            reply = "Reply *1* to Buy Now or type *back* to return.";
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
                                + "1️⃣  COD (Cash on Delivery)\n"
                                + "2️⃣  UPI\n"
                                + "3️⃣  Online / Card\n\n"
                                + "_Reply with 1, 2, or 3._";
                        next = STATE_ORDER_PAYMENT;
                        break;
                    }

                    case STATE_ORDER_PAYMENT: {
                        Map<String, String> methods = new HashMap<>();
                        methods.put("1", "COD");
                        methods.put("cod", "COD");
                        methods.put("2", "UPI");
                        methods.put("upi", "UPI");
                        methods.put("3", "Online/Card");
                        methods.put("online", "Online/Card");

                        String method = methods.get(textLower);
                        if (method == null) {
                            reply = "Please reply with *1*, *2*, or *3* to choose payment.";
                        } else {
                            getUserData(phone).put("paymentMethod", method);
                            reply = buildOrderSummary(phone);
                            next = STATE_ORDER_CONFIRM;
                        }
                        break;
                    }

                    case STATE_ORDER_CONFIRM: {
                        if (textLower.contains("confirm")) {
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
            sb.append(i + 1).append(". ").append(name).append("\n");
            Map<String, String> m = new HashMap<>();
            m.put("idx", String.valueOf(i + 1));
            m.put("id", id);
            m.put("slug", slug);
            m.put("name", name);
            cats.add(m);
        }
        sb.append("\n_Reply with a number to explore._\n_Type *back* for main menu._");
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
        int num;
        try {
            num = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return "Please reply with the *number* of the category.";
        }

        List<Map<String, String>> cats = getCatsFromState(phone, "categoriesJson");
        if (cats == null)
            return "Session expired. Type *hi* to start over.";

        Map<String, String> cat = cats.stream()
                .filter(c -> c.get("idx").equals(String.valueOf(num)))
                .findFirst().orElse(null);
        if (cat == null)
            return "Invalid choice. Reply with a number from the list.";

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
            sb.append(i + 1).append(". ").append(name).append("\n");
            Map<String, String> m = new HashMap<>();
            m.put("idx", String.valueOf(i + 1));
            m.put("id", id);
            m.put("slug", slug);
            m.put("name", name);
            subs.add(m);
        }
        sb.append("\n_Reply with a number._\n_Type *back* for main menu._");

        try {
            getUserData(phone).put("subCatsJson", objectMapper.writeValueAsString(subs));
        } catch (Exception ignored) {
        }

        userState.put(phone, STATE_SUBCATEGORIES);
        return sb.toString();
    }

    // ─── Handle sub-category number selection ─────────────────────────────────
    private String handleSubCategorySelection(String phone, String text) {
        int num;
        try {
            num = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return "Please reply with the *number* shown.";
        }

        List<Map<String, String>> subs = getCatsFromState(phone, "subCatsJson");
        if (subs == null)
            return "Session expired. Type *hi* to start over.";

        Map<String, String> sub = subs.stream()
                .filter(s -> s.get("idx").equals(String.valueOf(num)))
                .findFirst().orElse(null);
        if (sub == null)
            return "Invalid choice. Reply with a number from the list.";

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

            sb.append(i + 1).append(". *").append(name).append("* — ₹").append(price)
                    .append(" | ").append(inSt ? "✅ In Stock" : "❌ Out of Stock").append("\n");

            Map<String, String> m = new HashMap<>();
            m.put("idx", String.valueOf(i + 1));
            m.put("id", id);
            m.put("name", name);
            m.put("price", price);
            m.put("thumb", thumb);
            prods.add(m);
        }
        sb.append("\n_Reply with a number to see details._\n_Type *back* for main menu._");

        try {
            getUserData(phone).put("productsJson", objectMapper.writeValueAsString(prods));
        } catch (Exception ignored) {
        }

        userState.put(phone, STATE_PRODUCTS);
        return sb.toString();
    }

    // ─── Handle product number selection ──────────────────────────────────────
    private String handleProductSelection(String phone, String text) {
        int num;
        try {
            num = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return "Please reply with the *number* shown.";
        }

        List<Map<String, String>> prods = getCatsFromState(phone, "productsJson");
        if (prods == null)
            return "Session expired. Type *hi* to start over.";

        Map<String, String> prod = prods.stream()
                .filter(p -> p.get("idx").equals(String.valueOf(num)))
                .findFirst().orElse(null);
        if (prod == null)
            return "Invalid choice. Reply with a number from the list.";

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

        getUserData(phone).put("selectedName", name);
        getUserData(phone).put("selectedPrice", price);
        userState.put(phone, STATE_PRODUCT_DETAILS);

        return "*" + name + "*\n\n"
                + "💰 Price : ₹" + price + "\n"
                + "📦 Stock : " + (inSt ? "✅ In Stock" : "❌ Out of Stock") + "\n\n"
                + "📝 " + desc + "\n\n"
                + "Reply *1* to *Buy Now* 🛒\n"
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
        sb.append("_Type *1* to browse categories or *back* for menu._");
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
                + "1️⃣  Browse Categories\n"
                + "2️⃣  View Offers / Coupons\n\n"
                + "_Reply with a number._";
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

    private ResponseEntity<Map<String, String>> ok(String status) {
        return ResponseEntity.ok(Map.of("status", status));
    }

    private ResponseEntity<Map<String, String>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("status", "error", "message", msg));
    }
}
