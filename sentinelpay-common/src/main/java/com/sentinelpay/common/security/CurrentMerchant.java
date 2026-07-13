package com.sentinelpay.common.security;

import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import java.util.UUID;

public final class CurrentMerchant {

    private CurrentMerchant() {
    }

    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof PreAuthenticatedAuthenticationToken preAuth
                && preAuth.getPrincipal() instanceof String principal
                && !principal.isBlank()) {
            return UUID.fromString(principal);
        }
        throw new ApiException(ErrorCode.UNAUTHENTICATED, "not_authenticated");
    }
}
