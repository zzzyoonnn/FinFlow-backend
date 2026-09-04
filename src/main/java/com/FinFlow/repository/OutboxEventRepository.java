package com.FinFlow.repository;

import com.FinFlow.domain.OutboxEvent;
import com.FinFlow.domain.OutboxStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
  @Query(value = """
      SELECT * FROM outbox_event
      WHERE status = :status AND next_attempt_at <= :now
      ORDER BY created_at
      LIMIT 100
      FOR UPDATE SKIP LOCKED
      """, nativeQuery = true)
  List<OutboxEvent> lockPublishableBatch(@Param("status") String status,
      @Param("now") LocalDateTime now);

  @Modifying
  @Query("delete from OutboxEvent e where e.status = :status and e.publishedAt < :cutoff")
  int deletePublishedBefore(@Param("status") OutboxStatus status,
      @Param("cutoff") LocalDateTime cutoff);

  @Modifying
  @Query("delete from OutboxEvent e where e.status = :status and e.failedAt < :cutoff")
  int deleteFailedBefore(@Param("status") OutboxStatus status,
      @Param("cutoff") LocalDateTime cutoff);
}
