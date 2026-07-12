package com.sentinelpay.payment.application;

import com.sentinelpay.payment.domain.Provider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProviderRouting {

    public List<Provider> ordered() {
        return List.of(Provider.MOCKPAY, Provider.STRIPE);
    }
}
