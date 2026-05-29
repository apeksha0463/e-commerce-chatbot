package com.example.whatsapp.service;

import com.example.whatsapp.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Validates address and pincode before order creation.
 * Extend isServiceable() to call a real logistics API if available.
 */
@Service
@RequiredArgsConstructor
public class ValidationService {

    private final AppProperties appProperties;

    // ── Address ──────────────────────────────────────────────────────────────

    public boolean isAddressValid(String address) {
        return address != null
                && address.trim().length() >= appProperties.getValidation().getMinAddressLength();
    }

    public String addressErrorMessage() {
        return "Please enter a *valid delivery address* (at least "
                + appProperties.getValidation().getMinAddressLength() + " characters).\n"
                + "Example: _12, Main St, Mumbai, Maharashtra_";
    }

    // ── Pincode ───────────────────────────────────────────────────────────────

    public boolean isPincodeValid(String pincode) {
        return pincode != null
                && pincode.trim().matches("\\d{" + appProperties.getValidation().getPincodeLength() + "}");
    }

    public String pincodeErrorMessage() {
        return "Please enter a valid *" + appProperties.getValidation().getPincodeLength() + "-digit pincode* (numbers only).\n"
                + "Example: _400001_";
    }

    // ── Serviceability ────────────────────────────────────────────────────────

    /**
     * Stub: always serviceable. Replace with real logistics API call.
     * Example: GET https://api.logistics.com/serviceable?pincode={pincode}
     */
    public boolean isServiceable(String pincode) {
        // TODO: call real logistics API
        return true;
    }

    public String serviceabilityErrorMessage(String pincode) {
        return "Sorry, we currently do not deliver to pincode *" + pincode + "*.\n"
                + "Please enter a different pincode or choose *COD* if available.";
    }
}
