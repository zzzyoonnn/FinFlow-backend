package com.FinFlow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.FinFlow.domain.Transaction;
import com.FinFlow.domain.TransactionAuditLog;
import com.FinFlow.domain.TransactionEnum;
import com.FinFlow.repository.TransactionAuditLogRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class TransactionAuditServiceTests {

  @Test
  void syncModeRecordsTheSameBusinessSideEffect() {
    TransactionAuditLogRepository repository = org.mockito.Mockito.mock(
        TransactionAuditLogRepository.class);
    TransactionAuditService service = new TransactionAuditService(repository);
    ReflectionTestUtils.setField(service, "mode", "sync");

    service.recordSynchronously(transaction());

    ArgumentCaptor<TransactionAuditLog> captor = ArgumentCaptor.forClass(TransactionAuditLog.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getTransactionId()).isEqualTo(10L);
    assertThat(captor.getValue().getEventId()).isEqualTo("sync-10");
  }

  @Test
  void nonSyncModeDoesNotAddAuditWorkToRequest() {
    TransactionAuditLogRepository repository = org.mockito.Mockito.mock(
        TransactionAuditLogRepository.class);
    TransactionAuditService service = new TransactionAuditService(repository);
    ReflectionTestUtils.setField(service, "mode", "kafka");

    service.recordSynchronously(transaction());

    verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  private Transaction transaction() {
    return Transaction.builder()
        .id(10L)
        .amount(100L)
        .transaction_type(TransactionEnum.TRANSFER)
        .sender("7100000000")
        .receiver("7200000000")
        .createdAt(LocalDateTime.now())
        .build();
  }
}
