# Kafka 이벤트 처리 테스트 가이드

## 1. 문서 목적

FinFlow의 Kafka 관련 테스트는 빠른 Mockito/H2 테스트와 실제 MySQL·Kafka 장애 통합
테스트의 두 계층으로 구성된다. 이 문서의 2~6절은 빠른 테스트를 설명하며, 실제 브로커
검증은 7절과 [운영 준비 문서](operations-readiness.md)를 따른다.

이 문서는 다음 내용을 구분한다.

- 각 테스트 클래스가 검증하는 책임
- 단위 테스트와 DB 트랜잭션 테스트의 차이
- `test`와 `integrationTest` Gradle 태스크의 구분
- 현재 테스트가 보장하지 않는 범위

## 2. 테스트 대상 흐름

```text
거래와 Outbox 이벤트 DB 커밋
  → Outbox Publisher가 PENDING 이벤트 조회
  → Kafka 발행 성공 또는 실패 상태 반영
  → Consumer가 거래 완료 이벤트 역직렬화·검증
  → eventId 중복 확인
  → 감사 로그와 processed_event를 같은 DB 트랜잭션으로 저장
  → Listener 정상 반환 후 Kafka offset 커밋
```

현재 자동 테스트는 이 흐름을 하나의 실제 Kafka E2E 테스트로 실행하지 않는다. 각
컴포넌트의 책임을 작은 테스트로 분리해 검증한다.

## 3. 테스트 클래스별 의도

| 테스트 클래스 | 종류 | 주요 검증 대상 | 실제 Kafka 필요 여부 |
| --- | --- | --- | --- |
| `TransactionEventConsumerTests` | Mockito 단위 테스트 | 역직렬화, 이벤트 검증, Processor 위임 | 불필요 |
| `TransactionAuditEventProcessorTests` | H2 JPA 슬라이스 테스트 | 멱등 처리, 두 테이블 저장, 트랜잭션 롤백 | 불필요 |
| `OutboxPublisherTests` | Mockito 단위 테스트 | 발행 성공, 재시도 예약, 최종 실패 격리 | 불필요 |
| `OutboxEventRepositoryTests` | H2 JPA 슬라이스 테스트 | 발행 가능한 PENDING 이벤트 조회 | 불필요 |

### 3.1 `TransactionEventConsumerTests`

Consumer의 메시지 경계를 검증한다. Repository에 실제로 저장되는지는 이 테스트의
책임이 아니다.

- 올바른 이벤트를 `TransactionAuditEventProcessor`에 전달한다.
- Processor가 중복으로 판단한 경우에도 Listener가 정상 종료된다.
- 잘못된 JSON을 `InvalidEventPayloadException`으로 거절한다.
- 지원하지 않는 `schemaVersion`을 거절한다.
- UUID가 아닌 `eventId`를 거절한다.
- 검증에 실패한 이벤트는 Processor에 전달하지 않는다.

Processor는 Mockito Mock이므로 MySQL, H2, Kafka에 연결하지 않는다.

### 3.2 `TransactionAuditEventProcessorTests`

검증이 끝난 이벤트의 DB 처리 결과를 H2에서 확인한다.

- 최초 이벤트 처리 시 감사 로그와 `processed_event`가 각각 한 건 저장된다.
- 같은 `eventId`를 다시 처리하면 두 테이블에 중복 레코드가 생기지 않는다.
- `processed_event` 저장이 실패하면 앞서 저장한 감사 로그도 롤백된다.

롤백 테스트는 길이가 40자인 이벤트 ID를 전달해 `processed_event.event_id VARCHAR(36)`
제약 위반을 발생시킨다. 최종적으로 두 테이블 모두 0건인지 확인해 원자성을 검증한다.

테스트 클래스에 다음 설정을 사용한다.

```java
@Transactional(propagation = Propagation.NOT_SUPPORTED)
```

`@DataJpaTest`의 기본 테스트 트랜잭션과 Processor 트랜잭션이 합쳐지는 것을 막는다.
따라서 Processor 메서드가 만든 트랜잭션의 실제 커밋 및 롤백 결과를 메서드 호출 이후에
조회할 수 있다.

### 3.3 `OutboxPublisherTests`

Kafka와 Repository를 Mock으로 대체하고 Publisher의 상태 전이를 검증한다.

- Kafka가 발행을 확인하면 Outbox 상태를 `PUBLISHED`로 변경한다.
- 발행에 실패하면 `retryCount`를 증가시키고 다음 재시도를 예약한다.
- 최대 발행 횟수에 도달하면 상태를 `FAILED`로 변경하고 오류 정보를 남긴다.

이 테스트에서 `KafkaTemplate.send()`는 Mock이다. 실제 브로커 연결, 직렬화, 파티션
할당 또는 Kafka acknowledgement 자체를 검증하지는 않는다.

### 3.4 `OutboxEventRepositoryTests`

H2를 MySQL 호환 모드로 실행해 발행 가능한 `PENDING` 이벤트 조회 쿼리를 검증한다.

이 테스트는 기본적인 쿼리 동작을 빠르게 확인하기 위한 것이다. MySQL의 실제 락 동작이나
여러 Publisher가 동시에 조회하는 상황까지 보장하지는 않는다.

## 4. 실행 방법

### Consumer 단위 테스트

```bash
./gradlew test \
  --tests com.FinFlow.event.TransactionEventConsumerTests
```

### 감사 로그 Processor 트랜잭션 테스트

```bash
./gradlew test \
  --tests com.FinFlow.service.TransactionAuditEventProcessorTests
```

### Outbox Publisher 테스트

```bash
./gradlew test \
  --tests com.FinFlow.event.OutboxPublisherTests
```

### Outbox Repository 테스트

```bash
./gradlew test \
  --tests com.FinFlow.event.OutboxEventRepositoryTests
```

### Kafka 이벤트 처리 관련 테스트 전체 실행

```bash
./gradlew test \
  --tests 'com.FinFlow.event.*' \
  --tests com.FinFlow.service.TransactionAuditEventProcessorTests \
  --tests com.FinFlow.service.TransactionAuditServiceTests
```

위 테스트에는 Docker, MySQL, Redis 또는 Kafka 실행이 필요하지 않다.

## 5. `integrationTest`로 실행하면 안 되는 이유

현재 `build.gradle`은 클래스 이름을 기준으로 테스트 태스크를 분리한다.

```gradle
tasks.named('test') {
    exclude '**/*IntegrationTest.class'
}

tasks.register('integrationTest', Test) {
    include '**/*IntegrationTest.class'
}
```

`TransactionEventConsumerTests`와 `TransactionAuditEventProcessorTests`는 클래스 이름이
`IntegrationTest`로 끝나지 않는다. 다음 명령은 대상 클래스가 필터에서 제외되므로
실패한다.

```bash
./gradlew integrationTest \
  --tests com.FinFlow.event.TransactionEventConsumerTests
```

발생하는 오류는 다음과 같다.

```text
No matching tests found in any candidate test task.
Requested tests:
    Test pattern com.FinFlow.event.TransactionEventConsumerTests
    in task :integrationTest
```

이 오류는 테스트 로직이나 Kafka 연결 실패가 아니다. 테스트 클래스와 Gradle 태스크가
일치하지 않아 발생한 실행 설정 오류다. IntelliJ에서도 Gradle Run Configuration의
태스크를 `integrationTest`가 아니라 `test`로 지정해야 한다.

## 6. 테스트가 증명하는 보장

현재 테스트로 확인할 수 있는 보장은 다음과 같다.

```text
유효하지 않은 이벤트
  → DB Processor 호출 안 함

최초 이벤트
  → 감사 로그 1건 + processed_event 1건

동일 이벤트 재처리
  → 감사 로그 중복 생성 안 함

processed_event 저장 실패
  → 감사 로그까지 함께 롤백

Outbox 발행 성공
  → PUBLISHED 상태 전이

Outbox 발행 실패
  → 재시도 또는 FAILED 상태 전이
```

Kafka의 at-least-once 전달을 전제로 하되, `eventId`와 DB 트랜잭션을 이용해 감사 로그의
업무 결과가 중복 반영되지 않도록 검증한다.

## 7. 실제 MySQL·Kafka 통합 테스트

`KafkaOutboxIntegrationTest`가 다음 항목을 실제 컨테이너로 검증한다.

- 실제 Kafka 브로커를 통한 생산·소비 E2E 흐름
- DB 롤백 시 Outbox 미생성 및 커밋 이벤트만 발행
- 동일 이벤트 중복 전달의 단일 후속 처리
- `DefaultErrorHandler`의 실제 재전달 횟수와 DLQ 발행
- Kafka 중단 후 `PENDING` Outbox 재발행

```bash
RUN_KAFKA_INTEGRATION_TESTS=true ./gradlew integrationTest \
  --tests com.FinFlow.event.KafkaOutboxIntegrationTest
```

Consumer 프로세스를 offset commit 직전에 강제 종료하는 테스트와 다중 Publisher의
`SKIP LOCKED` 경쟁 테스트는 아직 수동 chaos 검증 범위다. lag와 처리량은 Actuator
Prometheus 지표 및 k6 하네스로 측정한다.

## 8. 테스트 선택 기준

| 확인하려는 내용 | 적합한 테스트 |
| --- | --- |
| JSON과 이벤트 필드 검증 | Consumer Mockito 단위 테스트 |
| 감사 로그와 처리 기록의 저장 결과 | Processor H2 JPA 테스트 |
| DB 롤백 원자성 | Processor H2 JPA 테스트 |
| Publisher 상태 전이 | Publisher Mockito 단위 테스트 |
| Repository 기본 쿼리 | H2 JPA 테스트 |
| Kafka 재시도·DLQ·offset 동작 | 실제 Kafka 통합 테스트 |
| MySQL 락과 동시 Publisher | 실제 MySQL 통합 테스트 |

빠른 피드백은 `test` 태스크가 담당하고, 브로커·DB 제품별 동작 검증은 별도의
`integrationTest` 태스크가 담당하도록 구분한다.
