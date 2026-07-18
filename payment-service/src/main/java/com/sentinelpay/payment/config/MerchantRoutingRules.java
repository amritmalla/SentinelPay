package com.sentinelpay.payment.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-merchant routing rules (prefer / deny / max-amount) under {@code sentinelpay.merchants.routing-rules}.
 */
public class MerchantRoutingRules {

    @Valid
    @NotNull
    @NestedConfigurationProperty
    private RuleSet defaultRules = new RuleSet();

    @Valid
    private Map<String, RuleSet> merchants = new HashMap<>();

    public RuleSet getDefaultRules() {
        return defaultRules;
    }

    public void setDefaultRules(RuleSet defaultRules) {
        this.defaultRules = defaultRules != null ? defaultRules : new RuleSet();
    }

    public Map<String, RuleSet> getMerchants() {
        return merchants;
    }

    public void setMerchants(Map<String, RuleSet> merchants) {
        this.merchants = merchants != null ? merchants : new HashMap<>();
    }

    public RuleSet resolve(UUID merchantId) {
        if (merchantId != null) {
            RuleSet specific = merchants.get(merchantId.toString());
            if (specific != null) {
                return specific;
            }
        }
        return defaultRules;
    }

    public static class RuleSet {
        private String prefer;
        private List<String> deny = List.of();
        private Map<String, Long> maxAmountCents = new HashMap<>();

        public String getPrefer() {
            return prefer;
        }

        public void setPrefer(String prefer) {
            this.prefer = prefer;
        }

        public List<String> getDeny() {
            return deny;
        }

        public void setDeny(List<String> deny) {
            this.deny = deny != null ? deny : List.of();
        }

        public Map<String, Long> getMaxAmountCents() {
            return maxAmountCents;
        }

        public void setMaxAmountCents(Map<String, Long> maxAmountCents) {
            this.maxAmountCents = maxAmountCents != null ? maxAmountCents : new HashMap<>();
        }
    }
}
