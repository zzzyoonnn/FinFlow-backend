package com.FinFlow.repository;

import com.FinFlow.domain.OutboxEvent;
import com.FinFlow.domain.OutboxStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
  List<OutboxEvent> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAt(
      OutboxStatus status, LocalDateTime now);
}
