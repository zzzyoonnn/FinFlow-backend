package com.FinFlow.event;

import com.FinFlow.domain.OutboxStatus;
import com.FinFlow.repository.OutboxEventRepository;
import com.FinFlow.repository.ProcessedEventRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "finflow.kafka.enabled", havingValue = "true")
public class EventRecordCleanup {
  private final OutboxEventRepository outboxEventRepository;
  private final ProcessedEventRepository processedEventRepository;

  @Value("${finflow.kafka.cleanup.outbox-retention:7d}")
  private Duration outboxRetention;
  @Value("${finflow.kafka.cleanup.failed-retention:30d}")
  private Duration failedRetention;
  @Value("${finflow.kafka.cleanup.processed-retention:30d}")
  private Duration processedRetention;

  @Scheduled(cron = "${finflow.kafka.cleanup.cron:0 0 3 * * *}")
  @Transactional
  public void cleanup() {
    LocalDateTime now = LocalDateTime.now();
    int published = outboxEventRepository.deletePublishedBefore(OutboxStatus.PUBLISHED,
        now.minus(outboxRetention));
    int failed = outboxEventRepository.deleteFailedBefore(OutboxStatus.FAILED,
        now.minus(failedRetention));
    int processed = processedEventRepository.deleteProcessedBefore(now.minus(processedRetention));
    log.info("Event cleanup completed. published={}, failed={}, processed={}",
        published, failed, processed);
  }
}
