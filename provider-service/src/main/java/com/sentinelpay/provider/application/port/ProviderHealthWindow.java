package com.sentinelpay.provider.application.port;

import com.sentinelpay.provider.application.model.ProviderHealthSnapshot;

/**
 * Rolling provider outcome window in Redis. Miss reads as healthy (degraded) — reactive failover remains the safety net.
 */
public interface ProviderHealthWindow {

    void recordOutcome(String provider, boolean success);

    ProviderHealthSnapshot read(String provider);
}
