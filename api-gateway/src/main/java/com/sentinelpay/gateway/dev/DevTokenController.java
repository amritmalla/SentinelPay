package com.sentinelpay.gateway.dev;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Profile("dev")
@RestController
@RequestMapping("/dev")
public class DevTokenController {

    private final String jwtSecret;

    public DevTokenController(@Value("${sentinelpay.security.jwt-secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    @PostMapping("/token")
    public TokenResponse mint(@RequestBody TokenRequest request) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(request.merchantId().toString())
                .claim("role", request.role())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build();

        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJwt.sign(new MACSigner(jwtSecret.getBytes()));
        return new TokenResponse(signedJwt.serialize());
    }

    public record TokenRequest(String role, @JsonProperty("merchant_id") UUID merchantId) {
    }

    public record TokenResponse(String token) {
    }
}
