package com.FinFlow.event;

import com.FinFlow.service.TransactionAuditEventProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "finflow.kafka.enabled", havingValue = "true")
public class TransactionEventConsumer {
  private final ObjectMapper objectMapper;
  private final TransactionAuditEventProcessor eventProcessor;

  @KafkaListener(topics = "${finflow.kafka.transaction-topic:finflow.transaction.completed.v1}")
  public void consume(String payload) {
    TransactionCompletedEvent event;
    try {
      event = objectMapper.readValue(payload, TransactionCompletedEvent.class);
    } catch (Exception exception) {
      throw new InvalidEventPayloadException("Malformed transaction event JSON", exception);
    }
    validate(event);
    if (eventProcessor.process(event)) {
      log.info("Transaction event processed. eventId={}, transactionId={}",
          event.eventId(), event.transactionId());
    } else {
      log.info("Duplicate transaction event skipped. eventId={}", event.eventId());
    }
  }

  private void validate(TransactionCompletedEvent event) {
    if (event.schemaVersion() != 1) {
      throw new UnsupportedEventSchemaException(
          "Unsupported TransactionCompleted schema version: " + event.schemaVersion());
    }
    if (event.eventId() == null || event.transactionId() == null || event.transactionType() == null
        || event.sender() == null || event.receiver() == null || event.amount() == null
        || event.occurredAt() == null) {
      throw new InvalidEventPayloadException("Transaction event is missing required fields");
    }
    try {
      UUID.fromString(event.eventId());
    } catch (IllegalArgumentException exception) {
      throw new InvalidEventPayloadException("Transaction eventId must be a UUID", exception);
    }
    if (event.transactionId() <= 0 || event.amount() <= 0
        || event.transactionType().isBlank() || event.sender().isBlank()
        || event.receiver().isBlank()) {
      throw new InvalidEventPayloadException("Transaction event contains invalid field values");
    }
  }
}
