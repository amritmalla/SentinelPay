package com.sentinelpay.risk.domain.scoring;

import java.util.Set;

public final class DisposableEmailRule implements Rule {

    static final Set<String> DISPOSABLE = Set.of(
            "mailinator.com", "guerrillamail.com", "10minutemail.com", "tempmail.com");

    @Override
    public RuleResult evaluate(ScoringInput input) {
        String domain = input.customerEmail() == null ? "" : extractDomain(input.customerEmail());
        if (DISPOSABLE.contains(domain)) {
            return new RuleResult(0.40, "disposable_email:" + domain);
        }
        return new RuleResult(0.0, null);
    }

    private static String extractDomain(String email) {
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return "";
        }
        return email.substring(at + 1).toLowerCase();
    }
}
