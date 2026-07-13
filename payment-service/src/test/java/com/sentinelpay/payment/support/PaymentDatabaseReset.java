package com.sentinelpay.payment.support;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Resets all payment-service tables between tests. The payment ITs share one Postgres container
 * (see {@code PaymentTestContainers}), so each test must start from a clean, FK-safe slate regardless
 * of which class ran before it. TRUNCATE ... CASCADE clears payments and every child table in one
 * statement (order-independent), which per-table {@code deleteAll()} calls did not.
 */
public final class PaymentDatabaseReset {

    private PaymentDatabaseReset() {
    }

    public static void truncateAll(DataSource dataSource) {
        // CASCADE from payments clears payment_attempts, refunds, payment_status_history and
        // idempotency_keys (all FK children); payment_outbox and processed_webhook_events have no FK
        // to payments, so they are listed explicitly.
        //
        // NOTE: no RESTART IDENTITY. The outbox bigint id feeds OutboxEnvelopeMapper.stableEventId,
        // and Kafka events from prior test classes persist in the shared broker. Resetting the
        // sequence would reuse ids and collide event ids with those stale events; keeping the
        // sequence monotonic across the JVM keeps every stableEventId unique.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE payments, payment_outbox, processed_webhook_events CASCADE");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to reset payment tables", ex);
        }
    }
}
