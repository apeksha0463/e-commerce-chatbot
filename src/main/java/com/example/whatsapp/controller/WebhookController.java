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
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.whatsapp.config.AppProperties;
import com.example.whatsapp.client.BgsApiClient;

@CrossOrigin(origins = "*")
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private BgsApiClient bgsApiClient;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OfferService offerService;

    @Autowired
    private AuthService authService;

    @Autowired
    private ValidationService validationService;

    @Value("${app.bot.api-secret:}")
    private String botApiSecret;

    /**
     * Set app.carousel.debug=false in application.properties to suppress verbose
     * carousel payload / response logs in production.
     */
    @Value("${app.carousel.debug:false}")
    private boolean carouselDebug;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- State constants ----------------------------------------------------
    private static final String STATE_START          = "START";
    private static final String STATE_MENU           = "MENU";
    private static final String STATE_CATEGORIES     = "CATEGORIES";
    private static final String STATE_SUBCATEGORIES  = "SUBCATEGORIES";
    private static final String STATE_PRODUCTS       = "PRODUCTS";
    private static final String STATE_PRODUCT_DETAILS = "PRODUCT_DETAILS";
    private static final String STATE_ORDER_NAME     = "ORDER_NAME";
    private static final String STATE_ORDER_PINCODE  = "ORDER_PINCODE";
    private static final String STATE_ORDER_ADDRESS  = "ORDER_ADDRESS";
    private static final String STATE_ORDER_PAYMENT  = "ORDER_PAYMENT";
    private static final String STATE_ORDER_CONFIRM  = "ORDER_CONFIRM";
    private static final String STATE_AWAITING_OTP   = "AWAITING_OTP";

    /** Sentinel returned by fetchCategories/fetchProducts when carousel was sent successfully. */
    private static final String CAROUSEL_SENT = "__CAROUSEL_SENT__";

    /**
     * Keys preserved in userData on global reset (0/hi/menu).
     * Everything else is cleared. authToken MUST be preserved to avoid re-auth on every menu visit.
     */
    private static final Set<String> PRESERVED_KEYS_ON_RESET =
            Set.of("authToken", "refreshToken");

    // --- In-memory state stores ---------------------------------------------
    private static final Map<String, String>              phoneTokenMap       = new ConcurrentHashMap<>();
    private static final Map<String, Long>                lastInteractionTime = new ConcurrentHashMap<>();
    private static final Map<String, String>              userState           = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> userData            = new ConcurrentHashMap<>();

    /** Tokens pushed from external signup (Key: Phone, Value: BGS Auth Token). */
    private static final Map<String, String> bgsUserTokens = new ConcurrentHashMap<>();

    private String getOrCreateToken(String phone) {
        return phoneTokenMap.computeIfAbsent(phone, k -> UUID.randomUUID().toString());
    }

    // =========================================================================
    // SESSION CLEANUP (runs every 60 seconds)
    // =========================================================================

    @Scheduled(fixedDelay = 60_000)
    public void checkIdleSessions() {
        long now       = System.currentTimeMillis();
        long timeoutMs = TimeUnit.MINUTES.toMillis(appProperties.getSession().getTimeoutMinutes());

        // Build a reverse phone→token lookup once to avoid O(n²) inner stream per entry
        Map<String, String> tokenToPhone = new HashMap<>();
        for (Map.Entry<String, String> e : phoneTokenMap.entrySet()) {
            tokenToPhone.put(e.getValue(), e.getKey());
        }

        Iterator<Map.Entry<String, Long>> iter = lastInteractionTime.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, Long> entry = iter.next();
            String token = entry.getKey();
            if (now - entry.getValue() > timeoutMs) {
                String phone = tokenToPhone.get(token);
                if (phone != null) {
                    sendAiSensyReply(phone, "⏳ Your session has timed out due to inactivity. Type *hi* to start over.");
                    phoneTokenMap.remove(phone);
                    bgsUserTokens.remove(phone);
                    log.info("[Session] Timed out session for phone={}", phone);
                }
                userState.remove(token);
                userData.remove(token);
                iter.remove();
            }
        }
    }

    // =========================================================================
    // HEALTH CHECK
    // =========================================================================

    @GetMapping("/messages/whatsapp")
    public String testWebhook() {
        return "Webhook is running";
    }

    // =========================================================================
    // EXTERNAL SIGNUP — BGS pushes auth token after user signs up on the website
    // =========================================================================

    @PostMapping("/api/external/signup")
    public ResponseEntity<Map<String, String>> externalSignup(
            @RequestHeader(value = "X-Bot-API-Secret", required = false) String secret,
            @RequestBody JsonNode payload) {

        if (botApiSecret != null && !botApiSecret.isEmpty() && !botApiSecret.equals(secret)) {
            log.warn("[ExternalSignup] Unauthorized attempt — invalid API secret");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Invalid API Secret"));
        }

        String phone     = payload.path("phone").asText("").trim();
        String authToken = payload.path("authToken").asText("").trim();

        if (phone.isEmpty() || authToken.isEmpty()) {
            return badRequest("Both 'phone' and 'authToken' are required.");
        }

        bgsUserTokens.put(phone, authToken);
        log.info("[ExternalSignup] Token stored for phone={}", phone);
        return ok("Signup successful in bot context");
    }

    // =========================================================================
    // MAIN WEBHOOK HANDLER
    // =========================================================================

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

            String phone   = msgNode.path("phone_number").asText("").trim();
            String rawText = msgNode.path("message_content").path("text").asText("").trim();
            String buttonPayload = msgNode.path("message_content").path("button_payload").asText("").trim();
            String text = buttonPayload.isEmpty() ? rawText : buttonPayload;

            if (phone.isEmpty()) return badRequest("Phone number missing");
            if (text.isEmpty())  return ok("no text content");

            String token = getOrCreateToken(phone);
            lastInteractionTime.put(token, System.currentTimeMillis());

            // Authenticate with BGS if not already done
            if (!ensureAuthenticatedOrTriggerOtp(phone, token)) {
                return ok("awaiting_otp");
            }

            String input = text.toLowerCase().trim();

            if (STATE_AWAITING_OTP.equals(userState.getOrDefault(token, STATE_START))) {
                AuthService.AuthResult authRes = authService.verifyOtp(phone, input);
                if (authRes.success) {
                    getUserData(token).put("authToken", authRes.jwt);
                    getUserData(token).put("refreshToken", authRes.refreshToken);
                    userState.put(token, STATE_MENU);
                    sendAiSensyReply(phone, "✅ Login successful!\n\n" + buildMenuMessage());
                } else {
                    sendAiSensyReply(phone, "❌ Invalid OTP. Please try again.");
                }
                return ok("ok");
            }

            String state = userState.getOrDefault(token, STATE_START);
            String reply;
            String next  = state;

            // Handle carousel "Order Now" button payload (e.g. "ORDER_2")
            if (text.startsWith("ORDER_")) {
                String idxStr = text.substring("ORDER_".length());
                if (STATE_PRODUCTS.equals(userState.getOrDefault(token, STATE_START))) {
                    List<Map<String, String>> prods = getListFromState(token, "productsJson");
                    Map<String, String> prod = matchByIndex(prods, idxStr);
                    if (prod != null) {
                        JsonNode details = bgsGet("/product/items/" + prod.get("id"));
                        if (details != null && isProductInStock(details)) {
                            OfferService.DiscountResult discount = offerService.applyBestOffer(details);
                            getUserData(token).put("selectedId",    prod.get("id"));
                            getUserData(token).put("selectedName",  details.path("name").asText(prod.get("name")));
                            getUserData(token).put("selectedPrice", discount.finalPrice);
                            userState.put(token, STATE_ORDER_NAME);
                            sendAiSensyReply(phone, "Great! Let\u2019s place your order.\n\nPlease enter your *Full Name*:");
                        } else {
                            sendAiSensyReply(phone, "Sorry, this product is currently *out of stock*.\n\nType 0 for Main Menu.");
                        }
                        return ok("ok");
                    }
                }
                // Treat suffix as a numeric index for non-product states
                text  = idxStr;
                input = idxStr;
            }

            // --- Global resets ---
            if (input.equals("0") || input.equals("hi") || input.equals("hello") || input.equals("menu")) {
                reply = buildMenuMessage();
                next  = STATE_MENU;
                resetUserDataPreservingAuth(token);
            } else {
                // --- State machine ---
                switch (state) {

                    case STATE_MENU: {
                        if (input.equals("1")) {
                            reply = fetchCategories(phone, token);
                            next  = CAROUSEL_SENT.equals(reply) ? STATE_CATEGORIES
                                    : (reply.contains("unavailable") ? STATE_MENU : STATE_CATEGORIES);
                        } else {
                            reply = "Please reply with a valid number (1).";
                        }
                        break;
                    }

                    case STATE_CATEGORIES: {
                        if (input.equals("9")) {
                            reply = buildMenuMessage();
                            next  = STATE_MENU;
                        } else {
                            reply = handleCategorySelection(phone, token, text);
                            next  = userState.getOrDefault(token, STATE_CATEGORIES);
                        }
                        break;
                    }

                    case STATE_SUBCATEGORIES: {
                        if (input.equals("9")) {
                            reply = fetchCategories(phone, token);
                            next  = STATE_CATEGORIES;
                        } else {
                            reply = handleSubCategorySelection(phone, token, text);
                            next  = userState.getOrDefault(token, STATE_SUBCATEGORIES);
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
                                    next  = STATE_SUBCATEGORIES;
                                } else {
                                    reply = fetchCategories(phone, token);
                                    next  = STATE_CATEGORIES;
                                }
                            } else {
                                reply = fetchCategories(phone, token);
                                next  = STATE_CATEGORIES;
                            }
                        } else {
                            reply = handleProductSelection(token, text);
                            next  = userState.getOrDefault(token, STATE_PRODUCTS);
                        }
                        break;
                    }

                    case STATE_PRODUCT_DETAILS: {
                        if (input.equals("9")) {
                            String lastSlug = (String) getUserData(token).get("lastSlug");
                            String lastName  = (String) getUserData(token).get("lastCategoryName");
                            reply = fetchProducts(phone, token, lastSlug, lastName);
                            next  = STATE_PRODUCTS;
                        } else if (input.equals("1")) {
                            String prodId = (String) getUserData(token).get("selectedId");
                            JsonNode details = bgsGet("/product/items/" + prodId);
                            if (!isProductInStock(details)) {
                                reply = "Sorry, this product is currently *out of stock*.\n\n"
                                        + "Type 9 for Previous Menu or 0 for Main Menu.";
                            } else {
                                reply = "Great! Let's place your order.\n\nPlease enter your *Full Name*:";
                                next  = STATE_ORDER_NAME;
                            }
                        } else {
                            reply = "Please type 1 to Buy, 9 for Previous Menu, or 0 for Main Menu.";
                        }
                        break;
                    }

                    case STATE_ORDER_NAME: {
                        if (input.equals("9")) {
                            String lastSlug = (String) getUserData(token).get("lastSlug");
                            String lastName  = (String) getUserData(token).get("lastCategoryName");
                            reply = fetchProducts(phone, token, lastSlug, lastName);
                            next  = STATE_PRODUCTS;
                        } else {
                            getUserData(token).put("orderName", text);
                            reply = "Got it!\n\nPlease enter your *6-digit Delivery Pincode*:";
                            next  = STATE_ORDER_PINCODE;
                        }
                        break;
                    }

                    case STATE_ORDER_PINCODE: {
                        if (input.equals("9")) {
                            reply = "Please enter your *Full Name*:";
                            next  = STATE_ORDER_NAME;
                        } else if (!validationService.isPincodeValid(input)) {
                            reply = validationService.pincodeErrorMessage();
                        } else if (!validationService.isServiceable(input)) {
                            reply = validationService.serviceabilityErrorMessage(input);
                        } else {
                            getUserData(token).put("orderPincode", input);
                            reply = "Pincode serviceable!\n\nNow please enter your *Full Delivery Address* "
                                    + "(including house number, street, etc.):";
                            next  = STATE_ORDER_ADDRESS;
                        }
                        break;
                    }

                    case STATE_ORDER_ADDRESS: {
                        if (input.equals("9")) {
                            reply = "Please enter your *6-digit Delivery Pincode*:";
                            next  = STATE_ORDER_PINCODE;
                        } else if (!validationService.isAddressValid(text)) {
                            reply = validationService.addressErrorMessage();
                        } else {
                            getUserData(token).put("orderAddress", text);
                            reply = "Address saved!\n\nChoose *Payment Method*:\n\n"
                                    + "1. COD (Cash on Delivery)\n"
                                    + "2. UPI Payment\n"
                                    + "3. Online (Card / Net Banking)\n\n"
                                    + "_Please reply with 1, 2, or 3._";
                            next  = STATE_ORDER_PAYMENT;
                        }
                        break;
                    }

                    case STATE_ORDER_PAYMENT: {
                        if (input.equals("9")) {
                            reply = "Now please enter your *Full Delivery Address* "
                                    + "(including house number, street, etc.):";
                            next  = STATE_ORDER_ADDRESS;
                        } else {
                            String method = resolvePaymentMethod(input);
                            if (method == null) {
                                reply = "Please reply with a valid number (1, 2, or 3).";
                            } else {
                                getUserData(token).put("paymentMethod", method);
                                reply = buildOrderSummary(token);
                                next  = STATE_ORDER_CONFIRM;
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
                            next  = STATE_ORDER_PAYMENT;
                        } else if (input.equals("1")) {
                            OrderPlacementResult result = processFinalOrder(phone, token);
                            reply = result.message;
                            next  = result.success ? STATE_MENU : STATE_ORDER_PAYMENT;
                        } else {
                            reply = "Please type 1 to confirm the order, 9 to go back, or 0 to cancel.";
                        }
                        break;
                    }

                    default: {
                        reply = buildMenuMessage();
                        next  = STATE_MENU;
                        resetUserDataPreservingAuth(token);
                    }
                }
            }

            userState.put(token, next);
            if (!CAROUSEL_SENT.equals(reply)) {
                sendAiSensyReply(phone, reply);
            }
            return ok("ok");

        } catch (Exception e) {
            log.error("[Webhook] Unhandled exception in receiveMessage: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Internal server error"));
        }
    }

    // =========================================================================
    // ORDER PLACEMENT
    // =========================================================================

    /**
     * Wraps the result of processFinalOrder so STATE_ORDER_CONFIRM can branch
     * cleanly without brittle string-contains checks.
     */
    private static class OrderPlacementResult {
        final boolean success;
        final String  message;
        OrderPlacementResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private OrderPlacementResult processFinalOrder(String phone, String token) {
        Map<String, Object> d = getUserData(token);
        String prodId    = (String) d.get("selectedId");
        String prodName  = (String) d.get("selectedName");
        String finalPrice = (String) d.get("selectedPrice");
        String name      = (String) d.get("orderName");
        String address   = (String) d.get("orderAddress");
        String pincode   = (String) d.get("orderPincode");
        String payment   = (String) d.get("paymentMethod");
        String authToken = (String) d.get("authToken");

        if (authToken == null) {
            log.error("[Order] Cannot process order for {}: Missing BGS auth token.", phone);
            return new OrderPlacementResult(false,
                    "Oops! We could not verify your account. Please type 0 to start over.");
        }

        // 1. Re-validate stock (CRITICAL — price/availability can change between browse and confirm)
        JsonNode details = bgsGet("/product/items/" + prodId);
        if (!isProductInStock(details)) {
            return new OrderPlacementResult(false,
                    "We're sorry, but this product just went *out of stock*.\n"
                            + "Your order could not be placed. Type 0 for Main Menu.");
        }

        // 2. Create Order
        log.info("[Order] Placing order for phone={}, product='{}' ({}), price={}, payment={}",
                phone, prodName, prodId, finalPrice, payment);

        OrderService.OrderResult orderRes = orderService.createOrder(
                phone, prodId, prodName, finalPrice, name, address, pincode, payment, authToken);

        if (!orderRes.success) {
            log.error("[Order] Creation failed for phone={}: {}", phone, orderRes.errorMessage);
            // Clear auth token to force refresh on next interaction
            getUserData(token).remove("authToken");
            return new OrderPlacementResult(false,
                    "Oops! Something went wrong while creating your order. Please try again later.");
        }

        String orderId = orderRes.orderId;
        log.info("[Order] ✅ Order created. ID={}, phone={}, product='{}', price={}, payment={}",
                orderId, phone, prodName, finalPrice, payment);

        StringBuilder msg = new StringBuilder();
        msg.append("*Order Placed Successfully!*\n\n")
                .append("Order ID : #").append(orderId).append("\n")
                .append("Thank you for shopping with *YotMart*!\n");

        // 3. Generate payment link if not COD
        if (!"COD".equals(payment)) {
            PaymentService.PaymentResult payRes =
                    paymentService.generatePaymentLink(orderId, phone, finalPrice, payment, authToken);

            if (payRes.success) {
                msg.append("\n\n*Please complete your payment here:*\n").append(payRes.paymentLink);
                log.info("[Order] Payment link generated for order {}: {}", orderId, payRes.paymentLink);
            } else {
                // Payment link failed — let user choose another method
                log.warn("[Order] Payment link failed for order {}: {}", orderId, payRes.errorMessage);
                userState.put(token, STATE_ORDER_PAYMENT);
                return new OrderPlacementResult(false,
                        "Order created, but *Payment link could not be generated*: " + payRes.errorMessage
                                + "\n\nPlease reply with:\n1. COD\n2. UPI\n3. Online\n\n_Enter 1, 2, or 3._");
            }
        }

        // Cleanup order-flow data (preserve authToken for future interactions)
        resetUserDataPreservingAuth(token);
        return new OrderPlacementResult(true, msg.toString());
    }

    // =========================================================================
    // BGS API HELPER
    // =========================================================================

    private JsonNode bgsGet(String path) {
        try {
            return bgsApiClient.get(path);
        } catch (Exception e) {
            log.error("[BGS] GET {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // CATEGORY FLOW
    // =========================================================================

    private String fetchCategories(String phone, String token) {
        JsonNode data = bgsGet("/product/categories");
        if (data == null) return "Service temporarily unavailable. Please try again later.";

        JsonNode list = resolveList(data);
        if (list == null || !list.isArray() || list.size() == 0) {
            return "No categories available right now.";
        }

        StringBuilder sb = new StringBuilder("*Available Categories:*\n\n");
        List<Map<String, String>> cats = new ArrayList<>();
        List<Map<String, Object>> carouselCards = new ArrayList<>();
        List<String[]> cardMeta = new ArrayList<>(); // [name, label, imageUrl]

        int max = Math.min(list.size(), 10);
        for (int i = 0; i < max; i++) {
            JsonNode cat = list.get(i);
            try {
                String name     = cat.path("name").asText("Category " + (i + 1));
                String id       = cat.path("_id").asText(cat.path("id").asText(""));
                String slug     = cat.path("seo").path("slug").asText(cat.path("slug").asText(""));
                String imageUrl = extractImageUrl(cat, name);

                sb.append((i + 1)).append(". ").append(name).append("\n");
                cats.add(Map.of("index", String.valueOf(i + 1), "id", id, "slug", slug, "name", name));

                if (isValidImageUrl(imageUrl)) {
                    carouselCards.add(buildCarouselCard(imageUrl, name, "Browse Collection", String.valueOf(i + 1)));
                    cardMeta.add(new String[]{name, "Browse Collection", imageUrl});
                } else {
                    log.warn("[Carousel][Category] No valid image for '{}' — card skipped", name);
                    cardMeta.add(new String[]{name, "Browse Collection", "MISSING"});
                }
            } catch (Exception e) {
                log.error("[Carousel][Category] Error processing category at index {}: {}", i, e.getMessage(), e);
                // Continue to next category — do not abort the entire list
            }
        }
        sb.append("\n9. Previous Menu\n0. Main Menu\n\n_Please reply with a number._");

        try {
            getUserData(token).put("categoriesJson", objectMapper.writeValueAsString(cats));
        } catch (Exception e) {
            log.error("[Category] Failed to serialize categoriesJson: {}", e.getMessage());
        }
        userState.put(token, STATE_CATEGORIES);

        if (carouselDebug) logCarouselDebugSummary("Category", cardMeta);

        String firstValidName  = firstValidCardMeta(cardMeta, 0, "Products");
        String firstValidLabel = firstValidCardMeta(cardMeta, 1, "Browse Collection");

        if (!carouselCards.isEmpty() && sendCarouselReply(phone, carouselCards, firstValidName, firstValidLabel)) {
            return CAROUSEL_SENT;
        }
        log.info("[Carousel][Category] Falling back to text listing for phone={}", phone);
        return sb.toString();
    }

    private String handleCategorySelection(String phone, String token, String text) {
        List<Map<String, String>> cats = getListFromState(token, "categoriesJson");
        if (cats == null) return "Session expired. Type 0 to start over.";

        Map<String, String> cat = matchByIndex(cats, text);
        if (cat == null) return "Please reply with a valid number from the list above.";

        getUserData(token).put("lastCategoryId", cat.get("id"));

        JsonNode subData = bgsGet("/product/categories/" + cat.get("id") + "/children");
        JsonNode subList = resolveList(subData);

        if (subList != null && subList.size() > 0) {
            return buildSubCategoryResponse(token, subList);
        }
        return fetchProducts(phone, token, cat.get("slug"), cat.get("name"));
    }

    // =========================================================================
    // SUBCATEGORY FLOW
    // =========================================================================

    private String buildSubCategoryResponse(String token, JsonNode list) {
        StringBuilder sb = new StringBuilder("*Sub-categories:*\n\n");
        List<Map<String, String>> subs = new ArrayList<>();
        int max = Math.min(list.size(), 8);

        for (int i = 0; i < max; i++) {
            JsonNode s = list.get(i);
            String name = s.path("name").asText("Sub " + (i + 1));
            String id   = s.path("_id").asText(s.path("id").asText(""));
            String slug = s.path("seo").path("slug").asText(s.path("slug").asText(""));
            sb.append((i + 1)).append(". ").append(name).append("\n");
            subs.add(Map.of("index", String.valueOf(i + 1), "id", id, "slug", slug, "name", name));
        }
        sb.append("\n9. Previous Menu\n0. Main Menu\n\n_Please reply with a number._");

        try {
            getUserData(token).put("subCatsJson", objectMapper.writeValueAsString(subs));
        } catch (Exception e) {
            log.error("[SubCategory] Failed to serialize subCatsJson: {}", e.getMessage());
        }
        userState.put(token, STATE_SUBCATEGORIES);
        return sb.toString();
    }

    private String handleSubCategorySelection(String phone, String token, String text) {
        List<Map<String, String>> subs = getListFromState(token, "subCatsJson");
        if (subs == null) return "Session expired. Type 0 to start over.";

        Map<String, String> sub = matchByIndex(subs, text);
        if (sub == null) return "Please reply with a valid number from the list above.";

        return fetchProducts(phone, token, sub.get("slug"), sub.get("name"));
    }

    // =========================================================================
    // PRODUCT FLOW
    // =========================================================================

    private String fetchProducts(String phone, String token, String slug, String categoryName) {
        // Guard against null/blank slug to prevent garbage API calls
        if (slug == null || slug.isBlank()) {
            log.warn("[Products] Cannot fetch products — slug is null/blank for category '{}'", categoryName);
            return "Could not load products for *" + categoryName + "*. Please try again.\n\n0. Main Menu";
        }

        getUserData(token).put("lastSlug", slug);
        getUserData(token).put("lastCategoryName", categoryName);

        JsonNode data = bgsGet("/product/items/category/slug/" + slug);
        JsonNode list = resolveList(data);

        if (list == null || list.size() == 0) {
            return "No products found in *" + categoryName + "*.\n\n9. Previous Menu\n0. Main Menu";
        }

        StringBuilder sb = new StringBuilder("*Products in " + categoryName + ":*\n\n");
        List<Map<String, String>> prods = new ArrayList<>();
        List<Map<String, Object>> carouselCards = new ArrayList<>();
        List<String[]> cardMeta = new ArrayList<>(); // [name, price, imageUrl]

        int max = Math.min(list.size(), 10);
        for (int i = 0; i < max; i++) {
            JsonNode p = list.get(i);
            try {
                String name     = p.path("name").asText("Product " + (i + 1));
                String id       = p.path("_id").asText(p.path("id").asText(""));
                boolean inSt    = isProductInStock(p);
                int stockAmt    = p.path("totalStock").asInt(p.path("stock").asInt(p.path("quantity").asInt(0)));
                String imageUrl = extractImageUrl(p, name);

                OfferService.DiscountResult discount = offerService.applyBestOffer(p);
                String priceText = "\u20b9" + discount.finalPrice;

                String stockLine = inSt
                        ? (stockAmt > 0 && stockAmt <= 5 ? "In Stock (Only " + stockAmt + " left!)" : "In Stock")
                        : "Out of Stock";

                sb.append((i + 1)).append(". *").append(name).append("*\n")
                        .append("   ").append(discount.toWhatsAppLine()).append("\n")
                        .append("   Stock: ").append(stockLine).append("\n\n");

                prods.add(Map.of("index", String.valueOf(i + 1), "id", id, "name", name));

                if (isValidImageUrl(imageUrl)) {
                    carouselCards.add(buildCarouselCard(imageUrl, name, priceText, String.valueOf(i + 1)));
                    cardMeta.add(new String[]{name, priceText, imageUrl});
                    log.debug("[Carousel][Product] Card {}: name='{}', price={}, image={}",
                            i + 1, name, priceText, imageUrl);
                } else {
                    log.warn("[Carousel][Product] No valid image for '{}' (index {}) — card skipped", name, i + 1);
                    cardMeta.add(new String[]{name, priceText, "MISSING"});
                }
            } catch (Exception e) {
                log.error("[Carousel][Product] Error processing product at index {}: {}", i, e.getMessage(), e);
                // Continue to next product — do not abort the entire list
            }
        }
        sb.append("9. Previous Menu\n0. Main Menu\n\n_Please reply with a number to view product details._");

        try {
            getUserData(token).put("productsJson", objectMapper.writeValueAsString(prods));
        } catch (Exception e) {
            log.error("[Products] Failed to serialize productsJson: {}", e.getMessage());
        }
        userState.put(token, STATE_PRODUCTS);

        if (carouselDebug) logCarouselDebugSummary("Product", cardMeta);

        String firstValidName  = firstValidCardMeta(cardMeta, 0, "Product");
        String firstValidPrice = firstValidCardMeta(cardMeta, 1, "Check price");

        if (!carouselCards.isEmpty() && sendCarouselReply(phone, carouselCards, firstValidName, firstValidPrice)) {
            return CAROUSEL_SENT;
        }
        log.info("[Carousel][Product] Falling back to text listing for phone={}", phone);
        return sb.toString();
    }

    private String handleProductSelection(String token, String text) {
        List<Map<String, String>> prods = getListFromState(token, "productsJson");
        if (prods == null) return "Session expired. Type 0 to start over.";

        Map<String, String> prod = matchByIndex(prods, text);
        if (prod == null) return "Please reply with a valid number from the list above.";

        JsonNode details = bgsGet("/product/items/" + prod.get("id"));
        if (details == null) return "Could not fetch product details. Please try again.";

        String name     = details.path("name").asText(prod.get("name"));
        boolean inSt    = isProductInStock(details);
        int stockAmt    = details.path("totalStock").asInt(details.path("stock").asInt(details.path("quantity").asInt(0)));
        String desc     = details.path("description").asText("").replaceAll("<[^>]*>", "");
        if (desc.length() > 250) desc = desc.substring(0, 250) + "...";

        OfferService.DiscountResult discount = offerService.applyBestOffer(details);

        getUserData(token).put("selectedId",    prod.get("id"));
        getUserData(token).put("selectedName",  name);
        getUserData(token).put("selectedPrice", discount.finalPrice);
        userState.put(token, STATE_PRODUCT_DETAILS);

        String stockLine = inSt
                ? (stockAmt > 0 && stockAmt <= 5 ? "In Stock (Only " + stockAmt + " left!)" : "In Stock")
                : "Out of Stock";
        String buyPrompt = inSt ? "1. Buy Now\n" : "_This product is currently out of stock._\n";

        log.info("[ProductDetails] name='{}', price={}, stock={}", name, discount.finalPrice, stockLine);

        return "*" + name + "*\n\n"
                + discount.toWhatsAppLine() + "\n"
                + "Stock: " + stockLine + "\n\n"
                + desc + "\n\n"
                + buyPrompt
                + "9. Previous Menu\n"
                + "0. Main Menu\n\n"
                + "_Please reply with a number._";
    }

    // =========================================================================
    // ORDER SUMMARY
    // =========================================================================

    private String buildOrderSummary(String token) {
        Map<String, Object> d = getUserData(token);
        String name    = (String) d.getOrDefault("orderName",    "N/A");
        String address = (String) d.getOrDefault("orderAddress", "N/A");
        String pincode = (String) d.getOrDefault("orderPincode", "N/A");
        String payment = (String) d.getOrDefault("paymentMethod","N/A");
        String product = (String) d.getOrDefault("selectedName", "N/A");
        String price   = (String) d.getOrDefault("selectedPrice","N/A");

        return "*Order Summary*\n\n"
                + "Product: "  + product + "\n"
                + "Name: "     + name    + "\n"
                + "Address: "  + address + ", " + pincode + "\n"
                + "Payment: "  + payment + "\n"
                + "Total: \u20b9" + price + "\n\n"
                + "1. Confirm Order\n"
                + "9. Previous Step\n"
                + "0. Cancel & Main Menu\n\n"
                + "_Please reply with 1, 9, or 0._";
    }

    // =========================================================================
    // MENU
    // =========================================================================

    private String buildMenuMessage() {
        return "Welcome to *YotMart*!\n\nWhat would you like to do?\n\n"
                + "1. Browse Products\n\n"
                + "_Please reply with 1._";
    }

    // =========================================================================
    // CAROUSEL HELPERS
    // =========================================================================

    /**
     * Extracts the highest-quality valid image URL from a BGS API response node.
     *
     * Checks all known image field names and nested quality sub-fields.
     * Priority for nested objects: original → md → sm → xs
     *
     * Checked field families (in order):
     *   1.  image            (string or nested object with xs/sm/md/original)
     *   2.  imageUrl         (string)
     *   3.  thumbnail        (string or nested object)
     *   4.  thumbnails       (object with xs/sm/md/original)
     *   5.  featuredImage    (string)
     *   6.  bannerImage      (string)
     *   7.  media            (array of strings or objects with url field)
     *   8.  gallery          (array of strings or objects with url field)
     *
     * @param node     the JSON node to inspect
     * @param itemName the name of the product/category (for logging)
     * @return the highest-quality valid image URL, or null if none found
     */
    private String extractImageUrl(JsonNode node, String itemName) {
        if (node == null) return null;
        String label = (itemName != null) ? itemName : "unknown";

        // ── 1. image — may be a plain string or nested object ──────────────
        JsonNode imageNode = node.path("image");
        if (!imageNode.isMissingNode()) {
            if (imageNode.isTextual()) {
                String url = imageNode.asText("").trim();
                if (isValidImageUrl(url)) {
                    logImageFound(label, "image", url);
                    return url;
                }
            } else if (imageNode.isObject()) {
                String url = bestQualityFromObject(imageNode, label, "image");
                if (url != null) return url;
            }
        }

        // ── 2. imageUrl ────────────────────────────────────────────────────
        {
            String url = node.path("imageUrl").asText("").trim();
            if (isValidImageUrl(url)) { logImageFound(label, "imageUrl", url); return url; }
        }

        // ── 3. thumbnail — may be a plain string or nested object ──────────
        JsonNode thumbNode = node.path("thumbnail");
        if (!thumbNode.isMissingNode()) {
            if (thumbNode.isTextual()) {
                String url = thumbNode.asText("").trim();
                if (isValidImageUrl(url)) { logImageFound(label, "thumbnail", url); return url; }
            } else if (thumbNode.isObject()) {
                String url = bestQualityFromObject(thumbNode, label, "thumbnail");
                if (url != null) return url;
            }
        }

        // ── 4. thumbnails — object with xs/sm/md/original sub-fields ───────
        JsonNode thumbsNode = node.path("thumbnails");
        if (!thumbsNode.isMissingNode() && thumbsNode.isObject()) {
            String url = bestQualityFromObject(thumbsNode, label, "thumbnails");
            if (url != null) return url;
        }

        // ── 5. featuredImage ───────────────────────────────────────────────
        {
            String url = node.path("featuredImage").asText("").trim();
            if (isValidImageUrl(url)) { logImageFound(label, "featuredImage", url); return url; }
        }

        // ── 6. bannerImage ─────────────────────────────────────────────────
        {
            String url = node.path("bannerImage").asText("").trim();
            if (isValidImageUrl(url)) { logImageFound(label, "bannerImage", url); return url; }
        }

        // ── 7. media (array) ───────────────────────────────────────────────
        String mediaUrl = firstValidFromArray(node.path("media"), label, "media");
        if (mediaUrl != null) return mediaUrl;

        // ── 8. gallery (array) ─────────────────────────────────────────────
        String galleryUrl = firstValidFromArray(node.path("gallery"), label, "gallery");
        if (galleryUrl != null) return galleryUrl;

        log.warn("[ImageExtract] '{}': no valid image URL found in any of the 8 field families "
                + "(image, imageUrl, thumbnail, thumbnails, featuredImage, bannerImage, media, gallery)", label);
        return null;
    }

    /**
     * Picks the highest-quality URL from a nested image object.
     * Quality preference: original → md → sm → xs → url → src
     */
    private String bestQualityFromObject(JsonNode obj, String label, String fieldName) {
        for (String quality : new String[]{"original", "md", "sm", "xs", "url", "src"}) {
            JsonNode qNode = obj.path(quality);
            if (qNode.isMissingNode()) continue;
            String url = qNode.isTextual() ? qNode.asText("").trim() : qNode.path("url").asText("").trim();
            if (isValidImageUrl(url)) {
                logImageFound(label, fieldName + "." + quality, url);
                return url;
            }
        }
        return null;
    }

    /**
     * Returns the first valid image URL from a JSON array.
     * Each element may be a plain string or an object with a "url" field.
     */
    private String firstValidFromArray(JsonNode array, String label, String fieldName) {
        if (!array.isArray() || array.size() == 0) return null;
        for (int i = 0; i < array.size(); i++) {
            JsonNode elem = array.get(i);
            String url = elem.isTextual() ? elem.asText("").trim() : elem.path("url").asText("").trim();
            if (isValidImageUrl(url)) {
                logImageFound(label, fieldName + "[" + i + "]", url);
                return url;
            }
        }
        return null;
    }

    private void logImageFound(String label, String field, String url) {
        if (carouselDebug) {
            log.info("[ImageExtract] '{}': found valid URL in field '{}': {}", label, field, url);
        } else {
            log.debug("[ImageExtract] '{}': found valid URL in field '{}': {}", label, field, url);
        }
    }

    /** Returns true only for non-blank http/https URLs. */
    private boolean isValidImageUrl(String url) {
        return url != null && !url.isBlank()
                && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * Builds a single AiSensy carousel card for the yotindia_carousel template.
     * Header  → image
     * Body    → {{1}} = param1 (name), {{2}} = param2 (price / label)
     * Button 0 payload → index        (routes into state machine as typed number)
     * Button 1 payload → ORDER_<index> (intercepted before state machine)
     */
    private Map<String, Object> buildCarouselCard(
            String imageUrl, String param1, String param2, String index) {

        Map<String, Object> imageParam = new HashMap<>();
        imageParam.put("type", "image");
        imageParam.put("image", Map.of("link", imageUrl));

        Map<String, Object> header = new HashMap<>();
        header.put("type", "header");
        header.put("parameters", List.of(imageParam));

        Map<String, Object> body = new HashMap<>();
        body.put("type", "body");
        body.put("parameters", List.of(
                Map.of("type", "text", "text", param1),
                Map.of("type", "text", "text", param2)
        ));

        Map<String, Object> viewBtn = new HashMap<>();
        viewBtn.put("type", "button");
        viewBtn.put("sub_type", "quick_reply");
        viewBtn.put("index", "0");
        viewBtn.put("parameters", List.of(Map.of("type", "payload", "payload", index)));

        Map<String, Object> orderBtn = new HashMap<>();
        orderBtn.put("type", "button");
        orderBtn.put("sub_type", "quick_reply");
        orderBtn.put("index", "1");
        orderBtn.put("parameters", List.of(Map.of("type", "payload", "payload", "ORDER_" + index)));

        Map<String, Object> card = new HashMap<>();
        card.put("components", List.of(header, body, viewBtn, orderBtn));
        return card;
    }

    /**
     * Sends the yotindia_carousel template to AiSensy.
     *
     * bodyParam1 = {{1}} in the template body (first card name)
     * bodyParam2 = {{2}} in the template body (first card price/label)
     * These MUST be non-empty — Meta rejects blank strings as 0 localizable_params.
     *
     * @return true on HTTP 2xx, false on any error
     */
    private boolean sendCarouselReply(String phone, List<Map<String, Object>> cards,
                                      String bodyParam1, String bodyParam2) {
        if (cards == null || cards.isEmpty()) return false;

        // Ensure params are never blank
        String p1 = (bodyParam1 != null && !bodyParam1.isBlank()) ? bodyParam1 : "Products";
        String p2 = (bodyParam2 != null && !bodyParam2.isBlank()) ? bodyParam2 : "Shop now";

        try {
            Map<String, Object> langMap = Map.of("code", "en");

            Map<String, Object> bodyComp = new HashMap<>();
            bodyComp.put("type", "body");
            bodyComp.put("parameters", List.of(
                    Map.of("type", "text", "text", p1),
                    Map.of("type", "text", "text", p2)
            ));

            Map<String, Object> carouselComp = new HashMap<>();
            carouselComp.put("type", "carousel");
            carouselComp.put("cards", cards);

            Map<String, Object> template = new HashMap<>();
            template.put("name", "yotindia_carousel");
            template.put("language", langMap);
            template.put("components", List.of(bodyComp, carouselComp));

            Map<String, Object> payload = new HashMap<>();
            payload.put("to", phone);
            payload.put("type", "template");
            payload.put("recipient_type", "individual");
            payload.put("template", template);

            if (carouselDebug) {
                try {
                    log.info("[AiSensy Carousel] Sending to={} cards={} template=yotindia_carousel\nPayload:\n{}",
                            phone, cards.size(),
                            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
                } catch (Exception ex) {
                    log.warn("[AiSensy Carousel] Could not serialize payload for debug logging: {}", ex.getMessage());
                }
            }

            ResponseEntity<String> resp = restTemplate.postForEntity(
                    buildAiSensyUrl(), new HttpEntity<>(payload, buildAiSensyHeaders()), String.class);

            if (carouselDebug) {
                log.info("[AiSensy Carousel] Response status={} body={}", resp.getStatusCode(), resp.getBody());
            }

            if (resp.getStatusCode().is2xxSuccessful()) {
                log.info("[Carousel] ✅ Sent {} card(s) to phone={}", cards.size(), phone);
                return true;
            }
            log.warn("[Carousel] Non-2xx response for phone={}: status={} body={}",
                    phone, resp.getStatusCode(), resp.getBody());
            return false;

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("[AiSensy Carousel] 4xx error: status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            log.error("[AiSensy Carousel] 5xx error: status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.error("[Carousel] Unexpected error sending carousel to phone={}: {}", phone, e.getMessage(), e);
            return false;
        }
    }

    // =========================================================================
    // AISENSY REPLY (plain text)
    // =========================================================================

    private void sendAiSensyReply(String phone, String text) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("to", phone);
            payload.put("type", "text");
            payload.put("recipient_type", "individual");
            payload.put("text", Map.of("body", text));

            restTemplate.postForEntity(
                    buildAiSensyUrl(), new HttpEntity<>(payload, buildAiSensyHeaders()), String.class);
        } catch (Exception e) {
            log.warn("[AiSensy] Failed to send reply to phone={}: {}", phone, e.getMessage());
        }
    }

    // =========================================================================
    // AISENSY SHARED HELPERS (eliminates duplication between text and carousel)
    // =========================================================================

    private String buildAiSensyUrl() {
        return "https://apis.aisensy.com/project-apis/v1/project/"
                + appProperties.getAisensy().getProjectId() + "/messages";
    }

    private HttpHeaders buildAiSensyHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-AiSensy-Project-API-Pwd", appProperties.getAisensy().getApiKey());
        return headers;
    }

    // =========================================================================
    // CAROUSEL DEBUG SUMMARY
    // =========================================================================

    private void logCarouselDebugSummary(String type, List<String[]> cardMeta) {
        long validCount = cardMeta.stream().filter(m -> !"MISSING".equals(m[2])).count();
        StringBuilder sb = new StringBuilder();
        sb.append("\n============================================================");
        sb.append("\n[AiSensy Carousel] PRE-SEND DEBUG SUMMARY");
        sb.append("\nTemplate  : yotindia_carousel");
        sb.append("\nType      : ").append(type);
        sb.append("\nTotal     : ").append(cardMeta.size())
          .append(" items  |  Valid: ").append(validCount)
          .append("  |  Skipped (no image): ").append(cardMeta.size() - validCount);
        sb.append("\n");
        for (int i = 0; i < cardMeta.size(); i++) {
            String[] m = cardMeta.get(i);
            sb.append("\nCard ").append(i + 1).append(":");
            sb.append("\n  Name  : ").append(m[0]);
            sb.append("\n  Price : ").append(m[1]);
            sb.append("\n  Image : ").append(m[2]);
            sb.append("\n  Btns  : [View Details] [Order Now]");
            if ("MISSING".equals(m[2])) sb.append("  << CARD WILL BE SKIPPED");
        }
        sb.append("\n============================================================");
        log.debug(sb.toString());
    }

    // =========================================================================
    // UTILITY
    // =========================================================================

    private Map<String, Object> getUserData(String token) {
        return userData.computeIfAbsent(token, k -> new ConcurrentHashMap<>());
    }

    /**
     * Clears order-flow session data while preserving the BGS auth token.
     * Resetting authToken would force re-authentication on every menu visit.
     */
    private void resetUserDataPreservingAuth(String token) {
        Map<String, Object> data = userData.get(token);
        if (data == null) return;
        Map<String, Object> preserved = new HashMap<>();
        for (String key : PRESERVED_KEYS_ON_RESET) {
            if (data.containsKey(key)) {
                preserved.put(key, data.get(key));
            }
        }
        data.clear();
        data.putAll(preserved);
    }

    private boolean isJwtExpired(String jwtToken) {
        try {
            String[] parts = jwtToken.split("\\.");
            if (parts.length != 3) return true;
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode json = objectMapper.readTree(payload);
            long exp = json.path("exp").asLong(0);
            return (exp * 1000) < (System.currentTimeMillis() + 60000);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Ensures the user has a valid BGS auth token. If not, triggers the OTP flow.
     */
    private boolean ensureAuthenticatedOrTriggerOtp(String phone, String token) {
        String bgsAuthToken = (String) getUserData(token).get("authToken");
        String bgsRefreshToken = (String) getUserData(token).get("refreshToken");

        // Consume token pushed by external signup endpoint
        if (bgsAuthToken == null) {
            bgsAuthToken = bgsUserTokens.remove(phone);
            if (bgsAuthToken != null) {
                getUserData(token).put("authToken", bgsAuthToken);
            }
        }

        if (bgsAuthToken != null) {
            if (isJwtExpired(bgsAuthToken)) {
                log.info("[Auth] Token expired for {}, attempting refresh", phone);
                if (bgsRefreshToken != null) {
                    AuthService.AuthResult result = authService.refreshSession(bgsRefreshToken);
                    if (result.success) {
                        getUserData(token).put("authToken", result.jwt);
                        getUserData(token).put("refreshToken", result.refreshToken);
                        return true;
                    }
                }
                getUserData(token).remove("authToken");
                getUserData(token).remove("refreshToken");
            } else {
                return true;
            }
        }

        String state = userState.getOrDefault(token, STATE_START);
        if (STATE_AWAITING_OTP.equals(state)) {
            return true; 
        }

        if (authService.requestOtp(phone)) {
            userState.put(token, STATE_AWAITING_OTP);
            sendAiSensyReply(phone, "Welcome to *YotMart*!\n\nPlease enter the *OTP* sent to your number to continue.");
        } else {
            log.warn("[Auth] OTP request failed, trying signup for {}", phone);
            if (authService.signupUser(phone) && authService.requestOtp(phone)) {
                userState.put(token, STATE_AWAITING_OTP);
                sendAiSensyReply(phone, "Welcome to *YotMart*!\n\nWe've created an account for you. Please enter the *OTP* sent to your number to continue.");
            } else {
                sendAiSensyReply(phone, "Sorry, we could not authenticate you at this time. Please try again later.");
            }
        }
        return false;
    }

    private boolean isProductInStock(JsonNode p) {
        if (p == null) return false;
        return p.path("totalStock").asInt(0) > 0
                || p.path("stock").asInt(0) > 0
                || p.path("inStock").asBoolean(false)
                || p.path("quantity").asInt(0) > 0;
    }

    private JsonNode resolveList(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) return node;
        for (String key : new String[]{"content", "data", "items", "categories", "offers", "products", "results", "list"}) {
            if (node.has(key) && node.get(key).isArray()) return node.get(key);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> getListFromState(String token, String key) {
        Object raw = getUserData(token).get(key);
        if (raw == null) return null;
        try {
            return objectMapper.readValue(raw.toString(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            log.error("[Session] Failed to parse '{}' from session: {} | raw='{}'", key, e.getMessage(), raw);
            return null;
        }
    }

    private Map<String, String> matchByIndex(List<Map<String, String>> items, String input) {
        if (items == null || input == null) return null;
        for (Map<String, String> item : items) {
            if (input.equals(item.get("index"))) return item;
        }
        return null;
    }

    private String resolvePaymentMethod(String input) {
        return switch (input) {
            case "1" -> "COD";
            case "2" -> "UPI";
            case "3" -> "ONLINE";
            default  -> null;
        };
    }

    /**
     * Returns the value at {@code metaIndex} from the first card whose image field is not "MISSING".
     */
    private String firstValidCardMeta(List<String[]> cardMeta, int metaIndex, String fallback) {
        return cardMeta.stream()
                .filter(m -> !"MISSING".equals(m[2]))
                .map(m -> m[metaIndex])
                .findFirst()
                .orElse(fallback);
    }

    private ResponseEntity<Map<String, String>> ok(String status) {
        return ResponseEntity.ok(Map.of("status", status));
    }

    private ResponseEntity<Map<String, String>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("status", "error", "message", msg));
    }
}
