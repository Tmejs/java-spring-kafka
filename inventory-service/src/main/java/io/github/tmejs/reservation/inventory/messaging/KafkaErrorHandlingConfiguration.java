package io.github.tmejs.reservation.inventory.messaging;

import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlingConfiguration {
    @Bean
    CommonErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> kafka,
            ObjectProvider<RetryListener> retryListeners,
            @Value("${reservation.kafka.dlt-ack-timeout:10s}") Duration dltAckTimeout) {
        var recoverer = new DeadLetterPublishingRecoverer(
                kafka, (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(dltAckTimeout);
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3L));
        RetryListener[] listeners = retryListeners.orderedStream().toArray(RetryListener[]::new);
        if (listeners.length > 0) {
            handler.setRetryListeners(listeners);
        }
        return handler;
    }
}
