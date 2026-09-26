package io.github.tmejs.reservation.orders.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
public class KafkaConfiguration {
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "reservation.outbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfiguration {}
}
