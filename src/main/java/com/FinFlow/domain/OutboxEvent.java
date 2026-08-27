package com.FinFlow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
  @Id
  @Column(length = 36)
  private String eventId;
  @Column(nullable = false, length = 100)
  private String eventType;
  @Column(nullable = false, length = 100)
  private String aggregateId;
  @Column(nullable = false, columnDefinition = "TEXT")
  private String payload;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private OutboxStatus status;
  @Column(nullable = false)
  private int retryCount;
  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;
  @Column(nullable = false)
  private LocalDateTime nextAttemptAt;
  private LocalDateTime publishedAt;

  public OutboxEvent(String eventType, String aggregateId, String payload) {
    this(eventType, aggregateId, payload, UUID.randomUUID().toString());
  }

  public OutboxEvent(String eventType, String aggregateId, String payload, String eventId) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.aggregateId = aggregateId;
    this.payload = payload;
    this.status = OutboxStatus.PENDING;
    this.createdAt = LocalDateTime.now();
    this.nextAttemptAt = this.createdAt;
  }

  public void published() {
    status = OutboxStatus.PUBLISHED;
    publishedAt = LocalDateTime.now();
  }

  public void retryLater() {
    retryCount++;
    long delaySeconds = Math.min(300L, 1L << Math.min(retryCount, 8));
    nextAttemptAt = LocalDateTime.now().plusSeconds(delaySeconds);
  }
}
