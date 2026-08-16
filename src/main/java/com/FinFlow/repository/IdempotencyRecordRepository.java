package com.FinFlow.repository;

import com.FinFlow.domain.IdempotencyRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

  @Query("select i from IdempotencyRecord i "
      + "join fetch i.transaction t "
      + "join fetch t.withdrawAccount "
      + "where i.idempotencyKey = :key")
  Optional<IdempotencyRecord> findCompletedByKey(@Param("key") String key);
}
