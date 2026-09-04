package com.FinFlow.repository;

import com.FinFlow.domain.ProcessedEvent;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
  @Modifying
  @Query("delete from ProcessedEvent e where e.processedAt < :cutoff")
  int deleteProcessedBefore(@Param("cutoff") LocalDateTime cutoff);
}
