package com.FinFlow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "transaction_audit_log", uniqueConstraints = {
    @UniqueConstraint(name = "uk_transaction_audit_event", columnNames = "event_id"),
    @UniqueConstraint(name = "uk_transaction_audit_transaction", columnNames = "transaction_id")
})
public class TransactionAuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_id", nullable = false, length = 64)
  private String eventId;

  @Column(name = "transaction_id", nullable = false)
  private Long transactionId;

  @Column(nullable = false, length = 20)
  private String transactionType;

  @Column(nullable = false, length = 20)
  private String sender;

  @Column(nullable = false, length = 20)
  private String receiver;

  @Column(nullable = false)
  private Long amount;

  @Column(nullable = false, updatable = false)
  private LocalDateTime occurredAt;

  @Column(nullable = false, updatable = false)
  private LocalDateTime recordedAt;

  public TransactionAuditLog(String eventId, Long transactionId, String transactionType,
      String sender, String receiver, Long amount, LocalDateTime occurredAt) {
    this.eventId = eventId;
    this.transactionId = transactionId;
    this.transactionType = transactionType;
    this.sender = sender;
    this.receiver = receiver;
    this.amount = amount;
    this.occurredAt = occurredAt;
    this.recordedAt = LocalDateTime.now();
  }
}
