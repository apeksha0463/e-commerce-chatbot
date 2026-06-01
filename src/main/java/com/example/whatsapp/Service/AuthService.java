package com.example.whatsapp.service;

import com.example.whatsapp.client.BgsApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles authentication of the WhatsApp user with the BGS backend.
 * Required because BGS APIs enforce user-level authentication for checkouts.
 */
@Service
@Slf4j
public class AuthService {

    @Autowired
    private BgsApiClient bgsApiClient;

    /**
     * Authenticates the user based on their phone number.
     * 
     * @param phone The user's WhatsApp phone number
     * @return The auth token (Bearer) if successful, otherwise null
     */
    public String authenticateUser(String phone) {
        try {
            // TODO: Update this to the EXACT endpoint used by BGS backend for customer login via WhatsApp
            String authEndpoint = "/auth/whatsapp/login"; // Placeholder

            Map<String, Object> payload = new HashMap<>();
            // TODO: Update the payload key if the backend expects something other than "phone"
            payload.put("phone", phone);

            log.info("[AuthService] Authenticating user {} with BGS backend", phone);
            
            // Assuming no auth token is needed to call the login endpoint
            JsonNode response = bgsApiClient.post(authEndpoint, payload);

            if (response != null) {
                // TODO: Update these paths if the token is returned in a different JSON structure
                String token = null;
                
                if (response.has("data") && response.path("data").has("token")) {
                    token = response.path("data").path("token").asText();
                } else if (response.has("token")) {
                    token = response.path("token").asText();
                } else if (response.has("accessToken")) {
                    token = response.path("accessToken").asText();
                }

                if (token != null && !token.isBlank() && !token.equals("null")) {
                    log.info("[AuthService] ✅ Successfully authenticated user {}", phone);
                    return token;
                }
            }
            
            log.warn("[AuthService] ❌ Failed to authenticate user {}, token not found in response", phone);
            return null;
        } catch (Exception e) {
            log.error("[AuthService] Exception during authentication for {}: {}", phone, e.getMessage());
            return null;
        }
    }
}
