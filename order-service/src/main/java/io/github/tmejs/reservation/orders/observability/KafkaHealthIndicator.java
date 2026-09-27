package io.github.tmejs.reservation.orders.observability;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class KafkaHealthIndicator implements HealthIndicator {
    private final Map<String, Object> configuration;
    private final Duration timeout;

    public KafkaHealthIndicator(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${reservation.health.kafka-timeout:2s}") Duration timeout) {
        this.configuration = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.toIntExact(timeout.toMillis()));
        this.timeout = timeout;
    }

    @Override
    public Health health() {
        Admin admin = Admin.create(configuration);
        try {
            admin.describeCluster().clusterId().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return Health.up().build();
        } catch (Exception exception) {
            return Health.down().build();
        } finally {
            admin.close(Duration.ZERO);
        }
    }
}
