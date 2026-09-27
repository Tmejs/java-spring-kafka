package io.github.tmejs.reservation.inventory.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {
    @Transactional(readOnly = true)
    List<OutboxEntity> findByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
            Instant eligibleAt, Pageable page);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update OutboxEntity o set o.publishedAt = :publishedAt, o.lastError = null "
            + "where o.id = :id and o.publishedAt is null")
    int markPublished(@Param("id") UUID id, @Param("publishedAt") Instant publishedAt);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update OutboxEntity o set o.attemptCount = o.attemptCount + 1, "
            + "o.nextAttemptAt = :nextAttemptAt, o.lastError = :lastError "
            + "where o.id = :id and o.publishedAt is null")
    int recordFailure(@Param("id") UUID id, @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastError") String lastError);
}
