package com.sentinelpay.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MerchantCountryProperties.class)
public class MerchantConfig {
}
