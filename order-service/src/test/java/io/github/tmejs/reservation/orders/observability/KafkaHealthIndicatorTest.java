package io.github.tmejs.reservation.orders.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class KafkaHealthIndicatorTest {

    @Test
    void restoresInterruptWhenHealthCheckWaitIsInterrupted() {
        var indicator = new KafkaHealthIndicator("127.0.0.1:1", Duration.ofMillis(100));
        Thread.currentThread().interrupt();
        try {
            assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
