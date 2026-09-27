package io.github.tmejs.reservation.orders.messaging;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedEventRepository {
    private final JdbcTemplate jdbc;

    public ProcessedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryClaim(UUID eventId, Instant processedAt) {
        return jdbc.update(
                        "insert into processed_events(event_id, processed_at) values (?, ?) "
                                + "on conflict (event_id) do nothing",
                        eventId,
                        Timestamp.from(processedAt))
                == 1;
    }
}
