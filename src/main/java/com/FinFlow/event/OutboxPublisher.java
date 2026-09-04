package com.FinFlow.event;

import com.FinFlow.domain.OutboxEvent;
import com.FinFlow.domain.OutboxStatus;
import com.FinFlow.repository.OutboxEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "finflow.kafka.enabled", havingValue = "true")
public class OutboxPublisher {
  private final OutboxEventRepository outboxEventRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;

  @Value("${finflow.kafka.transaction-topic:finflow.transaction.completed.v1}")
  private String topic;
  @Value("${finflow.kafka.outbox-max-attempts:5}")
  private int maxAttempts;

  @Scheduled(fixedDelayString = "${finflow.kafka.outbox-poll-interval:1000}")
  @Transactional
  public void publishPending() {
    List<OutboxEvent> events = outboxEventRepository.lockPublishableBatch(
        OutboxStatus.PENDING.name(), LocalDateTime.now());
    for (OutboxEvent event : events) {
      try {
        kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload()).get(10, TimeUnit.SECONDS);
        event.published();
      } catch (Exception exception) {
        event.publishFailed(exception, maxAttempts);
        log.warn("Outbox publish failed. eventId={}, retryCount={}, status={}",
            event.getEventId(), event.getRetryCount(), event.getStatus(), exception);
      }
    }
  }
}
