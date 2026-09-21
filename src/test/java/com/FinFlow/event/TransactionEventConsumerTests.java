package com.FinFlow.event;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.FinFlow.service.TransactionAuditEventProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class TransactionEventConsumerTests {

  private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

  @Test
  void delegatesValidEventToTransactionalProcessor() throws Exception {
    TransactionAuditEventProcessor processor = org.mockito.Mockito.mock(
        TransactionAuditEventProcessor.class);
    TransactionEventConsumer consumer = consumer(processor);
    String eventId = UUID.randomUUID().toString();
    String payload = payload(eventId);

    consumer.consume(payload);

    verify(processor).process(org.mockito.ArgumentMatchers.argThat(
        event -> event.eventId().equals(eventId) && event.transactionId().equals(1L)));
  }

  @Test
  void acceptsDuplicateDecisionFromProcessor() throws Exception {
    TransactionAuditEventProcessor processor = org.mockito.Mockito.mock(
        TransactionAuditEventProcessor.class);
    when(processor.process(org.mockito.ArgumentMatchers.any())).thenReturn(false);
    TransactionEventConsumer consumer = consumer(processor);

    consumer.consume(payload(UUID.randomUUID().toString()));

    verify(processor).process(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsMalformedJsonWithoutWritingDatabase() {
    TransactionAuditEventProcessor processor = org.mockito.Mockito.mock(
        TransactionAuditEventProcessor.class);
    TransactionEventConsumer consumer = consumer(processor);

    assertThatThrownBy(() -> consumer.consume("not-json"))
        .isInstanceOf(InvalidEventPayloadException.class);
    verify(processor, org.mockito.Mockito.never()).process(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsUnsupportedSchemaVersion() throws Exception {
    TransactionAuditEventProcessor processor = org.mockito.Mockito.mock(
        TransactionAuditEventProcessor.class);
    TransactionEventConsumer consumer = consumer(processor);
    String payload = objectMapper.writeValueAsString(new TransactionCompletedEvent(
        2, UUID.randomUUID().toString(), 1L, "TRANSFER", "111", "222", 100L,
        LocalDateTime.now()));

    assertThatThrownBy(() -> consumer.consume(payload))
        .isInstanceOf(UnsupportedEventSchemaException.class);
  }

  @Test
  void rejectsNonUuidEventId() throws Exception {
    TransactionAuditEventProcessor processor = org.mockito.Mockito.mock(
        TransactionAuditEventProcessor.class);
    TransactionEventConsumer consumer = consumer(processor);

    assertThatThrownBy(() -> consumer.consume(payload("event-1")))
        .isInstanceOf(InvalidEventPayloadException.class);
    verify(processor, org.mockito.Mockito.never()).process(org.mockito.ArgumentMatchers.any());
  }

  private String payload(String eventId) throws Exception {
    return objectMapper.writeValueAsString(new TransactionCompletedEvent(
        1, eventId, 1L, "TRANSFER", "111", "222", 100L, LocalDateTime.now()));
  }

  private TransactionEventConsumer consumer(TransactionAuditEventProcessor processor) {
    return new TransactionEventConsumer(objectMapper, processor,
        org.mockito.Mockito.mock(EventProcessingMetrics.class));
  }
}
