package com.FinFlow.event;

import com.FinFlow.domain.ProcessedEvent;
import com.FinFlow.repository.ProcessedEventRepository;
import com.FinFlow.service.TransactionAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "finflow.kafka.enabled", havingValue = "true")
public class TransactionEventConsumer {
  private final ProcessedEventRepository processedEventRepository;
  private final ObjectMapper objectMapper;
  private final TransactionAuditService transactionAuditService;

  @KafkaListener(topics = "${finflow.kafka.transaction-topic:finflow.transaction.completed.v1}")
  @Transactional
  public void consume(String payload) throws Exception {
    TransactionCompletedEvent event = objectMapper.readValue(payload, TransactionCompletedEvent.class);
    if (processedEventRepository.existsById(event.eventId())) {
      return;
    }
    transactionAuditService.recordFromEvent(event.eventId(), event.transactionId(),
        event.transactionType(), event.sender(), event.receiver(), event.amount(), event.occurredAt());
    processedEventRepository.saveAndFlush(new ProcessedEvent(event.eventId()));
    log.info("Transaction event processed. eventId={}, transactionId={}",
        event.eventId(), event.transactionId());
  }
}
