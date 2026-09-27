package io.github.tmejs.reservation.inventory.messaging;

import io.github.tmejs.reservation.events.EventCodec;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.kafka.support.ProducerListener;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class KafkaConfiguration {
    @Bean
    ProducerListener<Object, Object> safeProducerListener() {
        var log = LoggerFactory.getLogger("kafkaProducer");
        return new ProducerListener<>() {
            @Override
            public void onError(org.apache.kafka.clients.producer.ProducerRecord<Object, Object> record,
                    org.apache.kafka.clients.producer.RecordMetadata metadata, Exception exception) {
                log.warn("Kafka send failed topic={} partition={} error={}",
                        record.topic(), record.partition(), exception.getClass().getSimpleName());
            }
        };
    }

    @Bean
    Clock outboxClock() {
        return Clock.systemUTC();
    }

    @Bean
    EventCodec eventCodec(ObjectMapper objectMapper) {
        return new EventCodec(objectMapper);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "reservation.outbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfiguration {}
}
