package io.github.tmejs.reservation.inventory.reservation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {
    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockOrder(UUID orderId) {
        // The same stable 64-bit key serializes decisions for one order until this transaction ends.
        long lockKey = orderId.getMostSignificantBits() ^ orderId.getLeastSignificantBits();
        jdbc.queryForObject("select pg_advisory_xact_lock(?)", Object.class, lockKey);
    }

    public boolean claimEvent(UUID eventId, Instant processedAt) {
        // Event IDs are globally unique in the event contract; V1 intentionally stores no order ID here.
        return jdbc.update(
                "insert into processed_events(event_id, processed_at) values (?, ?) on conflict do nothing",
                eventId, Timestamp.from(processedAt)) == 1;
    }

    public Optional<Decision> findDecision(UUID orderId) {
        return jdbc.query(
                        "select triggering_event_id, items_fingerprint, outcome, rejection_reason "
                                + "from reservations where order_id = ?",
                        ReservationRepository::decision,
                        orderId)
                .stream()
                .findFirst();
    }

    public boolean insertDecision(
            UUID orderId,
            UUID triggeringEventId,
            String itemsFingerprint,
            String outcome,
            String rejectionReason,
            Instant createdAt) {
        return jdbc.update(
                "insert into reservations(order_id, triggering_event_id, items_fingerprint, outcome, "
                        + "rejection_reason, created_at) values (?, ?, ?, ?, ?, ?) on conflict do nothing",
                orderId, triggeringEventId, itemsFingerprint, outcome, rejectionReason, Timestamp.from(createdAt)) == 1;
    }

    private static Decision decision(ResultSet result, int rowNumber) throws SQLException {
        return new Decision(
                result.getObject("triggering_event_id", UUID.class),
                result.getString("items_fingerprint"),
                result.getString("outcome"),
                result.getString("rejection_reason"));
    }

    public record Decision(UUID triggeringEventId, String itemsFingerprint, String outcome, String rejectionReason) {}
}
