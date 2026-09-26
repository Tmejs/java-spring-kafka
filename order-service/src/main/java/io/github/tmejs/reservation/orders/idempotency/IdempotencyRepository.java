package io.github.tmejs.reservation.orders.idempotency;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryClaim(String ownerSubject, String key, String fingerprint, Instant createdAt) {
        return jdbc.update(
                        "insert into idempotency_keys"
                                + "(owner_subject, idempotency_key, request_fingerprint, created_at) "
                                + "values (?, ?, ?, ?) on conflict (owner_subject, idempotency_key) do nothing",
                        ownerSubject,
                        key,
                        fingerprint,
                        Timestamp.from(createdAt))
                == 1;
    }

    public Optional<StoredResponse> find(String ownerSubject, String key) {
        return jdbc.query(
                        "select request_fingerprint, response_payload, response_location "
                                + "from idempotency_keys where owner_subject = ? and idempotency_key = ?",
                        (resultSet, rowNumber) -> new StoredResponse(
                                resultSet.getString("request_fingerprint"),
                                resultSet.getString("response_payload"),
                                resultSet.getString("response_location")),
                        ownerSubject,
                        key)
                .stream()
                .findFirst();
    }

    public void complete(String ownerSubject, String key, UUID orderId, String responsePayload, String location) {
        int changed = jdbc.update(
                "update idempotency_keys set order_id = ?, response_payload = ?, response_location = ? "
                        + "where owner_subject = ? and idempotency_key = ? and order_id is null",
                orderId,
                responsePayload,
                location,
                ownerSubject,
                key);
        if (changed != 1) {
            throw new IllegalStateException("Idempotency claim was not completed exactly once");
        }
    }

    public record StoredResponse(String fingerprint, String payload, String location) {}
}
