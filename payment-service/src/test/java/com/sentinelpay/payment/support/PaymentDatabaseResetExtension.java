package com.sentinelpay.payment.support;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;

/**
 * Resets the payment database before every test. The payment ITs share one Postgres container
 * (see {@code PaymentTestContainers}), so without a per-test reset, rows left by one test class trip
 * FK constraints (or pollute count assertions) in the next — a flake that only appears in certain run
 * orders. Auto-registered for all tests (see {@code META-INF/services} +
 * {@code junit-platform.properties}); it no-ops for tests without a Spring context or a DataSource
 * (e.g. {@code @WebMvcTest} and plain unit tests), so only the real ITs are truncated.
 */
public class PaymentDatabaseResetExtension implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        ApplicationContext applicationContext;
        try {
            applicationContext = SpringExtension.getApplicationContext(context);
        } catch (IllegalStateException notASpringTest) {
            return;
        }
        if (applicationContext.getBeanNamesForType(DataSource.class).length == 0) {
            return;
        }
        PaymentDatabaseReset.truncateAll(applicationContext.getBean(DataSource.class));
    }
}
