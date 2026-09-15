package com.FinFlow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.FinFlow.event.TransactionCompletedEvent;
import com.FinFlow.repository.ProcessedEventRepository;
import com.FinFlow.repository.TransactionAuditLogRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import({TransactionAuditEventProcessor.class, TransactionAuditService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransactionAuditEventProcessorTests {

  @Autowired
  private TransactionAuditEventProcessor processor;
  @Autowired
  private TransactionAuditLogRepository auditLogRepository;
  @Autowired
  private ProcessedEventRepository processedEventRepository;

  @BeforeEach
  void clearCommittedRows() {
    processedEventRepository.deleteAll();
    auditLogRepository.deleteAll();
  }

  @Test
  void storesAuditAndProcessedMarkerInOneTransaction() {
    TransactionCompletedEvent event = event(UUID.randomUUID().toString());

    assertThat(processor.process(event)).isTrue();

    assertThat(auditLogRepository.count()).isEqualTo(1);
    assertThat(processedEventRepository.existsById(event.eventId())).isTrue();
  }

  @Test
  void skipsEventThatWasAlreadyProcessed() {
    TransactionCompletedEvent event = event(UUID.randomUUID().toString());

    assertThat(processor.process(event)).isTrue();
    assertThat(processor.process(event)).isFalse();

    assertThat(auditLogRepository.count()).isEqualTo(1);
    assertThat(processedEventRepository.count()).isEqualTo(1);
  }

  @Test
  void rollsBackAuditWhenProcessedMarkerCannotBeStored() {
    TransactionCompletedEvent event = event("x".repeat(40));

    assertThatThrownBy(() -> processor.process(event))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(auditLogRepository.count()).isZero();
    assertThat(processedEventRepository.count()).isZero();
  }

  private TransactionCompletedEvent event(String eventId) {
    return new TransactionCompletedEvent(1, eventId, 1L, "TRANSFER", "111", "222", 100L,
        LocalDateTime.now());
  }
}
