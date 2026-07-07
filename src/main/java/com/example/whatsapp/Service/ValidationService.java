package com.example.whatsapp.service;

import com.example.whatsapp.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Validates address and pincode before order creation.
 * Extend isServiceable() to call a real logistics API if available.
 */
@Service
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    private final AppProperties appProperties;

    public ValidationService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    // ── Address ───────────────────────────────────────────────────────────────

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
        if (pincode == null) return false;
        return pincode.trim().matches("\\d{" + appProperties.getValidation().getPincodeLength() + "}");
    }

    public String pincodeErrorMessage() {
        return "Please enter a valid *" + appProperties.getValidation().getPincodeLength()
                + "-digit pincode* (numbers only).\nExample: _400001_";
    }

    // ── Serviceability ────────────────────────────────────────────────────────

    /**
     * STUB — currently always returns true (all pincodes are considered serviceable).
     * TODO: Replace with a real logistics API call when available.
     * Example: GET https://api.logistics.com/serviceable?pincode={pincode}
     *
     * NOTE: Input is trimmed for consistency with isPincodeValid().
     */
    public boolean isServiceable(String pincode) {
        String normalized = (pincode != null) ? pincode.trim() : "";
        log.debug("[ValidationService] Serviceability check for pincode '{}' — stub returns true", normalized);
        // TODO: integrate real logistics/serviceability API here
        return true;
    }

    public String serviceabilityErrorMessage(String pincode) {
        return "Sorry, we currently do not deliver to pincode *" + pincode + "*.\n"
                + "Please enter a different pincode or choose *COD* if available.";
    }
}
