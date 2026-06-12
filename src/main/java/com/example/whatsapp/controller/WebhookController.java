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
    @Value("${app.carousel.debug:true}")
    private boolean carouselDebug;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    // Sentinel returned by fetchCategories/fetchProducts when carousel was sent successfully
    private static final String CAROUSEL_SENT = "__CAROUSEL_SENT__";

    // --- In-memory state stores ---------------------------------------------
    private static final Map<String, String> phoneTokenMap = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastInteractionTime = new ConcurrentHashMap<>();
    private static final Map<String, String> userState = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> userData = new ConcurrentHashMap<>();
    
    // Stores tokens pushed from external signup (Key: Phone number, Value: BGS Auth Token)
    private static final Map<String, String> bgsUserTokens = new ConcurrentHashMap<>();

    private String getOrCreateToken(String phone) {
        return phoneTokenMap.computeIfAbsent(phone, k -> UUID.randomUUID().toString());
    }

    @Scheduled(fixedDelay = 60000)
    public void checkIdleSessions() {
        long now = System.currentTimeMillis();
        long timeoutMs = TimeUnit.MINUTES.toMillis(appProperties.getSession().getTimeoutMinutes());

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
                    bgsUserTokens.remove(phone);
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

    // --- POST /api/external/signup - push auth tokens -----------------------
    @PostMapping("/api/external/signup")
    public ResponseEntity<Map<String, String>> externalSignup(
            @RequestHeader(value = "X-Bot-API-Secret", required = false) String secret,
            @RequestBody JsonNode payload) {
            
        // Security Handshake: Ensure the request is actually from your trusted BGS Backend
        if (botApiSecret != null && !botApiSecret.isEmpty() && !botApiSecret.equals(secret)) {
            log.warn("Unauthorized attempt to push external signup token!");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "error", "message", "Invalid API Secret"));
        }

        String phone = payload.path("phone").asText("").trim();
        String authToken = payload.path("authToken").asText("").trim();

        if (phone.isEmpty() || authToken.isEmpty()) {
            return badRequest("Both 'phone' and 'authToken' are required.");
        }

        bgsUserTokens.put(phone, authToken);
        log.info("External signup successful for phone: {}. Token stored.", phone);
        return ok("Signup successful in bot context");
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

            // Authenticate user with BGS if not already authenticated
            String bgsAuthToken = (String) getUserData(token).get("authToken");
            if (bgsAuthToken == null) {
                // Try fetching from the external signup map first (consume it)
                bgsAuthToken = bgsUserTokens.remove(phone);
                
                if (bgsAuthToken == null) {
                    // Fallback to internal auth service
                    bgsAuthToken = authService.authenticateUser(phone);
                }
                
                if (bgsAuthToken != null) {
                    getUserData(token).put("authToken", bgsAuthToken);
                } else {
                    log.warn("Failed to authenticate user {} with BGS backend", phone);
                }
            }

            String state = userState.getOrDefault(token, STATE_START);
            String reply;
            String next = state;

            String input = text.toLowerCase().trim();

            // Handle carousel "Order Now" button payload (e.g. "ORDER_2")
            if (text.startsWith("ORDER_")) {
                String idxStr = text.substring("ORDER_".length());
                String curState = userState.getOrDefault(token, STATE_START);
                if (STATE_PRODUCTS.equals(curState)) {
                    List<Map<String, String>> prods = getListFromState(token, "productsJson");
                    Map<String, String> prod = matchByIndex(prods, idxStr);
                    if (prod != null) {
                        JsonNode details = bgsGet("/product/items/" + prod.get("id"));
                        if (details != null && isProductInStock(details)) {
                            OfferService.DiscountResult discount = offerService.applyBestOffer(details);
                            getUserData(token).put("selectedId", prod.get("id"));
                            getUserData(token).put("selectedName", details.path("name").asText(prod.get("name")));
                            getUserData(token).put("selectedPrice", discount.finalPrice);
                            userState.put(token, STATE_ORDER_NAME);
                            sendAiSensyReply(phone, "Great! Let\u2019s place your order.\n\nPlease enter your *Full Name*:");
                            return ok("ok");
                        } else {
                            sendAiSensyReply(phone, "Sorry, this product is currently *out of stock*.\n\nType 0 for Main Menu.");
                            return ok("ok");
                        }
                    }
                }
                // For categories or fallback: treat the suffix as an index number
                text = idxStr;
                input = idxStr;
            }

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
                            reply = fetchCategories(phone, token);
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
                            reply = handleCategorySelection(phone, token, text);
                            next = userState.getOrDefault(token, STATE_CATEGORIES);
                        }
                        break;
                    }

                    case STATE_SUBCATEGORIES: {
                        if (input.equals("9")) {
                            reply = fetchCategories(phone, token);
                            next = STATE_CATEGORIES;
                        } else {
                            reply = handleSubCategorySelection(phone, token, text);
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
                                    reply = fetchCategories(phone, token);
                                    next = STATE_CATEGORIES;
                                }
                            } else {
                                reply = fetchCategories(phone, token);
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
                            reply = fetchProducts(phone, token, lastSlug, lastName);
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
                            reply = fetchProducts(phone, token, lastSlug, lastName);
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
            if (!CAROUSEL_SENT.equals(reply)) {
                sendAiSensyReply(phone, reply);
            }
            return ok("ok");

        } catch (Exception e) {
            log.error("[Error] {}", e.getMessage(), e);
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
        String authToken = (String) d.get("authToken");
        
        if (authToken == null) {
            log.error("Cannot process order for {}: Missing BGS auth token.", phone);
            return "Oops! We could not verify your account with our backend. Please type 0 to start over.";
        }

        // 1. Re‑validate Stock (CRITICAL)
        JsonNode details = bgsGet("/product/items/" + prodId);
        if (!isProductInStock(details)) {
            return "We're sorry, but this product just went *out of stock*.\nYour order could not be placed. Type 0 for Main Menu to view other products.";
        }

        // 2. Create Order in Backend
        OrderService.OrderResult orderRes = orderService.createOrder(
                phone, prodId, prodName, finalPrice, name, address, pincode, payment, authToken);

        if (!orderRes.success) {
            log.error("Order creation failed for {}: {}", phone, orderRes.errorMessage);
            // Clear the token so it gets refreshed on the next interaction if it was an auth issue
            getUserData(token).remove("authToken");
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
                    orderId, phone, finalPrice, payment, authToken);

            if (payRes.success) {
                msg.append("\n\n*Please complete your payment here:*\n").append(payRes.paymentLink);
            } else {
                // Payment link generation failed – keep user in payment step to re‑choose
                log.warn("Payment link generation failed for order {}: {}", orderId, payRes.errorMessage);
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

    private JsonNode bgsGet(String path) {
        try {
            return bgsApiClient.get(path);
        } catch (Exception e) {
            return null;
        }
    }

    // --- Fetch & format categories ------------------------------------------
    private void clearUserData(String token) {
        userData.remove(token);
        userState.remove(token);
    }

    // --- Fetch & format categories ------------------------------------------
    private String fetchCategories(String phone, String token) {
        JsonNode data = bgsGet("/product/categories");
        if (data == null)
            return "Service temporarily unavailable. Please try again later.";

        JsonNode list = resolveList(data);
        if (list == null || !list.isArray() || list.size() == 0) {
            return "No categories available right now.";
        }

        StringBuilder sb = new StringBuilder("*Available Categories:*\n\n");
        List<Map<String, String>> cats = new ArrayList<>();
        List<Map<String, Object>> carouselCards = new ArrayList<>();
        int max = Math.min(list.size(), 10); // WhatsApp carousel limit

        List<String[]> cardMeta = new ArrayList<>(); // [name, label, imageUrl]
        for (int i = 0; i < max; i++) {
            JsonNode cat = list.get(i);
            String name = cat.path("name").asText("Category " + (i + 1));
            String id = cat.path("_id").asText(cat.path("id").asText(""));
            String slug = cat.path("seo").path("slug").asText(cat.path("slug").asText(""));
            String imageUrl = extractImageUrl(cat, name);

            sb.append((i + 1)).append(". ").append(name).append("\n");
            cats.add(Map.of("index", String.valueOf(i + 1), "id", id, "slug", slug, "name", name));

            if (isValidImageUrl(imageUrl)) {
                carouselCards.add(buildCarouselCard(imageUrl, name, "Browse Collection", String.valueOf(i + 1)));
                cardMeta.add(new String[]{name, "Browse Collection", imageUrl});
            } else {
                log.warn("[Carousel][Category] Skipping card for '{}' - missing/invalid image URL", name);
                cardMeta.add(new String[]{name, "Browse Collection", "MISSING"});
            }
        }
        sb.append("\n9. Previous Menu\n0. Main Menu\n\n_Please reply with a number._");

        try {
            getUserData(token).put("categoriesJson", objectMapper.writeValueAsString(cats));
        } catch (Exception e) {
            log.error("[Carousel] Failed to serialize categoriesJson: {}", e.getMessage());
        }
        userState.put(token, STATE_CATEGORIES);

        // Attempt carousel; fall back to text listing on failure or no valid images
        if (carouselDebug) logCarouselDebugSummary("Category", cardMeta);
        // Extract first valid card's name + label for body {{1}} and {{2}}
        String firstValidCat = cardMeta.stream().filter(m -> !"MISSING".equals(m[2])).findFirst().map(m -> m[0]).orElse("Products");
        String firstLabelCat = cardMeta.stream().filter(m -> !"MISSING".equals(m[2])).findFirst().map(m -> m[1]).orElse("Browse Collection");
        if (!carouselCards.isEmpty() && sendCarouselReply(phone, carouselCards, firstValidCat, firstLabelCat)) {
            return CAROUSEL_SENT;
        }
        log.info("[Carousel][Category] Falling back to text listing for {}", phone);
        return sb.toString();
    }

    // --- Handle category selection -----------------------------------------
    private String handleCategorySelection(String phone, String token, String text) {
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
        return fetchProducts(phone, token, cat.get("slug"), cat.get("name"));
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
    private String handleSubCategorySelection(String phone, String token, String text) {
        List<Map<String, String>> subs = getListFromState(token, "subCatsJson");
        if (subs == null)
            return "Session expired. Type 0 to start over.";

        Map<String, String> sub = matchByIndex(subs, text);
        if (sub == null)
            return "Please reply with a valid number from the list above.";

        return fetchProducts(phone, token, sub.get("slug"), sub.get("name"));
    }

    // --- Fetch & format product list -----------------------------------------
    private String fetchProducts(String phone, String token, String slug, String categoryName) {
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
        int max = Math.min(list.size(), 10); // WhatsApp carousel limit

        List<String[]> cardMeta = new ArrayList<>(); // [name, price, imageUrl]
        for (int i = 0; i < max; i++) {
            JsonNode p = list.get(i);
            String name = p.path("name").asText("Product " + (i + 1));
            String id = p.path("_id").asText(p.path("id").asText(""));
            boolean inSt = isProductInStock(p);
            int stockAmt = p.path("totalStock").asInt(p.path("stock").asInt(p.path("quantity").asInt(0)));
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
            } else {
                log.warn("[Carousel][Product] Skipping card for '{}' - missing/invalid image URL", name);
                cardMeta.add(new String[]{name, priceText, "MISSING"});
            }
        }
        sb.append("9. Previous Menu\n0. Main Menu\n\n_Please reply with a number to view product details._");

        try {
            getUserData(token).put("productsJson", objectMapper.writeValueAsString(prods));
        } catch (Exception e) {
            log.error("[Carousel] Failed to serialize productsJson: {}", e.getMessage());
        }
        userState.put(token, STATE_PRODUCTS);

        // Attempt carousel; fall back to text listing on failure or no valid images
        if (carouselDebug) logCarouselDebugSummary("Product", cardMeta);
        // Pass first card's name + price as global body {{1}} and {{2}}
        String firstValidProd = cardMeta.stream().filter(m -> !"MISSING".equals(m[2])).findFirst().map(m -> m[0]).orElse("Product");
        String firstPriceProd = cardMeta.stream().filter(m -> !"MISSING".equals(m[2])).findFirst().map(m -> m[1]).orElse("Check price");
        if (!carouselCards.isEmpty() && sendCarouselReply(phone, carouselCards, firstValidProd, firstPriceProd)) {
            return CAROUSEL_SENT;
        }
        log.info("[Carousel][Product] Falling back to text listing for {}", phone);
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
    // CAROUSEL HELPERS
    // ==========================================================================

    /**
     * Searches the JSON node for a valid image URL, logging each field check when debug is enabled.
     * Priority: image -> imageUrl -> thumbnail -> featuredImage -> bannerImage -> media[0] -> gallery[0]
     */
    private String extractImageUrl(JsonNode node) {
        return extractImageUrl(node, null);
    }

    private String extractImageUrl(JsonNode node, String itemName) {
        if (node == null) return null;
        String[] fields = {"image", "imageUrl", "thumbnail", "featuredImage", "bannerImage"};
        for (String field : fields) {
            JsonNode fieldNode = node.path(field);
            String val = fieldNode.asText("").trim();
            if (val.isEmpty() || val.equals("null")) {
                if (carouselDebug && itemName != null)
                    log.debug("[ImageExtract] '{}': field '{}' is null/empty - skipping", itemName, field);
                continue;
            }
            if (isValidImageUrl(val)) {
                if (carouselDebug && itemName != null)
                    log.info("[ImageExtract] '{}': found valid URL in field '{}': {}", itemName, field, val);
                return val;
            } else {
                log.warn("[ImageExtract] '{}': field '{}' has value '{}' but failed URL validation (must start with http/https)",
                        itemName != null ? itemName : "unknown", field, val);
            }
        }
        // Check media array
        JsonNode media = node.path("media");
        if (media.isArray() && media.size() > 0) {
            JsonNode first = media.get(0);
            String url = first.isTextual() ? first.asText("") : first.path("url").asText("");
            if (isValidImageUrl(url)) {
                if (carouselDebug && itemName != null)
                    log.info("[ImageExtract] '{}': found valid URL in field 'media[0]': {}", itemName, url);
                return url;
            }
        }
        // Check gallery array
        JsonNode gallery = node.path("gallery");
        if (gallery.isArray() && gallery.size() > 0) {
            JsonNode first = gallery.get(0);
            String url = first.isTextual() ? first.asText("") : first.path("url").asText("");
            if (isValidImageUrl(url)) {
                if (carouselDebug && itemName != null)
                    log.info("[ImageExtract] '{}': found valid URL in field 'gallery[0]': {}", itemName, url);
                return url;
            }
        }
        log.warn("[ImageExtract] '{}': no valid image URL found. Checked all 7 fields (image, imageUrl, thumbnail, featuredImage, bannerImage, media[0], gallery[0])",
                itemName != null ? itemName : "unknown");
        return null;
    }

    /** Returns true only for non-blank http/https URLs. */
    private boolean isValidImageUrl(String url) {
        return url != null && !url.isBlank()
                && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * Builds a single AiSensy carousel card.
     * {{1}} = param1 (name), {{2}} = param2 (price / label)
     * Button 0 payload = index  ("View Details" → routes into existing state machine as typed number)
     * Button 1 payload = ORDER_<index>  ("Order Now" → intercepted before state machine)
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
     * Logs a human-readable pre-send carousel summary, then POSTs the yotindia_carousel
     * template to AiSensy. Returns true on HTTP 2xx; false on any error.
     */
    /**
     * Sends the yotindia_carousel template to AiSensy.
     * bodyParam1 = {{1}} in the template body (e.g. first card name)
     * bodyParam2 = {{2}} in the template body (e.g. first card price/label)
     * These MUST be non-empty — Meta counts blank strings as 0 localizable_params (#132000).
     */
    private boolean sendCarouselReply(String phone, List<Map<String, Object>> cards,
                                      String bodyParam1, String bodyParam2) {
        if (cards == null || cards.isEmpty()) return false;
        // Ensure params are never blank — Meta rejects empty string as "no param"
        String p1 = (bodyParam1 != null && !bodyParam1.isBlank()) ? bodyParam1 : "Products";
        String p2 = (bodyParam2 != null && !bodyParam2.isBlank()) ? bodyParam2 : "Shop now";
        try {
            String url = "https://apis.aisensy.com/project-apis/v1/project/"
                    + appProperties.getAisensy().getProjectId() + "/messages";

            Map<String, Object> langMap = new HashMap<>();
            langMap.put("code", "en");

            // Global body component: provides values for {{1}} and {{2}} in the template bubble.
            // Must use REAL non-empty text — Meta rejects empty strings as 0 localizable_params.
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

            // --- Debug: log the full JSON payload before sending ---
            if (carouselDebug) {
                try {
                    String payloadJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
                    log.info("[AiSensy Carousel] Sending to {} cards={} endpoint={}\nPayload:\n{}",
                            phone, cards.size(), url, payloadJson);
                } catch (Exception ex) {
                    log.warn("[AiSensy Carousel] Could not serialize payload for debug logging: {}", ex.getMessage());
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-AiSensy-Project-API-Pwd", appProperties.getAisensy().getApiKey());

            ResponseEntity<String> resp = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, headers), String.class);

            // --- Debug: log the full response ---
            if (carouselDebug) {
                log.info("[AiSensy Carousel] Response status: {} | Body: {}", resp.getStatusCode(), resp.getBody());
            }

            if (resp.getStatusCode().is2xxSuccessful()) {
                log.info("[Carousel] Successfully sent {} card(s) to {}", cards.size(), phone);
                return true;
            }
            log.warn("[Carousel] Non-2xx response for {}: status={} | body={}", phone, resp.getStatusCode(), resp.getBody());
            return false;

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("[AiSensy Carousel] 4xx Client Error: status={} | errorBody={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            log.error("[AiSensy Carousel] 5xx Server Error: status={} | errorBody={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.error("[Carousel] Unexpected error sending carousel to {}: {}", phone, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Logs a pre-send carousel debug summary in a readable format.
     * cardMeta entries: [0]=name, [1]=label/price, [2]=imageUrl or "MISSING"
     */
    private void logCarouselDebugSummary(String type, List<String[]> cardMeta) {
        long validCount = cardMeta.stream().filter(m -> !"MISSING".equals(m[2])).count();
        StringBuilder sb = new StringBuilder();
        sb.append("\n============================================================");
        sb.append("\n[AiSensy Carousel] PRE-SEND DEBUG SUMMARY");
        sb.append("\nTemplate  : yotindia_carousel");
        sb.append("\nType      : ").append(type);
        sb.append("\nTotal     : ").append(cardMeta.size())
          .append(" items  |  Valid cards: ").append(validCount)
          .append("  |  Skipped (no image): ").append(cardMeta.size() - validCount);
        sb.append("\n");
        for (int i = 0; i < cardMeta.size(); i++) {
            String[] m = cardMeta.get(i);
            sb.append("\nCard ").append(i + 1).append(":");
            sb.append("\n  Name   : ").append(m[0]);
            sb.append("\n  Price  : ").append(m[1]);
            sb.append("\n  Image  : ").append(m[2]);
            sb.append("\n  Buttons: [View Details] [Order Now]");
            if ("MISSING".equals(m[2])) sb.append("  << CARD WILL BE SKIPPED");
        }
        sb.append("\n============================================================");
        log.info(sb.toString());
    }

    // ==========================================================================
    // AiSensy REPLY
    // ==========================================================================
    private void sendAiSensyReply(String phone, String text) {
        try {
            String url = "https://apis.aisensy.com/project-apis/v1/project/" + appProperties.getAisensy().getProjectId() + "/messages";
            Map<String, Object> payload = new HashMap<>();
            payload.put("to", phone);
            payload.put("type", "text");
            payload.put("recipient_type", "individual");
            payload.put("text", Map.of("body", text));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-AiSensy-Project-API-Pwd", appProperties.getAisensy().getApiKey());

            restTemplate.postForEntity(url, new HttpEntity<>(payload, headers), JsonNode.class);
        } catch (Exception e) {
            log.warn("[AiSensy] Failed to send reply: {}", e.getMessage());
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
