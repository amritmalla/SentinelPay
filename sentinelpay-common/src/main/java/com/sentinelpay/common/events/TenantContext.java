package com.sentinelpay.common.events;

import java.util.UUID;

/**
 * Tenant seam carried on every domain event envelope.
 */
public record TenantContext(UUID merchantId) {
}
