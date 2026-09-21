package com.FinFlow.event;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.FinFlow.domain.OutboxEvent;
import com.FinFlow.domain.OutboxStatus;
import com.FinFlow.repository.OutboxEventRepository;
import com.FinFlow.repository.ProcessedEventRepository;
import com.FinFlow.repository.TransactionAuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RUN_KAFKA_INTEGRATION_TESTS", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaOutboxIntegrationTest {

  private static final String TOPIC = "finflow.integration." + UUID.randomUUID();

  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("finflow").withUsername("finflow").withPassword("finflow");

  @Container
  static final KafkaContainer KAFKA = new KafkaContainer(
      DockerImageName.parse("apache/kafka-native:3.9.1"));

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
    properties.add("spring.datasource.username", MYSQL::getUsername);
    properties.add("spring.datasource.password", MYSQL::getPassword);
    properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    properties.add("spring.flyway.enabled", () -> "false");
    properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    properties.add("spring.kafka.consumer.group-id", () -> "finflow-it-" + UUID.randomUUID());
    properties.add("spring.kafka.producer.properties." + ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
        () -> "3000");
    properties.add("spring.kafka.producer.properties." + ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
        () -> "1000");
    properties.add("finflow.kafka.enabled", () -> "true");
    properties.add("finflow.kafka.transaction-topic", () -> TOPIC);
    properties.add("finflow.kafka.outbox-poll-interval", () -> "600000");
    properties.add("finflow.kafka.consumer.retry-interval", () -> "100");
    properties.add("finflow.kafka.consumer.max-retries", () -> "2");
    properties.add("finflow.audit.mode", () -> "none");
    properties.add("finflow.idempotency.redis-enabled", () -> "false");
  }

  @Autowired OutboxEventRepository outboxRepository;
  @Autowired ProcessedEventRepository processedRepository;
  @Autowired TransactionAuditLogRepository auditRepository;
  @Autowired TransactionTemplate transactionTemplate;
  @Autowired OutboxPublisher publisher;
  @Autowired KafkaTemplate<String, String> kafkaTemplate;
  @Autowired ObjectMapper objectMapper;
  @Autowired MeterRegistry meterRegistry;

  @AfterEach
  void cleanDatabase() {
    processedRepository.deleteAll();
    auditRepository.deleteAll();
    outboxRepository.deleteAll();
  }

  @Test
  @Order(1)
  void rollbackCreatesNoEventAndOnlyCommittedOutboxIsPublished() throws Exception {
    OutboxEvent rolledBack = outbox(101L);
    assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
      outboxRepository.save(rolledBack);
      throw new IllegalStateException("force rollback");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(outboxRepository.existsById(rolledBack.getEventId())).isFalse();
    assertThat(auditRepository.count()).isZero();

    OutboxEvent committed = outbox(102L);
    transactionTemplate.executeWithoutResult(status -> outboxRepository.save(committed));
    publisher.publishPending();

    await().atMost(15, SECONDS).untilAsserted(() -> {
      assertThat(outboxRepository.findById(committed.getEventId()).orElseThrow().getStatus())
          .isEqualTo(OutboxStatus.PUBLISHED);
      assertThat(auditRepository.count()).isEqualTo(1);
    });
  }

  @Test
  @Order(2)
  void duplicateDeliveryRunsDownstreamWorkOnlyOnce() throws Exception {
    TransactionCompletedEvent event = event(201L);
    String payload = objectMapper.writeValueAsString(event);

    kafkaTemplate.send(TOPIC, "201", payload).get(10, SECONDS);
    kafkaTemplate.send(TOPIC, "201", payload).get(10, SECONDS);

    await().atMost(15, SECONDS).untilAsserted(() -> {
      assertThat(auditRepository.count()).isEqualTo(1);
      assertThat(processedRepository.count()).isEqualTo(1);
    });
  }

  @Test
  @Order(3)
  void retryableConsumerFailureIsRedeliveredThenMovedToDlq() throws Exception {
    double failuresBefore = meterRegistry.counter("finflow.consumer.failures").count();
    TransactionCompletedEvent invalidForDatabase = new TransactionCompletedEvent(
        1, UUID.randomUUID().toString(), 301L, "X".repeat(21), "111", "222", 100L,
        LocalDateTime.now());

    kafkaTemplate.send(TOPIC, "301", objectMapper.writeValueAsString(invalidForDatabase))
        .get(10, SECONDS);

    try (KafkaConsumer<String, String> dlqConsumer = dlqConsumer()) {
      dlqConsumer.subscribe(java.util.List.of(TOPIC + ".dlq"));
      await().atMost(20, SECONDS).untilAsserted(() ->
          assertThat(dlqConsumer.poll(Duration.ofMillis(500)).count()).isGreaterThan(0));
    }

    assertThat(meterRegistry.counter("finflow.consumer.failures").count() - failuresBefore)
        .isGreaterThanOrEqualTo(3);
    assertThat(meterRegistry.counter("finflow.consumer.dlq").count()).isGreaterThanOrEqualTo(1);
    assertThat(auditRepository.count()).isZero();
    assertThat(processedRepository.count()).isZero();
  }

  @Test
  @Order(4)
  void pendingOutboxIsRecoveredAfterBrokerRestart() throws Exception {
    OutboxEvent event = outbox(401L);
    outboxRepository.saveAndFlush(event);

    String containerId = KAFKA.getContainerId();
    KAFKA.getDockerClient().stopContainerCmd(containerId).exec();
    try {
      publisher.publishPending();
      assertThat(outboxRepository.findById(event.getEventId()).orElseThrow().getStatus())
          .isEqualTo(OutboxStatus.PENDING);
    } finally {
      KAFKA.getDockerClient().startContainerCmd(containerId).exec();
    }

    await().pollInterval(Duration.ofSeconds(1)).atMost(30, SECONDS).untilAsserted(() -> {
      publisher.publishPending();
      assertThat(outboxRepository.findById(event.getEventId()).orElseThrow().getStatus())
          .isEqualTo(OutboxStatus.PUBLISHED);
      assertThat(auditRepository.count()).isEqualTo(1);
    });
  }

  private KafkaConsumer<String, String> dlqConsumer() {
    return new KafkaConsumer<>(Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ConsumerConfig.GROUP_ID_CONFIG, "dlq-verifier-" + UUID.randomUUID(),
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
  }

  private OutboxEvent outbox(long transactionId) throws Exception {
    TransactionCompletedEvent event = event(transactionId);
    return new OutboxEvent("TransactionCompleted", Long.toString(transactionId),
        objectMapper.writeValueAsString(event), event.eventId());
  }

  private TransactionCompletedEvent event(long transactionId) {
    return new TransactionCompletedEvent(1, UUID.randomUUID().toString(), transactionId,
        "TRANSFER", "1111111111", "2222222222", 100L, LocalDateTime.now());
  }
}
