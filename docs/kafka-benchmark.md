# Kafka·Outbox 비교 테스트 환경

## 1. 목적

이 환경은 동일한 이체와 감사 로그 업무를 다음 처리 방식에서 비교한다.

| 모드 | API 트랜잭션 | 후속 처리 |
| --- | --- | --- |
| `sync` | 이체와 감사 로그를 함께 저장 | 없음 |
| `direct` | 이체 커밋 후 Kafka 직접 발행 | 다음 구현 단계에서 추가 |
| `outbox` | 이체와 Outbox 이벤트를 함께 저장 | Kafka Consumer가 감사 로그 저장 |

모든 모드는 `account_transaction` 한 건당 `transaction_audit_log` 한 건을 최종 결과로
만든다. 따라서 응답시간뿐 아니라 장애 중 데이터 유실과 복구 결과를 같은 기준으로
비교할 수 있다.

현재 하네스는 `sync`와 `outbox`를 지원한다. `direct` 모드는 DB와 Kafka의 이중 쓰기
문제를 재현할 구현을 추가한 뒤 같은 하네스에 연결한다.

## 2. 고정 테스트 조건

`benchmark` 프로필은 다음 데이터를 생성한다.

- 사용자: `benchmark` / `1234`
- 출금 계좌: `7100000000`부터 20개
- 입금 계좌: `7200000000`부터 20개
- 계좌별 초기 잔액: 10억 원
- 이체 금액: 요청당 1원
- Redis 멱등성 캐시: 비활성화
- SQL 로그: 비활성화

20개 계좌 쌍은 하나의 계좌 락만 측정하는 실험이 되지 않도록 요청을 분산한다.
동일 계좌 집중 성능은 별도 시나리오로 측정해야 한다.

## 3. 인프라 시작

모든 비교에서 컨테이너 실행 조건을 같게 유지한다.

```bash
docker compose up -d mysql redis kafka
docker compose ps
```

### 동기 감사 로그 모드

```bash
SPRING_PROFILES_ACTIVE=mysql,benchmark \
AUDIT_MODE=sync \
OUTBOX_ENABLED=false \
KAFKA_ENABLED=false \
./gradlew bootRun
```

### Transactional Outbox 모드

```bash
SPRING_PROFILES_ACTIVE=mysql,benchmark \
AUDIT_MODE=kafka \
OUTBOX_ENABLED=true \
KAFKA_ENABLED=true \
./gradlew bootRun
```

애플리케이션 시작 시 `create-drop`으로 스키마와 기준 데이터를 다시 만든다. 각 반복
측정 전에 애플리케이션을 재시작해야 이전 거래가 다음 결과에 섞이지 않는다.

## 4. 부하 시나리오

스크립트는 모든 요청에 고유한 `Idempotency-Key`를 사용한다. 결과 JSON은
`loadtest/k6/results`에 생성된다.

### 일정 부하

```bash
BENCHMARK_TARGET_RPS=100 \
K6_DURATION=2m \
./loadtest/benchmark/run-load.sh normal sync 1
```

마지막 인자는 반복 횟수다. 공식 비교에서는 각 버전을 최소 5회 실행하고 중앙값을
사용한다.

### 단계 증가

기본 목표는 50, 100, 200, 400 TPS이며 각 단계는 1분이다.

```bash
./loadtest/benchmark/run-load.sh ramp sync 1
```

`BENCHMARK_START_RPS`, `RAMP_RPS_1`부터 `RAMP_RPS_4`, 각 단계의
`RAMP_DURATION_1`부터 `RAMP_DURATION_4`로 값을 바꿀 수 있다.

### 순간 피크

기본값은 평상시 50 TPS, 피크 500 TPS다.

```bash
BENCHMARK_STEADY_RPS=50 \
BENCHMARK_SPIKE_RPS=500 \
./loadtest/benchmark/run-load.sh spike sync 1
```

각 실행은 요청 수, 실제 처리량, 평균, p50, p95, p99와 실패율을 출력한다.

## 5. 정합성 지표 수집

부하 실행 후 다음 명령으로 DB 상태를 CSV에 저장한다.

```bash
./loadtest/benchmark/collect-metrics.sh \
  loadtest/k6/results/sync-normal-database-metrics.csv
```

수집 항목은 다음과 같다.

- 전체 거래 수와 감사 로그 수
- 감사 로그 완료율
- 데이터 유실률
- 누락 및 중복 감사 로그 수
- `PENDING`, `PUBLISHED` Outbox 수
- 처리 완료 이벤트 수

정의는 다음과 같다.

```text
감사 로그 완료율 = 감사 로그 수 / 커밋된 거래 수 × 100
데이터 유실률 = 감사 로그가 없는 거래 수 / 커밋된 거래 수 × 100
```

## 6. 감사 저장소 장애

이 시나리오는 감사 로그 테이블의 이름을 일시적으로 변경해 감사 저장 실패를
주입한다. 스크립트 종료 또는 인터럽트 시 원래 이름으로 복구한다.

```bash
BENCHMARK_TARGET_RPS=100 \
BENCHMARK_DURATION=30s \
./loadtest/benchmark/run-audit-outage.sh sync
```

동기 모드에서는 감사 로그 실패가 이체 트랜잭션까지 롤백시키므로 요청 실패율이
증가할 것으로 예상한다. Outbox 모드에서는 API 거래 완료 여부와 Consumer 재시도 후
최종 누락 건수를 측정한다. 현재 Consumer 재시도·DLQ 정책의 부족도 이 실험 결과로
확인할 수 있다.

## 7. Kafka 장애와 복구 시간

Outbox 모드 애플리케이션이 실행 중일 때 사용한다.

```bash
BENCHMARK_TARGET_RPS=100 \
BENCHMARK_DURATION=30s \
RECOVERY_TIMEOUT_SECONDS=300 \
./loadtest/benchmark/run-kafka-outage.sh
```

스크립트는 다음 순서로 동작한다.

1. Kafka 중단
2. Kafka가 없는 상태에서 이체 부하 실행
3. Kafka 재시작
4. `거래 수 = 감사 로그 수`, `PENDING Outbox = 0`이 될 때까지 측정
5. 복구 타임라인과 최종 DB 지표를 CSV로 저장

복구 시간은 Kafka 재시작 명령 이후 모든 backlog가 처리되기까지 걸린 시간이다.
재처리 성공률은 최종 감사 로그 완료율로 평가하며, 중복 업무 반영은
`duplicate_audits`로 확인한다.

## 8. 결과 파일

```text
loadtest/k6/results/<run-id>-summary.json
loadtest/k6/results/<mode>-<fault>-database-metrics.csv
loadtest/k6/results/outbox-kafka-outage-recovery.csv
```

결과 디렉터리는 Git에서 제외한다. 공식 결과를 문서에 포함할 때는 원본 파일을 별도
보관하고 반복 실행의 중앙값, 머신 사양, JVM 옵션과 실행 시각을 함께 기록한다.

## 9. 공정한 비교 체크리스트

- 동일 Git 테스트 하네스 사용
- 동일 Docker 이미지와 CPU·메모리 제한
- 동일 JVM heap과 커넥션 풀
- 각 실행 전 애플리케이션 재시작
- 30초 이상 워밍업 후 본 측정
- sync와 outbox 실행 순서를 번갈아 배치
- 각 조건 최소 5회 반복
- 성능과 정합성 결과를 함께 기록
- 부하 실행 중 다른 로컬 작업 최소화

현재 작업 디렉터리의 Kafka 변경을 되돌리지 않고도 `OUTBOX_ENABLED=false`와
`KAFKA_ENABLED=false`로 동기 기준을 측정할 수 있다. 다만 최종 보고서에서는 Kafka
코드가 전혀 없는 기준 커밋에서도 같은 하네스를 실행해 결과가 일치하는지 확인한다.
