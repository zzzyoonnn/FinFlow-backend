package com.FinFlow.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.FinFlow.domain.OutboxEvent;
import com.FinFlow.domain.OutboxStatus;
import com.FinFlow.repository.OutboxEventRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:outbox-repository;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OutboxEventRepositoryTests {
  @Autowired
  private OutboxEventRepository repository;

  @Test
  void locksOnlyPublishablePendingEvents() {
    OutboxEvent event = repository.saveAndFlush(
        new OutboxEvent("TransactionCompleted", "1", "{}"));

    assertThat(repository.lockPublishableBatch(OutboxStatus.PENDING.name(), LocalDateTime.now()))
        .extracting(OutboxEvent::getEventId)
        .containsExactly(event.getEventId());
  }
}
