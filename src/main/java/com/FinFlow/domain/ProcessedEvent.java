package com.FinFlow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {
  @Id
  @Column(length = 36)
  private String eventId;
  @Column(nullable = false, updatable = false)
  private LocalDateTime processedAt;

  public ProcessedEvent(String eventId) {
    this.eventId = eventId;
    this.processedAt = LocalDateTime.now();
  }
}
