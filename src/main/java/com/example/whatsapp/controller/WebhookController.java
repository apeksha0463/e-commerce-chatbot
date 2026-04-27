package com.example.whatsapp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@CrossOrigin(origins = "*")
@RestController
public class WebhookController {

    @Autowired
    private RestTemplate restTemplate;

    private static final Map<String, String> userState = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> userData = new ConcurrentHashMap<>();

    private static final String STATE_START = "START";
    private static final String STATE_MENU = "MENU";
    private static final String STATE_ASK_NAME = "ASK_NAME";
    private static final String STATE_ASK_ADDRESS = "ASK_ADDRESS";
    private static final String STATE_COMPLETED = "COMPLETED";

    @GetMapping("/messages/whatsapp")
    public String testWebhook() {
        return "Webhook working";
    }

    @PostMapping("/messages/whatsapp")
    public ResponseEntity<Map<String, String>> receiveMessage(@RequestBody JsonNode body) {
        System.out.println("=== Webhook HIT ===");
        System.out.println(body.toPrettyString());

        try {
            JsonNode messageNode = body.path("data").path("message");

            // -------------------------------------------------------
            // PERMANENT FIX - ignore messages sent by AGENT (bot)
            // -------------------------------------------------------
            String sender = messageNode.has("sender")
                    ? messageNode.get("sender").asText().trim()
                    : "";

            if (sender.equalsIgnoreCase("AGENT")) {
                System.out.println("[SKIP] Outgoing bot message ignored. sender=AGENT");
                Map<String, String> skip = new HashMap<>();
                skip.put("status", "ignored - bot message");
                return ResponseEntity.ok(skip);
            }

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
                Map<String, String> err = new HashMap<>();
                err.put("status", "error");
                err.put("message", "Phone number missing");
                return ResponseEntity.badRequest().body(err);
            }

            String currentState = userState.getOrDefault(phoneNumber, STATE_START);
            String replyText = "";
            String nextState = currentState;

            if (messageText.equalsIgnoreCase("Hi")
                    || messageText.equalsIgnoreCase("Hello")
                    || currentState.equals(STATE_START)
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
                            replyText = "Available products:\n- Leather Bag";
                            nextState = STATE_MENU;
                        } else {
                            replyText = "Please reply with 1 or 2.";
                        }
                        break;

                    case STATE_ASK_NAME:
                        userData.computeIfAbsent(phoneNumber, k -> new HashMap<>()).put("name", messageText);
                        replyText = "Enter your address:";
                        nextState = STATE_ASK_ADDRESS;
                        break;

                    case STATE_ASK_ADDRESS:
                        userData.computeIfAbsent(phoneNumber, k -> new HashMap<>()).put("address", messageText);
                        String savedName = userData.getOrDefault(phoneNumber, new HashMap<>())
                                .getOrDefault("name", "Customer");
                        replyText = "Order confirmed! Thank you, " + savedName
                                + ", for ordering.\nWe will deliver to: " + messageText;
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

            Map<String, String> success = new HashMap<>();
            success.put("status", "ok");
            return ResponseEntity.ok(success);

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
            Map<String, String> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    private void sendAiSensyReply(String phoneNumber, String replyText) {
        try {
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

            System.out.println("[AiSensy] Sending to: " + phoneNumber);
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(url, request, JsonNode.class);
            System.out.println("[AiSensy] Response Status : " + response.getStatusCode());
            System.out.println("[AiSensy] Response Body   : " + response.getBody());

        } catch (Exception e) {
            System.err.println("[AiSensy] Failed to send: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
