package io.github.tmejs.reservation.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tmejs.reservation.orders.support.OrderPostgresIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class MigrationIT extends OrderPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void migratesCompleteOrderSchemaAndValidatesMappings() {
        assertThat(tableNames()).containsExactlyInAnyOrder(
                "flyway_schema_history",
                "orders",
                "order_items",
                "idempotency_keys",
                "outbox",
                "processed_events");
        assertThat(entityNames()).contains(
                "OrderEntity", "OrderItemEntity", "OutboxEntity", "ProcessedEventEntity");
        assertThat(jdbc.queryForObject(
                        "select count(*) from flyway_schema_history where success", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select data_type from information_schema.columns "
                                + "where table_schema = 'public' and table_name = 'outbox' and column_name = 'payload'",
                        String.class))
                .isEqualTo("text");
        assertThat(jdbc.queryForObject(
                        "select indexdef from pg_indexes where schemaname = 'public' "
                                + "and tablename = 'outbox' and indexname = 'idx_outbox_pending'",
                        String.class))
                .contains("WHERE (published_at IS NULL)");
    }

    @Test
    void enforcesOrderIdempotencyEventAndQuantityConstraints() {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "insert into orders(id, owner_subject, status, created_at, updated_at) values (?, ?, ?, ?, ?)",
                orderId,
                "alice",
                "PENDING",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        jdbc.update(
                "insert into idempotency_keys(owner_subject, idempotency_key, request_fingerprint, created_at) "
                        + "values (?, ?, ?, ?)",
                "alice",
                "request-1",
                "a".repeat(64),
                Timestamp.from(Instant.now()));
        jdbc.update(
                "insert into processed_events(event_id, processed_at) values (?, ?)",
                UUID.randomUUID(),
                Timestamp.from(Instant.now()));

        assertThatThrownBy(() -> jdbc.update(
                        "insert into order_items(order_id, product_id, quantity) values (?, ?, ?)",
                        orderId,
                        UUID.randomUUID(),
                        0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into idempotency_keys(owner_subject, idempotency_key, request_fingerprint, created_at) "
                                + "values (?, ?, ?, ?)",
                        "alice",
                        "request-1",
                        "b".repeat(64),
                        Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Set<String> tableNames() {
        return Set.copyOf(jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class));
    }

    private Set<String> entityNames() {
        return entityManagerFactory.getMetamodel().getEntities().stream()
                .map(entity -> entity.getName())
                .collect(Collectors.toSet());
    }
}
