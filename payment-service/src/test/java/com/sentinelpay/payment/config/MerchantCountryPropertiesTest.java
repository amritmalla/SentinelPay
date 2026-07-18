package com.sentinelpay.payment.config;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantCountryPropertiesTest {

    @Test
    void resolve_mappedMerchant_returnsConfiguredCountry() {
        MerchantCountryProperties properties = new MerchantCountryProperties();
        UUID merchantId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        properties.setCountries(Map.of(merchantId.toString(), "gb"));

        assertThat(properties.resolve(merchantId)).isEqualTo("GB");
    }

    @Test
    void resolve_unmappedMerchant_returnsDefault() {
        MerchantCountryProperties properties = new MerchantCountryProperties();
        properties.setDefaultCountry("DE");

        assertThat(properties.resolve(UUID.randomUUID())).isEqualTo("DE");
    }

    @Test
    void routingRules_resolve_merchantSpecificOverridesDefault() {
        MerchantCountryProperties properties = new MerchantCountryProperties();
        UUID merchantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MerchantRoutingRules.RuleSet rules = new MerchantRoutingRules.RuleSet();
        rules.setPrefer("stripe");
        properties.getRoutingRules().getMerchants().put(merchantId.toString(), rules);

        assertThat(properties.getRoutingRules().resolve(merchantId).getPrefer()).isEqualTo("stripe");
        assertThat(properties.getRoutingRules().resolve(UUID.randomUUID()).getPrefer()).isNull();
    }
}
