package com.example.whatsapp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class WebhookController {

    @Autowired
    private RestTemplate restTemplate;

    // --- In-memory State Tracking ---
    private static final Map<String, String> userState = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> userData = new ConcurrentHashMap<>();

    // State Constants
    private static final String STATE_START = "START";
    private static final String STATE_MENU = "MENU";
    private static final String STATE_ASK_NAME = "ASK_NAME";
    private static final String STATE_ASK_ADDRESS = "ASK_ADDRESS";
    private static final String STATE_COMPLETED = "COMPLETED";

    // ---------------------------------------------------------------
    // GET endpoint — health check
    // ---------------------------------------------------------------
    @GetMapping("/messages/whatsapp")
    public String testWebhook() {
        return "Webhook working";
    }

    // ---------------------------------------------------------------
    // POST endpoint — main webhook
    // ---------------------------------------------------------------
    @PostMapping("/messages/whatsapp")
    public ResponseEntity<Map<String, String>> receiveMessage(@RequestBody JsonNode body) {
        System.out.println("=== Webhook HIT ===");
        System.out.println(body.toPrettyString());
        try {
            JsonNode messageNode = body.path("data").path("message");

            String phoneNumber = messageNode.has("phone_number")
                    ? messageNode.get("phone_number").asText().trim()
                    : "Not present";

            String messageText = "Not present";
            if (messageNode.has("message_content") && messageNode.get("message_content").has("text")) {
                messageText = messageNode.get("message_content").get("text").asText().trim();
            }

            String senderName = messageNode.has("userName")
                    ? messageNode.get("userName").asText().trim()
                    : "Unknown";

            System.out.println("[Extracted] Phone   : " + phoneNumber);
            System.out.println("[Extracted] Message : " + messageText);
            System.out.println("[Extracted] Sender  : " + senderName);

            if (phoneNumber.equals("Not present")) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Phone number missing"));
            }

            String currentState = userState.getOrDefault(phoneNumber, STATE_START);
            String replyText = "";
            String nextState = currentState;

            if (messageText.equalsIgnoreCase("Hi") || messageText.equalsIgnoreCase("Hello")
                    || currentState.equals(STATE_COMPLETED)) {
                replyText = "Welcome to Yotindia!\nReply:\n1 Order Product\n2 View Products";
                nextState = STATE_MENU;
                userData.remove(phoneNumber);
            } else {
                switch (currentState) {
                    case STATE_MENU:
                        if (messageText.equals("1")) {
                            replyText = "Enter your name:";
                            nextState = STATE_ASK_NAME;
                        } else if (messageText.equals("2")) {
                            replyText = "Available products: Leather Bag";
                            nextState = STATE_MENU;
                        } else {
                            replyText = "Please reply with 1 or 2";
                        }
                        break;

                    case STATE_ASK_NAME:
                        userData.computeIfAbsent(phoneNumber, k -> new HashMap<>()).put("name", messageText);
                        replyText = "Enter your address:";
                        nextState = STATE_ASK_ADDRESS;
                        break;

                    case STATE_ASK_ADDRESS:
                        userData.computeIfAbsent(phoneNumber, k -> new HashMap<>()).put("address", messageText);
                        replyText = "Order confirmed!\nThank you for ordering.";
                        nextState = STATE_COMPLETED;
                        break;

                    default:
                        replyText = "Welcome to Yotindia!\nReply:\n1 Order Product\n2 View Products";
                        nextState = STATE_MENU;
                        break;
                }
            }

            userState.put(phoneNumber, nextState);
            System.out.println("[Chatbot Reply]: " + replyText);

            sendAiSensyReply(phoneNumber, replyText);

            return ResponseEntity.ok(Map.of("status", "ok"));

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ---------------------------------------------------------------
    // AiSensy Project Send Message API
    // ---------------------------------------------------------------
    private void sendAiSensyReply(String phoneNumber, String replyText) {
        try {
            // Hardcoded credentials as requested to fix startup errors
            String apiKey = "577d0643178707e58a3b0";
            String projectId = "69da0b7a7dec1710f8a9db08";

            String url = "https://apis.aisensy.com/project-apis/v1/project/" + projectId + "/messages";

            Map<String, Object> payload = new HashMap<>();
            payload.put("to", phoneNumber);
            payload.put("type", "text");
            payload.put("recipient_type", "individual");

            Map<String, String> textContent = new HashMap<>();
            textContent.put("body", replyText);
            payload.put("text", textContent);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-AiSensy-Project-API-Pwd", apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            System.out.println("[AiSensy] Sending...");
            System.out.println("[AiSensy] Payload: " + payload);

            ResponseEntity<JsonNode> response = restTemplate.postForEntity(url, request, JsonNode.class);

            System.out.println("[AiSensy] Response: " + response.getBody());

        } catch (Exception e) {
            System.err.println("[AiSensy] Error");
            e.printStackTrace();
        }
    }
}
