package gov.itms.platform.events;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Kafka implementation of {@link EventPublisher} (ADR-002). Uses CloudEvents structured mode: the value is the JSON
 * envelope, with the content type and event id also set as headers so consumers can filter without parsing.
 */
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final Duration sendTimeout;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, Duration sendTimeout) {
        this.kafka = kafka;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void publish(OutboundEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.key(), event.envelope());
        record.headers().add(new RecordHeader("content-type", CloudEvents.CONTENT_TYPE.getBytes(UTF_8)));
        record.headers().add(new RecordHeader("ce_id", event.id().getBytes(UTF_8)));
        record.headers().add(new RecordHeader("ce_type", event.type().getBytes(UTF_8)));
        try {
            kafka.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishingException("Interrupted while publishing event " + event.id(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EventPublishingException("Broker did not acknowledge event " + event.id(), e);
        }
    }
}
