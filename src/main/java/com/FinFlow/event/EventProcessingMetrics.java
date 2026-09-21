package com.FinFlow.event;

import com.FinFlow.domain.OutboxStatus;
import com.FinFlow.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "finflow.kafka.enabled", havingValue = "true")
public class EventProcessingMetrics {

  private final Counter published;
  private final Counter publishFailures;
  private final Counter consumed;
  private final Counter duplicates;
  private final Counter consumerFailures;
  private final Counter deadLetters;

  public EventProcessingMetrics(MeterRegistry registry, OutboxEventRepository repository) {
    this.published = counter(registry, "finflow.outbox.published", "Published outbox events");
    this.publishFailures = counter(registry, "finflow.outbox.publish.failures", "Outbox publish attempts that failed");
    this.consumed = counter(registry, "finflow.consumer.processed", "Events processed by the consumer");
    this.duplicates = counter(registry, "finflow.consumer.duplicates", "Duplicate events skipped by the consumer");
    this.consumerFailures = counter(registry, "finflow.consumer.failures", "Consumer delivery attempts that failed");
    this.deadLetters = counter(registry, "finflow.consumer.dlq", "Events recovered to the dead-letter topic");

    registry.gauge("finflow.outbox.pending", repository, value -> safeCount(value, OutboxStatus.PENDING));
    registry.gauge("finflow.outbox.failed", repository, value -> safeCount(value, OutboxStatus.FAILED));
  }

  private static Counter counter(MeterRegistry registry, String name, String description) {
    return Counter.builder(name).description(description).register(registry);
  }

  private static double safeCount(OutboxEventRepository repository, OutboxStatus status) {
    try {
      return repository.countByStatus(status);
    } catch (RuntimeException ignored) {
      return Double.NaN;
    }
  }

  public void published() { published.increment(); }
  public void publishFailed() { publishFailures.increment(); }
  public void consumed() { consumed.increment(); }
  public void duplicate() { duplicates.increment(); }
  public void consumerFailed() { consumerFailures.increment(); }
  public void deadLettered() { deadLetters.increment(); }
}
