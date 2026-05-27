package com.example.whatsapp.controller;

import com.example.whatsapp.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.springframework.scheduling.annotation.Scheduled;

@CrossOrigin(origins = "*")
@RestController
public class WebhookController {

    private static final Logger log = Logger.getLogger(WebhookController.class.getName());

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OfferService offerService;



    @Autowired
    private ValidationService validationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Config from application.properties ---------------------------------
    @Value("${bgs.base-url:https://be.bgsinfotech.com}")
    private String bgsBaseUrl;

    @Value("${bgs.tenant-id:697c756692a4f15176fefe8e}")
    private String bgsTenantId;

    @Value("${aisensy.api-key:577d0643178707e58a3b0}")
    private String aisensyApiKey;

    @Value("${aisensy.project-id:69da0b7a7dec1710f8a9db08}")
    private String aisensyProjectId;

    // --- State constants ----------------------------------------------------
    private static final String STATE_START = "START";
    private static final String STATE_MENU = "MENU";
    private static final String STATE_CATEGORIES = "CATEGORIES";
    private static final String STATE_SUBCATEGORIES = "SUBCATEGORIES";
    private static final String STATE_PRODUCTS = "PRODUCTS";
    private static final String STATE_PRODUCT_DETAILS = "PRODUCT_DETAILS";

    // User Verification


    // Order Flow
    private static final String STATE_ORDER_NAME = "ORDER_NAME";
    private static final String STATE_ORDER_PINCODE = "ORDER_PINCODE";
    private static final String STATE_ORDER_ADDRESS = "ORDER_ADDRESS";
    private static final String STATE_ORDER_PAYMENT = "ORDER_PAYMENT";
    private static final String STATE_ORDER_CONFIRM = "ORDER_CONFIRM";

    // --- In-memory state stores ---------------------------------------------
    private static final Map<String, String> phoneTokenMap = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastInteractionTime = new ConcurrentHashMap<>();
    private static final Map<String, String> userState = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> userData = new ConcurrentHashMap<>();

    private String getOrCreateToken(String phone) {
        return phoneTokenMap.computeIfAbsent(phone, k -> UUID.randomUUID().toString());
    }

    @Scheduled(fixedDelay = 60000)
    public void checkIdleSessions() {
        long now = System.currentTimeMillis();
        long timeoutMs = TimeUnit.MINUTES.toMillis(10);

        for (Map.Entry<String, Long> entry : lastInteractionTime.entrySet()) {
            String token = entry.getKey();
            if (now - entry.getValue() > timeoutMs) {
                // Find phone for this token
                String phone = phoneTokenMap.entrySet().stream()
                        .filter(e -> e.getValue().equals(token))
                        .map(Map.Entry::getKey)
                        .findFirst().orElse(null);

                if (phone != null) {
                    sendAiSensyReply(phone, "⏳ Your session has timed out due to inactivity. Type *hi* to start over.");
                    phoneTokenMap.remove(phone);
                }

                userState.remove(token);
                userData.remove(token);

                lastInteractionTime.remove(token);
            }
        }
    }

    // --- GET /messages/whatsapp - health check ------------------------------
    @GetMapping("/messages/whatsapp")
    public String testWebhook() {
        return "Webhook is running";
    }

    // --- POST /messages/whatsapp - main handler -----------------------------
    @PostMapping("/messages/whatsapp")
    public ResponseEntity<Map<String, String>> receiveMessage(@RequestBody JsonNode body) {
        log.info("=== Webhook HIT ===");

        try {
            JsonNode msgNode = body.path("data").path("message");

            // Ignore outgoing bot messages
            String sender = msgNode.path("sender").asText("").trim().toUpperCase();
            if ("AGENT".equals(sender) || "API".equals(sender)) {
                return ok("ignored - bot message");
            }

            String phone = msgNode.path("phone_number").asText("").trim();
            String rawText = msgNode.path("message_content").path("text").asText("").trim();

            String buttonPayload = msgNode.path("message_content").path("button_payload").asText("").trim();
            String text = buttonPayload.isEmpty() ? rawText : buttonPayload;

            if (phone.isEmpty())
                return badRequest("Phone number missing");
            if (text.isEmpty())
                return ok("no text content");

            String token = getOrCreateToken(phone);
            lastInteractionTime.put(token, System.currentTimeMillis());

            String state = userState.getOrDefault(token, STATE_START);
            String reply;
            String next = state;

            String input = text.toLowerCase().trim();

            // --- Global resets ---
            if (input.equals("0") || input.equals("hi") || input.equals("hello") || input.equals("menu")) {
                reply = buildMenuMessage(token);
                next = STATE_MENU;
                userData.remove(token);
            } else {
                // --- State machine ---
                switch (state) {

                    case STATE_MENU: {
                        if (input.equals("1")) {
                            reply = fetchCategories(token);
                            next = reply.contains("unavailable") ? STATE_MENU : STATE_CATEGORIES;
                        } else {
                            reply = "Please reply with a valid number (1).";
                        }
                        break;
                    }

                    case STATE_CATEGORIES: {
                        if (input.equals("9")) {
                            reply = buildMenuMessage(token);
                            next = STATE_MENU;
                        } else {
                            reply = handleCategorySelection(token, text);
                            next = userState.getOrDefault(token, STATE_CATEGORIES);
                        }
                        break;
                    }

                    case STATE_SUBCATEGORIES: {
                        if (input.equals("9")) {
                            reply = fetchCategories(token);
                            next = STATE_CATEGORIES;
                        } else {
                            reply = handleSubCategorySelection(token, text);
                            next = userState.getOrDefault(token, STATE_SUBCATEGORIES);
                        }
                        break;
                    }

                    case STATE_PRODUCTS: {
                        if (input.equals("9")) {
                            String parentCatId = (String) getUserData(token).get("lastCategoryId");
                            if (parentCatId != null) {
                                JsonNode subData = bgsGet("/product/categories/" + parentCatId + "/children");
                                JsonNode subList = resolveList(subData);
                                if (subList != null && subList.size() > 0) {
                                    reply = buildSubCategoryResponse(token, subList);
                                    next = STATE_SUBCATEGORIES;
                                } else {
                                    reply = fetchCategories(token);
                                    next = STATE_CATEGORIES;
                                }
                            } else {
                                reply = fetchCategories(token);
                                next = STATE_CATEGORIES;
                            }
                        } else {
                            reply = handleProductSelection(token, text);
                            next = userState.getOrDefault(token, STATE_PRODUCTS);
                        }
                        break;
                    }

                    case STATE_PRODUCT_DETAILS: {
                        if (input.equals("9")) {
                            String lastSlug = (String) getUserData(token).get("lastSlug");
                            String lastName = (String) getUserData(token).get("lastCategoryName");
                            reply = fetchProducts(token, lastSlug, lastName);
                            next = STATE_PRODUCTS;
                        } else if (input.equals("1")) {
                            // Live stock re-check
                            String prodId = (String) getUserData(token).get("selectedId");
                            JsonNode details = bgsGet("/product/items/" + prodId);
                            boolean inStock = isProductInStock(details);

                            if (!inStock) {
                                reply = "Sorry, this product is currently *out of stock*.\n\n"
                                        + "Type 9 for Previous Menu or 0 for Main Menu.";
                            } else {
                                reply = "Great! Let's place your order.\n\nPlease enter your *Full Name*:";
                                next = STATE_ORDER_NAME;
                            }
                        } else {
                            reply = "Please type 1 to Buy, 9 for Previous Menu, or 0 for Main Menu.";
                        }
                        break;
                    }

                    case STATE_ORDER_NAME: {
                        if (input.equals("9")) {
                            String lastSlug = (String) getUserData(token).get("lastSlug");
                            String lastName = (String) getUserData(token).get("lastCategoryName");
                            reply = fetchProducts(token, lastSlug, lastName);
                            next = STATE_PRODUCTS;
                        } else {
                            getUserData(token).put("orderName", text);
                            reply = "Got it!\n\nPlease enter your *6-digit Delivery Pincode*:";
                            next = STATE_ORDER_PINCODE;
                        }
                        break;
                    }

                    case STATE_ORDER_PINCODE: {
                        if (input.equals("9")) {
                            reply = "Please enter your *Full Name*:";
                            next = STATE_ORDER_NAME;
                        } else if (!validationService.isPincodeValid(input)) {
                            reply = validationService.pincodeErrorMessage();
                        } else if (!validationService.isServiceable(input)) {
                            reply = validationService.serviceabilityErrorMessage(input);
                        } else {
                            getUserData(token).put("orderPincode", input);
                            reply = "Pincode serviceable!\n\nNow please enter your *Full Delivery Address* (including house number, street, etc.):";
                            next = STATE_ORDER_ADDRESS;
                        }
                        break;
                    }

                    case STATE_ORDER_ADDRESS: {
                        if (input.equals("9")) {
                            reply = "Please enter your *6-digit Delivery Pincode*:";
                            next = STATE_ORDER_PINCODE;
                        } else if (!validationService.isAddressValid(text)) {
                            reply = validationService.addressErrorMessage();
                        } else {
                            getUserData(token).put("orderAddress", text);
                            reply = "Address saved!\n\nChoose *Payment Method*:\n\n"
                                    + "1. COD (Cash on Delivery)\n"
                                    + "2. UPI Payment\n"
                                    + "3. Online (Card / Net Banking)\n\n"
                                    + "_Please reply with 1, 2, or 3._";
                            next = STATE_ORDER_PAYMENT;
                        }
                        break;
                    }

                    case STATE_ORDER_PAYMENT: {
                        if (input.equals("9")) {
                            reply = "Now please enter your *Full Delivery Address* (including house number, street, etc.):";
                            next = STATE_ORDER_ADDRESS;
                        } else {
                            String method = resolvePaymentMethod(input);
                            if (method == null) {
                                reply = "Please reply with a valid number (1, 2, or 3).";
                            } else {
                                getUserData(token).put("paymentMethod", method);
                                reply = buildOrderSummary(token);
                                next = STATE_ORDER_CONFIRM;
                            }
                        }
                        break;
                    }

                    case STATE_ORDER_CONFIRM: {
                        if (input.equals("9")) {
                            reply = "Choose *Payment Method*:\n\n"
                                    + "1. COD (Cash on Delivery)\n"
                                    + "2. UPI Payment\n"
                                    + "3. Online (Card / Net Banking)\n\n"
                                    + "_Please reply with 1, 2, or 3._";
                            next = STATE_ORDER_PAYMENT;
                        } else if (input.equals("1")) {
                            reply = processFinalOrder(phone, token);
                            next = reply.contains("could not be generated") ? STATE_ORDER_PAYMENT : STATE_MENU;
                        } else {
                            reply = "Please type 1 to confirm the order, 9 to go back, or 0 to cancel and return to main menu.";
                        }
                        break;
                    }

                    default: {
                        reply = buildMenuMessage(token);
                        next = STATE_MENU;
                        userData.remove(token);
                    }
                }
            }

            userState.put(token, next);
            sendAiSensyReply(phone, reply);
            return ok("ok");

        } catch (Exception e) {
            log.severe("[Error] " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ==========================================================================
    // ORDER PLACEMENT LOGIC
    // ==========================================================================

    private String processFinalOrder(String phone, String token) {
        Map<String, Object> d = getUserData(token);
        String prodId = (String) d.get("selectedId");
        String prodName = (String) d.get("selectedName");
        String finalPrice = (String) d.get("selectedPrice");
        String name = (String) d.get("orderName");
        String address = (String) d.get("orderAddress");
        String pincode = (String) d.get("orderPincode");
        String payment = (String) d.get("paymentMethod");

        // 1. Re‑validate Stock (CRITICAL)
        JsonNode details = bgsGet("/product/items/" + prodId);
        if (!isProductInStock(details)) {
            return "We're sorry, but this product just went *out of stock*.\nYour order could not be placed. Type 0 for Main Menu to view other products.";
        }

        // 2. Create Order in Backend
        OrderService.OrderResult orderRes = orderService.createOrder(
                phone, prodId, prodName, finalPrice, name, address, pincode, payment);

        if (!orderRes.success) {
            log.severe("Order creation failed for " + phone + ": " + orderRes.errorMessage);
            return "Oops! Something went wrong while creating your order. Please try again later or contact support.";
        }

        String orderId = orderRes.orderId;
        StringBuilder msg = new StringBuilder();
        msg.append("*Order Placed Successfully!*\n\n")
                .append("Order ID : #").append(orderId).append("\n")
                .append("Thank you for shopping with *YotMart*!\n");

        // 3. Generate Payment Link if not COD
        if (!"COD".equals(payment)) {
            PaymentService.PaymentResult payRes = paymentService.generatePaymentLink(
                    orderId, phone, finalPrice, payment);

            if (payRes.success) {
                msg.append("\n\n*Please complete your payment here:*\n").append(payRes.paymentLink);
            } else {
                // Payment link generation failed – keep user in payment step to re‑choose
                log.warning("Payment link generation failed for order " + orderId + ": " + payRes.errorMessage);
                // Reset state so the user can pick another method
                userState.put(token, STATE_ORDER_PAYMENT);
                return "Order created, but *Payment link could not be generated*: " + payRes.errorMessage
                        + "\n\nPlease reply with:\n1. COD (Cash on Delivery)\n2. UPI Payment\n3. Online (Card / Net Banking)\n\n_Enter 1, 2, or 3._";
            }
        }

        // Cleanup session data
        userData.remove(token);
        return msg.toString();
    }

    // ==========================================================================
    // BGS API HELPER METHODS
    // ==========================================================================

    private HttpHeaders bgsHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Tenant-ID", bgsTenantId);
        return h;
    }

    private JsonNode bgsGet(String path) {
        String url = bgsBaseUrl + path;
        HttpEntity<Void> entity = new HttpEntity<>(bgsHeaders());
        try {
            ResponseEntity<JsonNode> resp = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            return resp.getBody();
        } catch (Exception e) {
            log.warning("[BGS] Error calling " + path + ": " + e.getMessage());
            return null;
        }
    }

    // --- Fetch & format categories ------------------------------------------
    private void clearUserData(String token) {
        userData.remove(token);
        userState.remove(token);
    }

    // --- Fetch & format categories ------------------------------------------
    private String fetchCategories(String token) {
        JsonNode data = bgsGet("/product/categories");
        if (data == null)
            return "Service temporarily unavailable. Please try again later.";

        JsonNode list = resolveList(data);
        if (list == null || !list.isArray() || list.size() == 0) {
            return "No categories available right now.";
        }

        StringBuilder sb = new StringBuilder("*Available Categories:*\n\n");
        List<Map<String, String>> cats = new ArrayList<>();
        int max = Math.min(list.size(), 8);

        for (int i = 0; i < max; i++) {
            JsonNode cat = list.get(i);
            String name = cat.path("name").asText("Category " + (i + 1));
            String id = cat.path("_id").asText(cat.path("id").asText(""));
            String slug = cat.path("seo").path("slug").asText(cat.path("slug").asText(""));
            sb.append((i + 1)).append(". ").append(name).append("\n");
            cats.add(Map.of("index", String.valueOf(i + 1), "id", id, "slug", slug, "name", name));
        }
        sb.append("\n9. Previous Menu\n0. Main Menu\n\n_Please reply with a number._");

        try {
            getUserData(token).put("categoriesJson", objectMapper.writeValueAsString(cats));
        } catch (Exception ignored) {
        }
        userState.put(token, STATE_CATEGORIES);
        return sb.toString();
    }

    // --- Handle category selection -----------------------------------------
    private String handleCategorySelection(String token, String text) {
        List<Map<String, String>> cats = getListFromState(token, "categoriesJson");
        if (cats == null)
            return "Session expired. Type 0 to start over.";

        Map<String, String> cat = matchByIndex(cats, text);
        if (cat == null)
            return "Please reply with a valid number from the list above.";

        getUserData(token).put("lastCategoryId", cat.get("id"));

        JsonNode subData = bgsGet("/product/categories/" + cat.get("id") + "/children");
        JsonNode subList = resolveList(subData);

        if (subList != null && subList.size() > 0) {
            return buildSubCategoryResponse(token, subList);
        }
        return fetchProducts(token, cat.get("slug"), cat.get("name"));
    }

    // --- Fetch & format sub-categories --------------------------------------
    private String buildSubCategoryResponse(String token, JsonNode list) {
        StringBuilder sb = new StringBuilder("*Sub-categories:*\n\n");
        List<Map<String, String>> subs = new ArrayList<>();
        int max = Math.min(list.size(), 8);

        for (int i = 0; i < max; i++) {
            JsonNode s = list.get(i);
            String name = s.path("name").asText("Sub " + (i + 1));
            String id = s.path("_id").asText(s.path("id").asText(""));
            String slug = s.path("seo").path("slug").asText(s.path("slug").asText(""));
            sb.append((i + 1)).append(". ").append(name).append("\n");
            subs.add(Map.of("index", String.valueOf(i + 1), "id", id, "slug", slug, "name", name));
        }
        sb.append("\n9. Previous Menu\n0. Main Menu\n\n_Please reply with a number._");

        try {
            getUserData(token).put("subCatsJson", objectMapper.writeValueAsString(subs));
        } catch (Exception ignored) {
        }
        userState.put(token, STATE_SUBCATEGORIES);
        return sb.toString();
    }

    // --- Handle sub-category selection ---------------------------------------
    private String handleSubCategorySelection(String token, String text) {
        List<Map<String, String>> subs = getListFromState(token, "subCatsJson");
        if (subs == null)
            return "Session expired. Type 0 to start over.";

        Map<String, String> sub = matchByIndex(subs, text);
        if (sub == null)
            return "Please reply with a valid number from the list above.";

        return fetchProducts(token, sub.get("slug"), sub.get("name"));
    }

    // --- Fetch & format product list -----------------------------------------
    private String fetchProducts(String token, String slug, String categoryName) {
        getUserData(token).put("lastSlug", slug);
        getUserData(token).put("lastCategoryName", categoryName);

        JsonNode data = bgsGet("/product/items/category/slug/" + slug);
        JsonNode list = resolveList(data);

        if (list == null || list.size() == 0) {
            return "No products found in *" + categoryName + "*.\n\n9. Previous Menu\n0. Main Menu";
        }

        StringBuilder sb = new StringBuilder("*Products in " + categoryName + ":*\n\n");
        List<Map<String, String>> prods = new ArrayList<>();
        int max = Math.min(list.size(), 8);

        for (int i = 0; i < max; i++) {
            JsonNode p = list.get(i);
            String name = p.path("name").asText("Product " + (i + 1));
            String id = p.path("_id").asText(p.path("id").asText(""));
            boolean inSt = isProductInStock(p);
            int stockAmt = p.path("totalStock").asInt(p.path("stock").asInt(p.path("quantity").asInt(0)));

            OfferService.DiscountResult discount = offerService.applyBestOffer(p);

            String stockLine = inSt
                    ? (stockAmt > 0 && stockAmt <= 5 ? "In Stock (Only " + stockAmt + " left!)" : "In Stock")
                    : "Out of Stock";

            sb.append((i + 1)).append(". *").append(name).append("*\n")
                    .append("   ").append(discount.toWhatsAppLine()).append("\n")
                    .append("   Stock: ").append(stockLine).append("\n\n");

            prods.add(Map.of("index", String.valueOf(i + 1), "id", id, "name", name));
        }
        sb.append("9. Previous Menu\n0. Main Menu\n\n_Please reply with a number to view product details._");

        try {
            getUserData(token).put("productsJson", objectMapper.writeValueAsString(prods));
        } catch (Exception ignored) {
        }
        userState.put(token, STATE_PRODUCTS);
        return sb.toString();
    }

    // --- Handle product selection ---------------------------------------------
    private String handleProductSelection(String token, String text) {
        List<Map<String, String>> prods = getListFromState(token, "productsJson");
        if (prods == null)
            return "Session expired. Type 0 to start over.";

        Map<String, String> prod = matchByIndex(prods, text);
        if (prod == null)
            return "Please reply with a valid number from the list above.";

        JsonNode details = bgsGet("/product/items/" + prod.get("id"));
        if (details == null)
            return "Could not fetch product details. Please try again.";

        String name = details.path("name").asText(prod.get("name"));
        boolean inSt = isProductInStock(details);
        int stockAmt = details.path("totalStock").asInt(details.path("stock").asInt(details.path("quantity").asInt(0)));
        OfferService.DiscountResult discount = offerService.applyBestOffer(details);

        String desc = details.path("description").asText("").replaceAll("<[^>]*>", "");
        if (desc.length() > 250)
            desc = desc.substring(0, 250) + "...";

        getUserData(token).put("selectedId", prod.get("id"));
        getUserData(token).put("selectedName", name);
        getUserData(token).put("selectedPrice", discount.finalPrice);
        userState.put(token, STATE_PRODUCT_DETAILS);

        String stockLine = inSt
                ? (stockAmt > 0 && stockAmt <= 5 ? "In Stock (Only " + stockAmt + " left!)" : "In Stock")
                : "Out of Stock";
        String buyPrompt = inSt ? "1. Buy Now\n" : "_This product is currently out of stock._\n";

        return "*" + name + "*\n\n"
                + discount.toWhatsAppLine() + "\n"
                + "Stock: " + stockLine + "\n\n"
                + desc + "\n\n"
                + buyPrompt
                + "9. Previous Menu\n"
                + "0. Main Menu\n\n"
                + "_Please reply with a number._";
    }

    // --- Build order summary --------------------------------------------------
    private String buildOrderSummary(String token) {
        Map<String, Object> d = getUserData(token);
        String name = (String) d.getOrDefault("orderName", "N/A");
        String address = (String) d.getOrDefault("orderAddress", "N/A");
        String pincode = (String) d.getOrDefault("orderPincode", "N/A");
        String payment = (String) d.getOrDefault("paymentMethod", "N/A");
        String product = (String) d.getOrDefault("selectedName", "N/A");
        String price = (String) d.getOrDefault("selectedPrice", "N/A");

        return "*Order Summary*\n\n"
                + "Product: " + product + "\n"
                + "Name: " + name + "\n"
                + "Address: " + address + ", " + pincode + "\n"
                + "Payment: " + payment + "\n"
                + "Total: ₹" + price + "\n\n"
                + "1. Confirm Order\n"
                + "9. Previous Step\n"
                + "0. Cancel & Main Menu\n\n"
                + "_Please reply with 1, 9, or 0._";
    }

    // --- Build welcome/menu message -------------------------------------------
    private String buildMenuMessage(String token) {
        return "Welcome to *YotMart*!\n\nWhat would you like to do?\n\n"
                + "1. Browse Products\n\n"
                + "_Please reply with 1._";
    }

    // ==========================================================================
    // AiSensy REPLY
    // ==========================================================================
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

            restTemplate.postForEntity(url, new HttpEntity<>(payload, headers), JsonNode.class);
        } catch (Exception e) {
            log.warning("[AiSensy] Failed to send reply: " + e.getMessage());
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private Map<String, Object> getUserData(String token) {
        return userData.computeIfAbsent(token, k -> new ConcurrentHashMap<>());
    }

    private boolean isProductInStock(JsonNode p) {
        if (p == null)
            return false;
        // TotalStock from BGS API is the source of truth
        return p.path("totalStock").asInt(0) > 0 || p.path("stock").asInt(0) > 0 || p.path("inStock").asBoolean(false)
                || p.path("quantity").asInt(0) > 0;
    }

    private JsonNode resolveList(JsonNode node) {
        if (node == null)
            return null;
        if (node.isArray())
            return node;
        // Check for paginated content array specifically
        for (String key : new String[] { "content", "data", "items", "categories", "offers", "products" }) {
            if (node.has(key) && node.get(key).isArray())
                return node.get(key);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> getListFromState(String token, String key) {
        Object raw = getUserData(token).get(key);
        if (raw == null)
            return null;
        try {
            return objectMapper.readValue(raw.toString(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> matchByIndex(List<Map<String, String>> items, String input) {
        if (items == null || input == null)
            return null;
        for (Map<String, String> item : items) {
            if (input.equals(item.get("index")))
                return item;
        }
        return null;
    }

    private String resolvePaymentMethod(String input) {
        if (input.equals("1"))
            return "COD";
        if (input.equals("2"))
            return "UPI";
        if (input.equals("3"))
            return "ONLINE";
        return null;
    }

    private ResponseEntity<Map<String, String>> ok(String status) {
        return ResponseEntity.ok(Map.of("status", status));
    }

    private ResponseEntity<Map<String, String>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("status", "error", "message", msg));
    }
}
