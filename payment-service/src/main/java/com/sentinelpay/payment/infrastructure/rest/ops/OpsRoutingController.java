package com.sentinelpay.payment.infrastructure.rest.ops;

import com.sentinelpay.payment.application.OpsRoutingQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ops/routing")
public class OpsRoutingController {

    private final OpsRoutingQueryService opsRoutingQueryService;

    public OpsRoutingController(OpsRoutingQueryService opsRoutingQueryService) {
        this.opsRoutingQueryService = opsRoutingQueryService;
    }

    @GetMapping("/providers")
    public OpsRoutingProvidersResponse providers() {
        return opsRoutingQueryService.providers();
    }

    @GetMapping("/config")
    public OpsRoutingConfigResponse config() {
        return opsRoutingQueryService.config();
    }
}
