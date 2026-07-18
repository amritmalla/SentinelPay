package com.sentinelpay.payment.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@ConfigurationProperties(prefix = "sentinelpay.merchants")
public class MerchantCountryProperties {

    /**
     * ISO-3166-1 alpha-2 fallback when a merchant has no explicit mapping.
     */
    private String defaultCountry = "US";

    /**
     * Per-merchant country map keyed by merchant UUID string.
     */
    private Map<String, String> countries = new HashMap<>();

    @Valid
    @NotNull
    private MerchantRoutingRules routingRules = new MerchantRoutingRules();

    public String getDefaultCountry() {
        return defaultCountry;
    }

    public void setDefaultCountry(String defaultCountry) {
        this.defaultCountry = defaultCountry;
    }

    public Map<String, String> getCountries() {
        return countries;
    }

    public void setCountries(Map<String, String> countries) {
        this.countries = countries != null ? countries : new HashMap<>();
    }

    public MerchantRoutingRules getRoutingRules() {
        return routingRules;
    }

    public void setRoutingRules(MerchantRoutingRules routingRules) {
        this.routingRules = routingRules != null ? routingRules : new MerchantRoutingRules();
    }

    public String resolve(UUID merchantId) {
        if (merchantId != null) {
            String mapped = countries.get(merchantId.toString());
            if (mapped != null && !mapped.isBlank()) {
                return mapped.trim().toUpperCase(Locale.ROOT);
            }
        }
        if (defaultCountry == null || defaultCountry.isBlank()) {
            return "US";
        }
        return defaultCountry.trim().toUpperCase(Locale.ROOT);
    }
}
