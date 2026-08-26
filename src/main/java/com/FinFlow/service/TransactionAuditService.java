package com.FinFlow.service;

import com.FinFlow.domain.Transaction;
import com.FinFlow.domain.TransactionAuditLog;
import com.FinFlow.repository.TransactionAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransactionAuditService {

  private final TransactionAuditLogRepository auditLogRepository;

  @Value("${finflow.audit.mode:none}")
  private String mode;

  public void recordSynchronously(Transaction transaction) {
    if (!"sync".equalsIgnoreCase(mode)) {
      return;
    }
    auditLogRepository.save(new TransactionAuditLog(
        "sync-" + transaction.getId(), transaction.getId(),
        transaction.getTransaction_type().name(), transaction.getSender(),
        transaction.getReceiver(), transaction.getAmount(), transaction.getCreatedAt()));
  }

  public void recordFromEvent(String eventId, Long transactionId, String transactionType,
      String sender, String receiver, Long amount, java.time.LocalDateTime occurredAt) {
    auditLogRepository.saveAndFlush(new TransactionAuditLog(
        eventId, transactionId, transactionType, sender, receiver, amount, occurredAt));
  }
}
