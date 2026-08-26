package com.FinFlow.event;

import com.FinFlow.domain.OutboxEvent;
import com.FinFlow.domain.Transaction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.UUID;

public record TransactionCompletedEvent(int schemaVersion, String eventId, Long transactionId,
    String transactionType, String sender, String receiver, Long amount, LocalDateTime occurredAt) {
  public static final String EVENT_TYPE = "TransactionCompleted";

  public static OutboxEvent toOutbox(Transaction transaction) {
    String eventId = UUID.randomUUID().toString();
    var event = new TransactionCompletedEvent(1, eventId, transaction.getId(),
        transaction.getTransaction_type().name(), transaction.getSender(), transaction.getReceiver(),
        transaction.getAmount(), transaction.getCreatedAt());
    try {
      String payload = new ObjectMapper().findAndRegisterModules().writeValueAsString(event);
      return new OutboxEvent(EVENT_TYPE, transaction.getId().toString(), payload, eventId);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("거래 완료 이벤트를 직렬화할 수 없습니다.", e);
    }
  }
}
