# k6 이체 멱등성 부하 테스트

## 1. 문서 목적

이 문서는 여러 클라이언트가 같은 이체를 동시에 재시도하는 상황을 k6로 재현하고, DB 유니크 제약만 사용하는 경로와 Redis를 우선 조회하는 경로의 HTTP 응답 성능을 비교하는 방법을 정리한다.

서비스 메서드 실행시간을 측정하는 [RedisIdempotencyPerformanceIntegrationTest](../src/test/java/com/FinFlow/service/RedisIdempotencyPerformanceIntegrationTest.java)와 달리, k6 테스트는 로그인, JWT 인증 필터, JSON 직렬화, HTTP 연결을 포함한 전체 API 경로를 측정한다.

## 2. 테스트 시나리오

[idempotency-retry.js](../loadtest/k6/idempotency-retry.js)는 테스트 시작 시 한 번 로그인한 뒤, 모든 VU가 같은 `Idempotency-Key`와 같은 요청 본문으로 이체 API를 반복 호출한다.

```text
setup: POST /api/login → JWT 획득
  ↓
20 VU가 30초 동안 반복
  ↓
POST /api/s/account/transfer
Idempotency-Key: 모든 VU가 같은 값 사용
  ↓
최초 1건만 이체되고 나머지는 최초 거래 결과 반환
```

기본 설정은 다음과 같다.

| 항목 | 기본값 | 환경변수 |
| --- | --- | --- |
| 애플리케이션 주소 | `http://host.docker.internal:8081` | `K6_BASE_URL` |
| 가상 사용자 | 20 VU | `K6_VUS` |
| 실행 시간 | 30초 | `K6_DURATION` |
| 로그인 사용자 | `test` | `K6_USERNAME` |
| 로그인 비밀번호 | `1234` | `K6_PASSWORD` |
| 테스트 구분 | `redis` | `K6_MODE` |
| 멱등성 키 | `k6-idempotency-retry` | `K6_IDEMPOTENCY_KEY` |

## 3. 사전 준비

MySQL과 Redis를 실행한다.

```bash
docker compose up -d mysql redis
```

테스트 계정과 두 계좌가 필요하므로 애플리케이션은 `dev,mysql` 프로필 순서로 실행한다. `dev` 프로필이 더미 데이터를 생성하고, 마지막 `mysql` 프로필이 데이터소스를 Docker MySQL로 덮어쓴다.

```bash
SPRING_PROFILES_ACTIVE=dev,mysql \
SPRING_JPA_SHOW_SQL=false \
./gradlew bootRun
```

`SPRING_JPA_SHOW_SQL=false`는 요청마다 SQL 전체를 콘솔에 출력하는 비용이 부하 테스트 결과를 왜곡하지 않도록 한다.

IntelliJ에서는 Spring Boot 실행 구성의 Active profiles에 다음 값을 입력한다.

```text
dev,mysql
```

Environment variables에는 다음 값을 추가한다.

```text
SPRING_JPA_SHOW_SQL=false
```

기본 테스트 데이터는 다음과 같다.

| 구분 | 값 |
| --- | --- |
| 사용자 | `test` / `1234` |
| 출금 계좌 | `1111111111`, 비밀번호 `1234`, 잔액 1,000원 |
| 입금 계좌 | `2222222222` |

애플리케이션 시작 시 `ddl-auto: create-drop`으로 테이블을 다시 만들기 때문에 각 비교 실행은 동일한 초기 상태에서 시작할 수 있다.

## 4. Redis 적용 전: DB-only 실행

애플리케이션 실행 구성에 다음 환경변수를 추가하고 서버를 재시작한다.

```text
FINFLOW_IDEMPOTENCY_REDIS_ENABLED=false
```

이 경로에서는 중복 요청마다 MySQL INSERT가 유니크 제약에 걸리고, 실패한 트랜잭션을 롤백한 뒤 최초 거래를 조회한다.

k6를 실행한다.

```bash
K6_MODE=db-only \
K6_IDEMPOTENCY_KEY=k6-db-only \
docker compose --profile loadtest run --rm k6
```

## 5. Redis 적용 후 실행

환경변수를 변경하고 애플리케이션을 재시작한다.

```text
FINFLOW_IDEMPOTENCY_REDIS_ENABLED=true
```

Redis에 이전 측정값이 남아 측정에 영향을 주지 않도록 다른 키를 사용한다.

```bash
K6_MODE=redis \
K6_IDEMPOTENCY_KEY=k6-redis \
docker compose --profile loadtest run --rm k6
```

## 6. 결과 확인

각 실행은 터미널에 다음 지표를 출력한다.

| 지표 | 의미 |
| --- | --- |
| `avg` | 전체 요청 평균 응답시간 |
| `med` / p50 | 절반의 요청이 이 시간 안에 완료 |
| p95 | 95%의 요청이 이 시간 안에 완료 |
| p99 | 99%의 요청이 이 시간 안에 완료 |
| `max` | 가장 느린 요청의 응답시간 |
| `http_reqs` | 처리한 전체 HTTP 요청 수 |
| `http_req_failed` | HTTP 실패율 |
| `checks` | 상태 코드와 응답 본문 검증 성공률 |

JSON 원본은 다음 위치에 저장된다.

```text
loadtest/k6/results/db-only-summary.json
loadtest/k6/results/redis-summary.json
```

두 결과에서는 평균만 비교하지 않고 p95, p99, 실패율, 초당 처리량을 함께 확인한다. 로컬 Docker 결과는 JVM 상태, 로그 출력, CPU 사용률의 영향을 받으므로 최소 3회 이상 반복하고 중앙값을 사용하는 것이 좋다.

## 7. 테스트 강도 변경

VU와 실행 시간을 환경변수로 조절할 수 있다.

```bash
K6_MODE=redis \
K6_VUS=50 \
K6_DURATION=1m \
K6_IDEMPOTENCY_KEY=k6-redis-50vu \
docker compose --profile loadtest run --rm k6
```

단계적으로 부하를 올릴 때는 10, 20, 50 VU 순서로 실행하고 각 단계에서 다음 항목을 확인한다.

- p95와 p99의 증가 폭
- `http_req_failed`와 check 실패 여부
- 초당 처리량이 더 이상 증가하지 않는 지점
- 애플리케이션 HikariCP 커넥션 풀 대기
- MySQL CPU와 커넥션 수
- Redis 명령 처리량과 메모리 사용량

## 8. 결과 해석 시 주의사항

이 시나리오는 이미 완료된 하나의 이체에 재시도가 집중되는 특수 상황을 측정한다. 서로 다른 이체 키가 계속 생성되는 일반 트래픽이나 여러 계좌에 대한 실제 이체 처리량을 대표하지 않는다.

Redis 경로도 응답 원본을 MySQL에서 조회하므로 DB 읽기 부하는 남는다. Redis의 목적은 중복 INSERT, 유니크 예외, 트랜잭션 롤백 비용을 제거하는 것이며 MySQL을 완전히 우회하는 것은 아니다.

정확성 확인을 위해 테스트 후 다음 조건도 함께 검증해야 한다.

```text
account_transaction: 테스트 키별 거래 1건
idempotency_record:  테스트 키별 레코드 1건
출금 계좌: 테스트 키 하나당 1원 감소
입금 계좌: 테스트 키 하나당 1원 증가
```
