package com.FinFlow.event;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.FinFlow.domain.ProcessedEvent;
import com.FinFlow.repository.ProcessedEventRepository;
import com.FinFlow.service.TransactionAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class TransactionEventConsumerTests {

  private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

  @Test
  void recordsEventIdWhenEventIsFirstConsumed() throws Exception {
    ProcessedEventRepository repository = org.mockito.Mockito.mock(ProcessedEventRepository.class);
    TransactionAuditService auditService = org.mockito.Mockito.mock(TransactionAuditService.class);
    TransactionEventConsumer consumer = new TransactionEventConsumer(repository, objectMapper, auditService);
    String payload = payload("event-1");

    consumer.consume(payload);

    ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
    verify(repository).saveAndFlush(captor.capture());
    verify(auditService).recordFromEvent(org.mockito.ArgumentMatchers.eq("event-1"),
        org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("TRANSFER"),
        org.mockito.ArgumentMatchers.eq("111"), org.mockito.ArgumentMatchers.eq("222"),
        org.mockito.ArgumentMatchers.eq(100L),
        org.mockito.ArgumentMatchers.any());
    org.assertj.core.api.Assertions.assertThat(captor.getValue().getEventId()).isEqualTo("event-1");
  }

  @Test
  void skipsAlreadyProcessedEvent() throws Exception {
    ProcessedEventRepository repository = org.mockito.Mockito.mock(ProcessedEventRepository.class);
    TransactionAuditService auditService = org.mockito.Mockito.mock(TransactionAuditService.class);
    when(repository.existsById("event-1")).thenReturn(true);
    TransactionEventConsumer consumer = new TransactionEventConsumer(repository, objectMapper, auditService);

    consumer.consume(payload("event-1"));

    verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    verify(auditService, never()).recordFromEvent(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsMalformedJsonWithoutWritingDatabase() {
    ProcessedEventRepository repository = org.mockito.Mockito.mock(ProcessedEventRepository.class);
    TransactionAuditService auditService = org.mockito.Mockito.mock(TransactionAuditService.class);
    TransactionEventConsumer consumer = new TransactionEventConsumer(repository, objectMapper, auditService);

    assertThatThrownBy(() -> consumer.consume("not-json"))
        .isInstanceOf(InvalidEventPayloadException.class);
    verify(auditService, never()).recordFromEvent(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsUnsupportedSchemaVersion() throws Exception {
    ProcessedEventRepository repository = org.mockito.Mockito.mock(ProcessedEventRepository.class);
    TransactionAuditService auditService = org.mockito.Mockito.mock(TransactionAuditService.class);
    TransactionEventConsumer consumer = new TransactionEventConsumer(repository, objectMapper, auditService);
    String payload = objectMapper.writeValueAsString(new TransactionCompletedEvent(
        2, "event-1", 1L, "TRANSFER", "111", "222", 100L, LocalDateTime.now()));

    assertThatThrownBy(() -> consumer.consume(payload))
        .isInstanceOf(UnsupportedEventSchemaException.class);
  }

  private String payload(String eventId) throws Exception {
    return objectMapper.writeValueAsString(new TransactionCompletedEvent(
        1, eventId, 1L, "TRANSFER", "111", "222", 100L, LocalDateTime.now()));
  }
}
