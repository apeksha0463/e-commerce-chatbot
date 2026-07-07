package com.example.whatsapp.service;

import com.example.whatsapp.client.BgsApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles authentication of the WhatsApp user with the BGS backend.
 * Required because BGS APIs enforce user-level authentication for checkouts.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    @Autowired
    private BgsApiClient bgsApiClient;

    public static class AuthResult {
        public final boolean success;
        public final String jwt;
        public final String refreshToken;
        public final String message;

        public AuthResult(boolean success, String jwt, String refreshToken, String message) {
            this.success = success;
            this.jwt = jwt;
            this.refreshToken = refreshToken;
            this.message = message;
        }

        public static AuthResult success(String jwt, String refreshToken) {
            return new AuthResult(true, jwt, refreshToken, null);
        }

        public static AuthResult failure(String message) {
            return new AuthResult(false, null, null, message);
        }
    }

    /**
     * Initiates the login process by sending an OTP to the user's phone.
     */
    public boolean requestOtp(String phone) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("phone", phone);

            log.info("[AuthService] Requesting OTP for user {} via BGS backend", phone);

            JsonNode response = bgsApiClient.post("/user-service/api/auth/login", payload);

            if (response != null) {
                log.info("[AuthService] ✅ OTP requested successfully for user {}", phone);
                return true;
            }

            log.warn("[AuthService] ❌ Failed to request OTP for user {}", phone);
            return false;

        } catch (Exception e) {
            log.error("[AuthService] Exception during OTP request for {}: {}", phone, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Verifies the OTP and extracts the JWT and Refresh Token.
     */
    public AuthResult verifyOtp(String phone, String otp) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("phone", phone);
            payload.put("otp", otp);

            log.info("[AuthService] Verifying OTP for user {}", phone);

            JsonNode response = bgsApiClient.post("/user-service/api/auth/verify-otp", payload);

            if (response != null) {
                String token = extractField(response, new String[]{"token", "authToken", "access_token", "accessToken", "jwt"});
                String refreshToken = extractField(response, new String[]{"refreshToken", "refresh_token"});
                
                if (token != null) {
                    log.info("[AuthService] ✅ Successfully verified OTP for user {}", phone);
                    return AuthResult.success(token, refreshToken);
                }
            }

            log.warn("[AuthService] ❌ Failed to verify OTP for user {} — token not found in response", phone);
            return AuthResult.failure("Invalid OTP or token missing in response.");

        } catch (Exception e) {
            log.error("[AuthService] Exception during OTP verification for {}: {}", phone, e.getMessage(), e);
            return AuthResult.failure(e.getMessage());
        }
    }

    /**
     * Refreshes the JWT using the stored Refresh Token.
     */
    public AuthResult refreshSession(String refreshTokenStr) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("refreshToken", refreshTokenStr);

            log.info("[AuthService] Attempting to refresh JWT token");

            // Typical refresh endpoint if unspecified
            JsonNode response = bgsApiClient.post("/user-service/api/auth/refresh", payload);

            if (response != null) {
                String token = extractField(response, new String[]{"token", "authToken", "access_token", "accessToken", "jwt"});
                String newRefreshToken = extractField(response, new String[]{"refreshToken", "refresh_token"});
                
                if (token != null) {
                    log.info("[AuthService] ✅ Successfully refreshed token");
                    // Keep the old refresh token if the server didn't provide a new one
                    return AuthResult.success(token, newRefreshToken != null ? newRefreshToken : refreshTokenStr);
                }
            }

            log.warn("[AuthService] ❌ Failed to refresh token");
            return AuthResult.failure("Token refresh failed");

        } catch (Exception e) {
            log.error("[AuthService] Exception during token refresh: {}", e.getMessage(), e);
            return AuthResult.failure(e.getMessage());
        }
    }
    
    /**
     * Signs up a new user if login fails due to non-existent account.
     */
    public boolean signupUser(String phone) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("phone", phone);

            log.info("[AuthService] Signing up user {} with BGS backend", phone);

            JsonNode response = bgsApiClient.post("/user-service/api/auth/signup", payload);

            if (response != null) {
                log.info("[AuthService] ✅ Successfully signed up user {}", phone);
                return true;
            }

            log.warn("[AuthService] ❌ Failed to sign up user {}", phone);
            return false;

        } catch (Exception e) {
            log.error("[AuthService] Exception during signup for {}: {}", phone, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Extracts a field from the BGS auth response.
     * Checks both root and nested data objects.
     */
    private String extractField(JsonNode response, String[] possibleNames) {
        // Direct root fields
        for (String field : possibleNames) {
            String val = response.path(field).asText("").trim();
            if (!val.isEmpty() && !val.equals("null") && !val.equals("undefined")) {
                return val;
            }
        }

        // Nested under "data"
        JsonNode dataNode = response.path("data");
        if (!dataNode.isMissingNode()) {
            for (String field : possibleNames) {
                String val = dataNode.path(field).asText("").trim();
                if (!val.isEmpty() && !val.equals("null") && !val.equals("undefined")) {
                    return val;
                }
            }
        }

        return null;
    }
}
