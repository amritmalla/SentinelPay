package com.sentinelpay.payment.domain;

public enum Provider {
    STRIPE("stripe"),
    MOCKPAY("mockpay");

    private final String dbValue;

    Provider(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static Provider fromDbValue(String dbValue) {
        for (Provider provider : values()) {
            if (provider.dbValue.equals(dbValue)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Unknown provider: " + dbValue);
    }
}
