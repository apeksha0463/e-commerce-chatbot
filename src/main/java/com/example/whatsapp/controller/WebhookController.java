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
import java.util.logging.Logger;

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

    // â”€â”€â”€ Config from application.properties â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Value("${bgs.base-url:https://be.bgsinfotech.com}")
    private String bgsBaseUrl;

    @Value("${bgs.tenant-id:697c756692a4f15176fefe8e}")
    private String bgsTenantId;

    @Value("${aisensy.api-key:577d0643178707e58a3b0}")
    private String aisensyApiKey;

    @Value("${aisensy.project-id:69da0b7a7dec1710f8a9db08}")
    private String aisensyProjectId;

    // â”€â”€â”€ State constants â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private static final String STATE_START = "START";
    private static final String STATE_MENU = "MENU";
    private static final String STATE_CATEGORIES = "CATEGORIES";
    private static final String STATE_SUBCATEGORIES = "SUBCATEGORIES";
    private static final String STATE_PRODUCTS = "PRODUCTS";
    private static final String STATE_PRODUCT_DETAILS = "PRODUCT_DETAILS";
    
    // User Verification
    private static final String STATE_OTP_VERIFY = "OTP_VERIFY";

    // Order Flow
    private static final String STATE_ORDER_NAME = "ORDER_NAME";
    private static final String STATE_ORDER_PINCODE = "ORDER_PINCODE";
    private static final String STATE_ORDER_ADDRESS = "ORDER_ADDRESS";
    private static final String STATE_ORDER_PAYMENT = "ORDER_PAYMENT";
    private static final String STATE_ORDER_CONFIRM = "ORDER_CONFIRM";

    // â”€â”€â”€ Intent constants â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private enum Intent {
        BROWSE, OFFERS, BUY, NAVIGATION, UNKNOWN
    }

    // â”€â”€â”€ In-memory state stores â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private static final Map<String, String> userState = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> userData = new ConcurrentHashMap<>();
    private static final Set<String> verifiedUsers = ConcurrentHashMap.newKeySet();

    // â”€â”€â”€ GET /messages/whatsapp â€” health check â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @GetMapping("/messages/whatsapp")
    public String testWebhook() {
        return "Webhook is running âœ…";
    }

    // â”€â”€â”€ POST /messages/whatsapp â€” main handler â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

            // â”€â”€ Input normalization: button payload OR text â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            String buttonPayload = msgNode.path("message_content").path("button_payload").asText("").trim();
            String text = buttonPayload.isEmpty() ? rawText : buttonPayload;

            if (phone.isEmpty()) return badRequest("Phone number missing");
            if (text.isEmpty()) return ok("no text content");

            String state = userState.getOrDefault(phone, STATE_START);
            String reply;
            String next = state;

            // â”€â”€ Normalized input for intent detection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            String input = text.toLowerCase().trim();
            Intent intent = detectIntent(input);

            // â”€â”€ Global resets â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (intent == Intent.NAVIGATION) {
                reply = buildMenuMessage(phone);
                next = STATE_MENU;
                userData.remove(phone);
            } else {
                // â”€â”€ State machine â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                switch (state) {

                    case STATE_MENU: {
                        if (intent == Intent.BROWSE) {
                            reply = fetchCategories(phone);
                            next = reply.startsWith("âš ï¸") ? STATE_MENU : STATE_CATEGORIES;
                        } else if (intent == Intent.OFFERS) {
                            reply = offerService.fetchActiveOffersMessage();
                            next = STATE_MENU;
                        } else {
                            reply = smartFallback(state);
                        }
                        break;
                    }

                    case STATE_CATEGORIES: {
                        reply = handleCategorySelection(phone, text);
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
                            // Live stock re-check
                            String prodId = (String) getUserData(phone).get("selectedId");
                            JsonNode details = bgsGet("/product/items/" + prodId);
                            boolean inStock = isProductInStock(details);
                            
                            if (!inStock) {
                                reply = "âŒ Sorry, this product is currently *out of stock*.\n\n"
                                        + "Try browsing other products â€” type *browse* or *back*.";
                            } else {
                                // User Authentication
                                if (!verifiedUsers.contains(phone)) {
                                    String otp = String.format("%04d", new Random().nextInt(10000));
                                    getUserData(phone).put("otp", otp);
                                    reply = "ðŸ”’ *Verification Required*\n\n"
                                          + "We have sent a 4-digit OTP to your number. For testing, your OTP is: *" + otp + "*\n\n"
                                          + "Please enter the OTP to continue your order:";
                                    next = STATE_OTP_VERIFY;
                                } else {
                                    reply = "Great! Let's place your order. ðŸ›’\n\nPlease enter your *Full Name*:";
                                    next = STATE_ORDER_NAME;
                                }
                            }
                        } else {
                            reply = smartFallback(state);
                        }
                        break;
                    }

                    case STATE_OTP_VERIFY: {
                        String savedOtp = (String) getUserData(phone).get("otp");
                        if (input.equals(savedOtp)) {
                            verifiedUsers.add(phone);
                            reply = "âœ… Number verified successfully!\n\nPlease enter your *Full Name* for the order:";
                            next = STATE_ORDER_NAME;
                        } else {
                            reply = "âŒ Incorrect OTP. Please try again or type *back* to cancel.";
                        }
                        break;
                    }

                    case STATE_ORDER_NAME: {
                        getUserData(phone).put("orderName", text);
                        reply = "âœ… Got it!\n\nPlease enter your *6-digit Delivery Pincode*:";
                        next = STATE_ORDER_PINCODE;
                        break;
                    }

                    case STATE_ORDER_PINCODE: {
                        if (!validationService.isPincodeValid(input)) {
                            reply = validationService.pincodeErrorMessage();
                        } else if (!validationService.isServiceable(input)) {
                            reply = validationService.serviceabilityErrorMessage(input);
                        } else {
                            getUserData(phone).put("orderPincode", input);
                            reply = "ðŸ“ Pincode serviceable!\n\nNow please enter your *Full Delivery Address* (including house number, street, etc.):";
                            next = STATE_ORDER_ADDRESS;
                        }
                        break;
                    }

                    case STATE_ORDER_ADDRESS: {
                        if (!validationService.isAddressValid(text)) {
                            reply = validationService.addressErrorMessage();
                        } else {
                            getUserData(phone).put("orderAddress", text);
                            reply = "ðŸ“ Address saved!\n\nChoose *Payment Method*:\n\n"
                                    + "ðŸ’µ  *COD* â€” Cash on Delivery\n"
                                    + "ðŸ“±  *UPI* â€” UPI Payment\n"
                                    + "ðŸ’³  *Online* â€” Card / Net Banking\n\n"
                                    + "_Type your preferred method (e.g. cod, upi, online)._";
                            next = STATE_ORDER_PAYMENT;
                        }
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
                            reply = processFinalOrder(phone);
                            next = reply.contains("could not be generated") ? STATE_ORDER_PAYMENT : STATE_MENU;
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
            sendAiSensyReply(phone, reply);
            return ok("ok");

        } catch (Exception e) {
            log.severe("[Error] " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // ORDER PLACEMENT LOGIC
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    
    private String processFinalOrder(String phone) {
        Map<String, Object> d = getUserData(phone);
        String prodId = (String) d.get("selectedId");
        String prodName = (String) d.get("selectedName");
        String finalPrice = (String) d.get("selectedPrice");
        String name = (String) d.get("orderName");
        String address = (String) d.get("orderAddress");
        String pincode = (String) d.get("orderPincode");
        String payment = (String) d.get("paymentMethod");

        // 1. Re-validate Stock (CRITICAL)
        JsonNode details = bgsGet("/product/items/" + prodId);
        if (!isProductInStock(details)) {
            return "âŒ We're sorry, but this product just went *out of stock*.\nYour order could not be placed. Type *browse* to view other products.";
        }

        // 2. Create Order in Backend
        OrderService.OrderResult orderRes = orderService.createOrder(
                phone, prodId, prodName, finalPrice, name, address, pincode, payment);

        if (!orderRes.success) {
            log.severe("Order creation failed for " + phone + ": " + orderRes.errorMessage);
            return "âš ï¸ Oops! Something went wrong while creating your order. Please try again later or contact support.";
        }

        String orderId = orderRes.orderId;
        String msg = "ðŸŽ‰ *Order Placed Successfully!*\n\n"
                + "ðŸ“¦ Order ID : #" + orderId + "\n"
                + "Thank you for shopping with *YotMart*! ðŸ›ï¸";

        // 3. Generate Payment Link if not COD
        if (!"COD".equals(payment)) {
            PaymentService.PaymentResult payRes = paymentService.generatePaymentLink(
                    orderId, phone, finalPrice, payment);
            
            if (payRes.success) {
                msg += "\n\nðŸ”— *Please complete your payment here:*\n" + payRes.paymentLink;
            } else {
                return "âš ï¸ Order created, but *Payment link could not be generated*. Try again or choose another method.\n\nType your preferred method (*cod*, *upi*, *online*):";
            }
        }

        userData.remove(phone);
        return msg;
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // BGS API HELPER METHODS
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

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

    // â”€â”€â”€ Fetch & format categories â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private String fetchCategories(String phone) {
        JsonNode data = bgsGet("/product/categories");
        if (data == null) return "âš ï¸ Service temporarily unavailable. Please try again later.";

        JsonNode list = resolveList(data);
        if (list == null || !list.isArray() || list.size() == 0) {
            return "No categories available right now.";
        }

        StringBuilder sb = new StringBuilder("ðŸ“‚ *Available Categories:*\n\n");
        List<Map<String, String>> cats = new ArrayList<>();
        int max = Math.min(list.size(), 5);

        for (int i = 0; i < max; i++) {
            JsonNode cat = list.get(i);
            String name = cat.path("name").asText("Category " + (i + 1));
            String id = cat.path("_id").asText(cat.path("id").asText(""));
            String slug = cat.path("seo").path("slug").asText(cat.path("slug").asText(""));
            sb.append("â–¸ ").append(name).append("\n");
            cats.add(Map.of("id", id, "slug", slug, "name", name));
        }
        sb.append("\n_Type a category name to explore._\n_Type *back* for main menu._");

        try { getUserData(phone).put("categoriesJson", objectMapper.writeValueAsString(cats)); } catch (Exception ignored) {}
        userState.put(phone, STATE_CATEGORIES);
        return sb.toString();
    }

    // â”€â”€â”€ Handle category selection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private String handleCategorySelection(String phone, String text) {
        List<Map<String, String>> cats = getListFromState(phone, "categoriesJson");
        if (cats == null) return "Session expired. Type *hi* to start over.";

        Map<String, String> cat = matchByName(cats, text);
        if (cat == null) return "I couldn't find that category. Try typing a name from the list above, or type *back*.";

        JsonNode subData = bgsGet("/product/categories/" + cat.get("id") + "/children");
        JsonNode subList = resolveList(subData);

        if (subList != null && subList.size() > 0) {
            return buildSubCategoryResponse(phone, subList);
        }
        return fetchProducts(phone, cat.get("slug"), cat.get("name"));
    }

    // â”€â”€â”€ Fetch & format sub-categories â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private String buildSubCategoryResponse(String phone, JsonNode list) {
        StringBuilder sb = new StringBuilder("ðŸ“ *Sub-categories:*\n\n");
        List<Map<String, String>> subs = new ArrayList<>();
        int max = Math.min(list.size(), 5);

        for (int i = 0; i < max; i++) {
            JsonNode s = list.get(i);
            String name = s.path("name").asText("Sub " + (i + 1));
            String id = s.path("_id").asText(s.path("id").asText(""));
            String slug = s.path("seo").path("slug").asText(s.path("slug").asText(""));
            sb.append("â–¸ ").append(name).append("\n");
            subs.add(Map.of("id", id, "slug", slug, "name", name));
        }
        sb.append("\n_Type a sub-category name to explore._\n_Type *back* for main menu._");

        try { getUserData(phone).put("subCatsJson", objectMapper.writeValueAsString(subs)); } catch (Exception ignored) {}
        userState.put(phone, STATE_SUBCATEGORIES);
        return sb.toString();
    }

    // â”€â”€â”€ Handle sub-category selection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private String handleSubCategorySelection(String phone, String text) {
        List<Map<String, String>> subs = getListFromState(phone, "subCatsJson");
        if (subs == null) return "Session expired. Type *hi* to start over.";

        Map<String, String> sub = matchByName(subs, text);
        if (sub == null) return "I couldn't find that sub-category. Try typing a name from the list above, or type *back*.";

        return fetchProducts(phone, sub.get("slug"), sub.get("name"));
    }

    // â”€â”€â”€ Fetch & format product list â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private String fetchProducts(String phone, String slug, String categoryName) {
        JsonNode data = bgsGet("/product/items/category/slug/" + slug);
        JsonNode list = resolveList(data);

        if (list == null || list.size() == 0) {
            userState.put(phone, STATE_MENU);
            return "No products found in *" + categoryName + "*.\n_Type *back* to browse other categories._";
        }

        StringBuilder sb = new StringBuilder("ðŸ›ï¸ *Products in " + categoryName + ":*\n\n");
        List<Map<String, String>> prods = new ArrayList<>();
        int max = Math.min(list.size(), 5);

        for (int i = 0; i < max; i++) {
            JsonNode p = list.get(i);
            String name = p.path("name").asText("Product " + (i + 1));
            String id = p.path("_id").asText(p.path("id").asText(""));
            boolean inSt = isProductInStock(p);
            
            OfferService.DiscountResult discount = offerService.applyBestOffer(p);

            sb.append("*").append(name).append("*\n")
              .append("   ").append(discount.toWhatsAppLine()).append("\n")
              .append("   ðŸ“¦ ").append(inSt ? "âœ… In Stock" : "âŒ Out of Stock").append("\n\n");

            prods.add(Map.of("id", id, "name", name));
        }
        sb.append("_Type the product name to view details._\n_Type *back* for main menu._");

        try { getUserData(phone).put("productsJson", objectMapper.writeValueAsString(prods)); } catch (Exception ignored) {}
        userState.put(phone, STATE_PRODUCTS);
        return sb.toString();
    }

    // â”€â”€â”€ Handle product selection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private String handleProductSelection(String phone, String text) {
        List<Map<String, String>> prods = getListFromState(phone, "productsJson");
        if (prods == null) return "Session expired. Type *hi* to start over.";

        Map<String, String> prod = matchByName(prods, text);
        if (prod == null) return "I couldn't find that product. Try typing a name from the list above, or type *back*.";

        JsonNode details = bgsGet("/product/items/" + prod.get("id"));
        if (details == null) return "Could not fetch product details. Please try again.";

        String name = details.path("name").asText(prod.get("name"));
        boolean inSt = isProductInStock(details);
        OfferService.DiscountResult discount = offerService.applyBestOffer(details);
        
        String desc = details.path("description").asText("").replaceAll("<[^>]*>", "");
        if (desc.length() > 250) desc = desc.substring(0, 250) + "...";

        getUserData(phone).put("selectedId", prod.get("id"));
        getUserData(phone).put("selectedName", name);
        getUserData(phone).put("selectedPrice", discount.finalPrice);
        userState.put(phone, STATE_PRODUCT_DETAILS);

        String stockLine = inSt ? "âœ… In Stock" : "âŒ Out of Stock";
        String buyPrompt = inSt ? "Type *buy* to order ðŸ›’" : "âš ï¸ _This product is currently out of stock. Browse other products by typing *back*._";

        return "*" + name + "*\n\n"
                + discount.toWhatsAppLine() + "\n"
                + "ðŸ“¦ Stock : " + stockLine + "\n\n"
                + "ðŸ“ " + desc + "\n\n"
                + buyPrompt + "\n"
                + "Type *back* to return.";
    }

    // â”€â”€â”€ Build order summary â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private String buildOrderSummary(String phone) {
        Map<String, Object> d = getUserData(phone);
        String name = (String) d.getOrDefault("orderName", "N/A");
        String address = (String) d.getOrDefault("orderAddress", "N/A");
        String pincode = (String) d.getOrDefault("orderPincode", "N/A");
        String payment = (String) d.getOrDefault("paymentMethod", "N/A");
        String product = (String) d.getOrDefault("selectedName", "N/A");
        String price = (String) d.getOrDefault("selectedPrice", "N/A");

        return "ðŸ“‹ *Order Summary*\n\n"
                + "ðŸ›ï¸  Product : " + product + "\n"
                + "ðŸ‘¤  Name    : " + name + "\n"
                + "ðŸ“  Address : " + address + ", " + pincode + "\n"
                + "ðŸ’³  Payment : " + payment + "\n"
                + "ðŸ’°  Total   : â‚¹" + price + "\n\n"
                + "Type *confirm* to place your order or *back* to cancel.";
    }

    // â”€â”€â”€ Build welcome/menu message â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private String buildMenuMessage(String phone) {
        String prefix = !verifiedUsers.contains(phone)
                ? "ðŸŽ *New User Offer!* Register now to get deals!\n\n" : "";
        return prefix + "Welcome to *YotMart*! ðŸ›ï¸\n\nWhat would you like to do?\n\n"
                + "ðŸ›’  *Browse* â€” View product categories\n"
                + "ðŸŽ‰  *Offers* â€” View active deals & coupons\n\n"
                + "_You can type things like: *browse*, *offers*, *show bags*_";
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // AiSensy REPLY
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
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

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // UTILITY
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private Map<String, Object> getUserData(String phone) {
        return userData.computeIfAbsent(phone, k -> new ConcurrentHashMap<>());
    }
    
    private boolean isProductInStock(JsonNode p) {
        if (p == null) return false;
        return p.path("stock").asInt(0) > 0 || p.path("inStock").asBoolean(false) || p.path("quantity").asInt(0) > 0;
    }

    private JsonNode resolveList(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) return node;
        for (String key : new String[] { "data", "items", "categories", "offers", "products" }) {
            if (node.has(key) && node.get(key).isArray()) return node.get(key);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> getListFromState(String phone, String key) {
        Object raw = getUserData(phone).get(key);
        if (raw == null) return null;
        try {
            return objectMapper.readValue(raw.toString(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) { return null; }
    }

    private Map<String, String> matchByName(List<Map<String, String>> items, String input) {
        if (items == null || input == null) return null;
        String lower = input.toLowerCase().trim();
        for (Map<String, String> item : items) { if (item.get("name").toLowerCase().equals(lower)) return item; }
        for (Map<String, String> item : items) { if (item.get("name").toLowerCase().contains(lower)) return item; }
        for (Map<String, String> item : items) { if (lower.contains(item.get("name").toLowerCase())) return item; }
        return null;
    }

    private Intent detectIntent(String input) {
        if (input == null) return Intent.UNKNOWN;
        if (List.of("hi", "hello", "hey", "start", "menu", "back", "home", "reset").contains(input)) return Intent.NAVIGATION;
        if (input.contains("offer") || input.contains("discount") || input.contains("coupon") || input.contains("deal")) return Intent.OFFERS;
        if (input.contains("buy") || input.contains("order") || input.contains("checkout")) return Intent.BUY;
        if (input.contains("browse") || input.contains("categor") || input.contains("shop") || input.contains("show") || input.contains("view")) return Intent.BROWSE;
        return Intent.UNKNOWN;
    }

    private String resolvePaymentMethod(String input) {
        if (input.contains("cod") || input.contains("cash")) return "COD";
        if (input.contains("upi")) return "UPI";
        if (input.contains("online") || input.contains("card") || input.contains("net")) return "Online/Card";
        return null;
    }

    private String smartFallback(String state) {
        switch (state) {
            case STATE_MENU: return "Try typing:\nâ–¸ *browse* â€” to see products\nâ–¸ *offers* â€” to view deals";
            case STATE_CATEGORIES: return "Please type the *name* of a category from the list above, or *back*.";
            case STATE_SUBCATEGORIES: return "Please type the *name* of a sub-category from the list above, or *back*.";
            case STATE_PRODUCTS: return "Please type the *product name* from the list above, or *back*.";
            case STATE_PRODUCT_DETAILS: return "You can type *buy* to place an order, or *back* to browse.";
            default: return "I'm not sure what you mean. ðŸ¤”\nType *hi* or *menu* to start fresh.";
        }
    }

    private ResponseEntity<Map<String, String>> ok(String status) { return ResponseEntity.ok(Map.of("status", status)); }
    private ResponseEntity<Map<String, String>> badRequest(String msg) { return ResponseEntity.badRequest().body(Map.of("status", "error", "message", msg)); }
}

