package com.FinFlow.service;

import com.FinFlow.domain.ProcessedEvent;
import com.FinFlow.event.TransactionCompletedEvent;
import com.FinFlow.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionAuditEventProcessor {

  private final ProcessedEventRepository processedEventRepository;
  private final TransactionAuditService transactionAuditService;

  @Transactional
  public boolean process(TransactionCompletedEvent event) {
    if (processedEventRepository.existsById(event.eventId())) {
      return false;
    }

    transactionAuditService.recordFromEvent(event.eventId(), event.transactionId(),
        event.transactionType(), event.sender(), event.receiver(), event.amount(), event.occurredAt());
    processedEventRepository.saveAndFlush(new ProcessedEvent(event.eventId()));
    return true;
  }
}
