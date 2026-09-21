# Kafka Outbox 운영 준비

## 자동 통합 테스트

`KafkaOutboxIntegrationTest`는 Testcontainers의 MySQL 8.4와 Kafka 3.9.1을 사용한다.
기본 빌드에서는 Docker 의존 테스트를 건너뛰며, 운영 전 검증에서는 명시적으로 켠다.

```bash
RUN_KAFKA_INTEGRATION_TESTS=true ./gradlew integrationTest \
  --tests com.FinFlow.event.KafkaOutboxIntegrationTest
```

검증 항목은 다음과 같다.

- DB 트랜잭션 롤백 시 Outbox 행과 Kafka 후속 결과가 생성되지 않음
- 커밋된 Outbox만 발행되고 `PUBLISHED`로 전이됨
- 같은 `eventId`를 두 번 전달해도 감사 로그와 `processed_event`가 각각 한 건임
- 재시도 가능한 Consumer DB 오류가 최초 처리와 2회 재전달 후 DLQ로 이동함
- Kafka 컨테이너 중단 중 이벤트가 `PENDING`에 남고 재기동 후 발행·소비됨

테스트는 실제 Docker 컨테이너를 중지하고 다시 시작하므로 공유 Kafka가 아닌 격리된
Testcontainers 브로커에서만 실행한다.

## 운영 마이그레이션

운영 프로필은 Flyway를 활성화하고 Hibernate를 `validate` 모드로 실행한다.
초기 업무 스키마는 `V1__initial_schema.sql`, Outbox·Consumer 스키마는
`V2__event_outbox_schema.sql`에 있다. 기존 비-Flyway DB는 V1로 baseline한 뒤 V2부터
적용하며, 신규 DB에는 V1과 V2가 순서대로 적용된다.

```bash
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
```

배포 순서는 Flyway 성공, 애플리케이션 readiness 성공, 트래픽 전환 순이다. 마이그레이션
실패 시 애플리케이션을 기동하지 않는다. 새 마이그레이션은 기존 파일을 수정하지 않고
`V2__...sql`처럼 추가한다.

## 메트릭과 알람

Actuator의 `/actuator/prometheus`에서 다음 지표를 수집한다.

| 지표 | 의미 | 시작 알람 기준 |
| --- | --- | --- |
| `finflow_outbox_pending` | 미발행 Outbox 적체 | 5분 연속 증가 또는 평시의 3배 |
| `finflow_outbox_failed` | 최대 재시도 초과 Outbox | 1건 이상 |
| `finflow_outbox_publish_failures_total` | Kafka 발행 실패 시도 | 5분 증가량 > 0 |
| `finflow_consumer_failures_total` | Consumer 실패/재전달 횟수 | 5분 오류율 1% 초과 |
| `finflow_consumer_dlq_total` | DLQ 이동 건수 | 1건 이상 |
| `finflow_consumer_duplicates_total` | 중복 전달 차단 건수 | 급증 시 producer/offset 조사 |
| `kafka_consumer_*records_lag*` | Kafka client lag | 복구 목표 시간으로 환산해 설정 |

Consumer lag 이름은 Micrometer/Prometheus 버전에 따라 suffix가 조금 다를 수 있으므로
`/actuator/metrics`에서 `kafka.consumer` prefix를 확인한 뒤 대시보드 쿼리를 고정한다.
애플리케이션 health endpoint는 `/actuator/health`; 외부에는 health와 prometheus만 망
정책으로 허용한다.

장애 대응 순서는 Kafka 상태 확인, Outbox `PENDING` 증가 확인, Consumer lag 확인, DLQ
payload와 예외 헤더 확인이다. DLQ 재처리는 원인 수정 후 원래 topic으로 동일 key와
payload를 재발행한다. `eventId` 멱등성 때문에 이미 성공한 후속 작업은 건너뛴다.

## 부하 테스트와 파티션 수

`transaction-benchmark.js`의 `ramp`로 포화 전 단일 파티션 처리량과 Consumer 평균 처리
시간을 측정한다. API 처리량만으로 파티션 수를 정하지 않고, producer와 consumer 중 더
큰 요구량을 선택한다.

```bash
./loadtest/benchmark/run-load.sh ramp outbox 5

PEAK_RPS=1000 \
MEASURED_PARTITION_RPS=250 \
CONSUMER_PROCESSING_MS=10 \
TARGET_UTILIZATION=0.7 \
PARTITION_HEADROOM=1.3 \
./loadtest/benchmark/calculate-partitions.sh
```

산식은 다음과 같다.

```text
계획 처리량 = peak RPS × headroom
producer 기준 = ceil(계획 처리량 / (단일 partition 실측 RPS × 목표 사용률))
consumer 기준 = ceil(계획 처리량 / ((1000 / 평균 처리 ms) × 목표 사용률))
권장 partition = max(producer 기준, consumer 기준)
```

예시 입력의 결과는 consumer 처리시간이 병목이어서 19 partitions다. 이는 기본값이
아니라 실측값을 넣은 계산 예시다.
partition 증가는 되돌리기 어렵고 key ordering 분포가 바뀔 수 있으므로, 운영 생성 전
피크·스파이크·브로커 재기동 복구 테스트를 각각 5회 실행하고 중앙값으로 확정한다.
복제 계수는 운영 Kafka에서 3, `min.insync.replicas=2`, producer `acks=all`을 권장한다.
