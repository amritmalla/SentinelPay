package com.sentinelpay.risk.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RiskOutboxRepository extends JpaRepository<RiskOutboxEntity, Long> {

    @Query(
            value =
                    """
                    SELECT * FROM risk_outbox
                    WHERE published_at IS NULL
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<RiskOutboxEntity> claimUnpublished(@Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE risk_outbox SET published_at = :publishedAt WHERE id = :id", nativeQuery = true)
    void markPublished(@Param("id") long id, @Param("publishedAt") Instant publishedAt);

    @Modifying
    @Query(
            value =
                    """
                    DELETE FROM risk_outbox
                    WHERE published_at IS NOT NULL AND published_at < :cutoff
                    """,
            nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
