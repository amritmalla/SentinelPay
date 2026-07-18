package com.sentinelpay.payment.application;

import com.sentinelpay.payment.application.routing.RoutingContext;
import com.sentinelpay.payment.application.routing.RoutingDecision;
import com.sentinelpay.payment.application.routing.RoutingEngine;
import org.springframework.stereotype.Component;

@Component
public class ProviderRouting {

    private final RoutingEngine routingEngine;

    public ProviderRouting(RoutingEngine routingEngine) {
        this.routingEngine = routingEngine;
    }

    public RoutingDecision decide(RoutingContext context) {
        return routingEngine.decide(context);
    }
}
