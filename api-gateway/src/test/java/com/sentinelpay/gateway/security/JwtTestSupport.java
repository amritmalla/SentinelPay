package com.sentinelpay.gateway.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

public final class JwtTestSupport {

    public static final String JWT_SECRET = "test-jwt-secret-for-ci-must-be-long-enough-for-hs256";

    private JwtTestSupport() {
    }

    public static String merchantToken(UUID merchantId) throws JOSEException {
        return token(merchantId.toString(), "MERCHANT");
    }

    public static String opsToken() throws JOSEException {
        return token(UUID.randomUUID().toString(), "OPS");
    }

    private static String token(String subject, String role) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("role", role)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build();

        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJwt.sign(new MACSigner(JWT_SECRET.getBytes()));
        return signedJwt.serialize();
    }
}
