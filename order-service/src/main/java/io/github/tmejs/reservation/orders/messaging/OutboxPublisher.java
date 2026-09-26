package io.github.tmejs.reservation.orders.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int MAX_ERROR_LENGTH = 2_000;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final int batchSize;
    private final Duration ackTimeout;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;
    private final ReentrantLock cycleLock = new ReentrantLock();

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Clock clock,
            @Value("${reservation.outbox.batch-size:100}") int batchSize,
            @Value("${reservation.outbox.ack-timeout:10s}") Duration ackTimeout,
            @Value("${reservation.outbox.backoff-initial:1s}") Duration initialBackoff,
            @Value("${reservation.outbox.backoff-max:5m}") Duration maximumBackoff) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
        this.batchSize = batchSize;
        this.ackTimeout = ackTimeout;
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
    }

    @Scheduled(fixedDelayString = "${reservation.outbox.fixed-delay:1s}",
            initialDelayString = "${reservation.outbox.initial-delay:1s}")
    public void scheduledPublish() {
        publishBatch();
    }

    public void publishBatch() {
        if (!cycleLock.tryLock()) {
            return;
        }
        try {
            var messages = outbox.findByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                    clock.instant(), PageRequest.of(0, batchSize));
            for (OutboxEntity message : messages) {
                if (!publish(message) && Thread.currentThread().isInterrupted()) {
                    return;
                }
            }
        } finally {
            cycleLock.unlock();
        }
    }

    private boolean publish(OutboxEntity message) {
        try {
            kafka.send(message.getTopic(), message.getOrderId().toString(), message.getPayload())
                    .get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
            outbox.markPublished(message.getId(), clock.instant());
            LOG.info("Published outbox event eventId={} orderId={} topic={}",
                    message.getEventId(), message.getOrderId(), message.getTopic());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordFailure(message, exception);
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            recordFailure(message, exception);
            return false;
        }
    }

    private void recordFailure(OutboxEntity message, Exception exception) {
        Instant failedAt = clock.instant();
        String detail = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        if (detail.length() > MAX_ERROR_LENGTH) detail = detail.substring(0, MAX_ERROR_LENGTH);
        outbox.recordFailure(message.getId(), safePlus(failedAt, backoffFor(message.getAttemptCount())), detail);
        LOG.warn("Outbox publish failed eventId={} orderId={} topic={} attempt={}",
                message.getEventId(), message.getOrderId(), message.getTopic(), message.getAttemptCount() + 1, exception);
    }

    private Duration backoffFor(int completedAttempts) {
        long multiplier = 1L << Math.min(Math.max(completedAttempts, 0), 62);
        try {
            Duration candidate = initialBackoff.multipliedBy(multiplier);
            return candidate.compareTo(maximumBackoff) > 0 ? maximumBackoff : candidate;
        } catch (ArithmeticException exception) {
            return maximumBackoff;
        }
    }

    private static Instant safePlus(Instant instant, Duration duration) {
        try {
            return instant.plus(duration);
        } catch (ArithmeticException exception) {
            return Instant.MAX;
        }
    }
}
