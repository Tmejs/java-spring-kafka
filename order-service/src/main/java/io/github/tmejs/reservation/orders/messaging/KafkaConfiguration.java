package io.github.tmejs.reservation.orders.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.support.ProducerListener;
import org.slf4j.LoggerFactory;

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

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "reservation.outbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfiguration {}
}
