package com.FinFlow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "idempotency_record", uniqueConstraints =
    @UniqueConstraint(name = "uk_idempotency_record_key", columnNames = "idempotency_key"))
public class IdempotencyRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "idempotency_key", nullable = false, length = 100)
  private String idempotencyKey;

  @Column(nullable = false, length = 64)
  private String requestHash;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "transaction_id", unique = true)
  private Transaction transaction;

  @CreatedDate
  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  public IdempotencyRecord(String idempotencyKey, String requestHash) {
    this.idempotencyKey = idempotencyKey;
    this.requestHash = requestHash;
  }

  public void complete(Transaction transaction) {
    this.transaction = transaction;
  }
}
