package com.FinFlow.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.FinFlow.domain.OutboxEvent;
import com.FinFlow.domain.OutboxStatus;
import com.FinFlow.repository.OutboxEventRepository;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxPublisherTests {

  @Test
  void marksEventPublishedAfterKafkaAcknowledgement() {
    OutboxEventRepository repository = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    OutboxEvent event = new OutboxEvent("TransactionCompleted", "1", "{}");
    when(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAt(
        org.mockito.ArgumentMatchers.eq(OutboxStatus.PENDING), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(event));
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    OutboxPublisher publisher = new OutboxPublisher(repository, kafkaTemplate);
    ReflectionTestUtils.setField(publisher, "topic", "transactions");

    publisher.publishPending();

    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    verify(kafkaTemplate).send("transactions", "1", "{}");
  }

  @Test
  void schedulesRetryWhenKafkaPublishFails() {
    OutboxEventRepository repository = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    OutboxEvent event = new OutboxEvent("TransactionCompleted", "1", "{}");
    when(repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAt(
        org.mockito.ArgumentMatchers.eq(OutboxStatus.PENDING), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(event));
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
    OutboxPublisher publisher = new OutboxPublisher(repository, kafkaTemplate);
    ReflectionTestUtils.setField(publisher, "topic", "transactions");

    publisher.publishPending();

    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(event.getRetryCount()).isEqualTo(1);
  }
}
