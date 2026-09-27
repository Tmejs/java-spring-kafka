package io.github.tmejs.reservation.orders.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("reservation.outbox.pending", pending, AtomicLong::doubleValue).register(registry);
        Gauge.builder("reservation.outbox.oldest.age", oldestAgeSeconds, AtomicLong::doubleValue)
                .baseUnit("seconds")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${reservation.metrics.outbox-refresh:10s}", initialDelayString = "0")
    public void refresh() {
        Snapshot snapshot = jdbc.queryForObject(
                "select count(*), coalesce(extract(epoch from (current_timestamp - min(created_at))), 0)::bigint "
                        + "from outbox where published_at is null",
                (row, number) -> new Snapshot(row.getLong(1), Math.max(0L, row.getLong(2))));
        pending.set(snapshot.pending());
        oldestAgeSeconds.set(snapshot.oldestAgeSeconds());
    }

    private record Snapshot(long pending, long oldestAgeSeconds) {}
}
