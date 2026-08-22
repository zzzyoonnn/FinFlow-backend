# k6 이체 멱등성 부하 테스트

## 1. 문서 목적

이 문서는 여러 클라이언트가 같은 이체를 동시에 재시도하는 상황을 k6로 재현하고, DB 유니크 제약만 사용하는 경로와 Redis를 우선 조회하는 경로의 HTTP 응답 성능을 비교하는 방법을 정리한다.

서비스 메서드 실행시간을 측정하는 [RedisIdempotencyPerformanceIntegrationTest](../src/test/java/com/FinFlow/service/RedisIdempotencyPerformanceIntegrationTest.java)와 달리, k6 테스트는 로그인, JWT 인증 필터, JSON 직렬화, HTTP 연결을 포함한 전체 API 경로를 측정한다.

두 테스트의 측정 범위는 다음과 같이 구분한다.

| 테스트 | 측정 범위 | 주 용도 |
| --- | --- | --- |
| `RedisIdempotencyPerformanceIntegrationTest` | 서비스, JPA, DB/Redis | 저장소 경로 자체의 성능 차이 진단 |
| k6 | HTTP, JSON, Spring Security/JWT, MVC, 서비스, DB/Redis | 클라이언트가 체감하는 API 성능 비교 |

따라서 서비스 통합 테스트에서 측정한 아래 값이 k6 결과에 그대로 나타나지는 않는다.

| 경로 | 평균 | p50 | p95 |
| --- | ---: | ---: | ---: |
| DB-only | 5.036 ms | 5.151 ms | 6.227 ms |
| Redis 완료 응답 | 0.506 ms | 0.499 ms | 0.699 ms |

k6에서는 절대값보다 동일한 조건에서 측정한 DB-only와 Redis의 p95, p99, 실패율, 처리량 차이를 중심으로 비교한다.

## 2. 테스트 시나리오

Redis의 두 역할이 한 결과에 섞이지 않도록 테스트를 분리한다.

| 시나리오 | 스크립트 | 실행 방식 | 확인 대상 |
| --- | --- | --- | --- |
| DB-only retry | [idempotency-cache-hit.js](../loadtest/k6/idempotency-cache-hit.js) | 완료된 키를 20 VU가 30초 동안 반복 | DB 유니크 제약 기반 재시도 |
| Redis cache hit | [idempotency-cache-hit.js](../loadtest/k6/idempotency-cache-hit.js) | 완료된 키를 20 VU가 30초 동안 반복 | Redis 완료 응답 캐시 |
| Redis SET NX race | [idempotency-setnx-race.js](../loadtest/k6/idempotency-setnx-race.js) | 20 VU가 같은 새 키로 한 번씩 요청 | 최초 동시 요청 차단 |

캐시 적중 스크립트는 `setup`에서 먼저 이체를 완료한다. 이후 부하 구간에는 완료된 키의 재시도만 포함되므로 DB 조회 경로와 Redis 캐시 경로를 같은 조건으로 비교할 수 있다.

```text
setup: 로그인 → 새 키로 이체 1건 완료
  ↓
20 VU가 30초 동안 같은 완료 키 반복 호출
  ↓
DB-only: DB에서 최초 거래 조회
Redis:   캐시된 완료 응답 반환
```

SET NX race 스크립트는 사전 이체 없이 모든 VU가 같은 새 키로 한 번씩 요청한다. 한 요청만 `SET NX` 잠금을 획득해 DB 처리에 진입하고, 나머지는 완료 결과를 기다리는 구간을 측정한다.

```text
setup: 로그인만 수행
  ↓
20 VU가 같은 새 키로 동시에 1회씩 요청
  ↓
SET NX 획득 1건 → DB 이체
나머지 요청 → 진행 중 요청 대기 → 같은 완료 응답
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
| 실행 스크립트 | `idempotency-cache-hit.js` | `K6_SCRIPT` |
| race 최대 실행시간 | 30초 | `K6_MAX_DURATION` |

## 3. 사전 준비

프로젝트 루트에서 MySQL과 Redis를 실행한다. Docker Redis는 호스트의 `6380` 포트를 사용하며, 애플리케이션 기본 설정도 `localhost:6380`을 바라본다.

```bash
cd /Users/jiyoon/Documents/git/FinFlow
docker compose up -d mysql redis
docker compose ps
```

필요하면 Docker Redis 연결을 직접 확인한다.

```bash
docker compose exec redis redis-cli PING
```

정상이라면 `PONG`이 출력된다. k6 컨테이너가 Redis에 직접 연결하는 것은 아니며, k6가 호출한 애플리케이션이 Docker Redis를 사용한다.

테스트 계정과 두 계좌가 필요하므로 애플리케이션은 `dev,mysql` 프로필 순서로 실행한다. `dev` 프로필이 더미 데이터를 생성하고, 마지막 `mysql` 프로필이 데이터소스를 Docker MySQL로 덮어쓴다. DB-only와 Redis 실행 명령은 다음 절에서 각각 안내한다.

`SPRING_JPA_SHOW_SQL=false`는 요청마다 SQL 전체를 콘솔에 출력하는 비용이 부하 테스트 결과를 왜곡하지 않도록 한다.

IntelliJ에서는 Spring Boot 실행 구성의 Active profiles에 다음 값을 입력한다.

```text
dev,mysql
```

Environment variables에는 다음 값을 추가한다.

```text
SPRING_JPA_SHOW_SQL=false
FINFLOW_IDEMPOTENCY_REDIS_ENABLED=false 또는 true
```

기본 테스트 데이터는 다음과 같다.

| 구분 | 값 |
| --- | --- |
| 사용자 | `test` / `1234` |
| 출금 계좌 | `1111111111`, 비밀번호 `1234`, 잔액 1,000원 |
| 입금 계좌 | `2222222222` |

애플리케이션 시작 시 `ddl-auto: create-drop`으로 테이블을 다시 만들기 때문에 각 비교 실행은 동일한 초기 상태에서 시작할 수 있다.

## 4. Redis 적용 전: DB-only 실행

첫 번째 터미널에서 Redis 멱등성 계층을 비활성화한 애플리케이션을 실행한다.

```bash
SPRING_PROFILES_ACTIVE=dev,mysql \
SPRING_JPA_SHOW_SQL=false \
FINFLOW_IDEMPOTENCY_REDIS_ENABLED=false \
./gradlew bootRun
```

이 경로에서는 중복 요청마다 MySQL INSERT가 유니크 제약에 걸리고, 실패한 트랜잭션을 롤백한 뒤 최초 거래를 조회한다.

애플리케이션 시작이 완료되면 두 번째 터미널의 프로젝트 루트에서 k6를 실행한다.

```bash
cd /Users/jiyoon/Documents/git/FinFlow
K6_MODE=db-only \
K6_IDEMPOTENCY_KEY=k6-db-only \
K6_VUS=20 \
K6_DURATION=30s \
K6_SCRIPT=idempotency-cache-hit.js \
docker compose --profile loadtest run --rm k6
```

## 5. Redis 적용 후 실행

DB-only 애플리케이션을 종료한 뒤 Redis 멱등성 계층을 활성화해 다시 실행한다.

```bash
SPRING_PROFILES_ACTIVE=dev,mysql \
SPRING_JPA_SHOW_SQL=false \
FINFLOW_IDEMPOTENCY_REDIS_ENABLED=true \
./gradlew bootRun
```

Redis에 이전 측정값이 남아 측정에 영향을 주지 않도록 다른 키를 사용한다.

```bash
cd /Users/jiyoon/Documents/git/FinFlow
K6_MODE=redis \
K6_IDEMPOTENCY_KEY=k6-redis \
K6_VUS=20 \
K6_DURATION=30s \
K6_SCRIPT=idempotency-cache-hit.js \
docker compose --profile loadtest run --rm k6
```

`K6_MODE`는 k6 결과의 태그와 JSON 파일명을 구분할 뿐, 애플리케이션의 Redis 사용 여부를 바꾸지 않는다. 실제 경로 전환은 애플리케이션을 실행할 때 지정한 `FINFLOW_IDEMPOTENCY_REDIS_ENABLED` 값으로 결정된다.

## 6. Redis SET NX race 실행

Redis를 활성화한 애플리케이션을 그대로 실행한 상태에서 race 전용 스크립트를 선택한다. 이 테스트는 각 VU가 한 번만 요청하므로 `K6_DURATION` 대신 `K6_MAX_DURATION`을 사용한다.

```bash
cd /Users/jiyoon/Documents/git/FinFlow
K6_MODE=redis-setnx-race \
K6_IDEMPOTENCY_KEY=k6-setnx-race-001 \
K6_VUS=20 \
K6_MAX_DURATION=30s \
K6_SCRIPT=idempotency-setnx-race.js \
docker compose --profile loadtest run --rm k6
```

`K6_IDEMPOTENCY_KEY`는 race 실행마다 사용한 적 없는 값으로 바꾼다. 이미 완료된 키를 재사용하면 모든 요청이 캐시에 적중하므로 `SET NX` 경합을 측정할 수 없다.

## 7. 결과 확인

각 실행은 터미널에 다음 지표를 출력한다.

| 지표 | 의미 |
| --- | --- |
| `idempotent_transfer_latency`의 `avg` | 이체 요청의 평균 응답시간 |
| `idempotent_transfer_latency`의 `med` | 이체 요청의 중앙값(p50) |
| `idempotent_transfer_latency`의 `p(95)` | 이체 요청의 p95 |
| `idempotent_transfer_latency`의 `p(99)` | 이체 요청의 p99 |
| `setnx_race_latency` | SET NX race 요청의 응답시간 |
| `max` | 가장 느린 요청의 응답시간 |
| `http_reqs` | 처리한 전체 HTTP 요청 수 |
| `http_req_failed` | HTTP 실패율 |
| `checks` | 상태 코드와 응답 본문 검증 성공률 |

JSON 원본은 다음 위치에 저장된다.

```text
loadtest/k6/results/db-only-summary.json
loadtest/k6/results/redis-summary.json
loadtest/k6/results/redis-setnx-race-summary.json
```

두 결과에서는 평균만 비교하지 않고 p95, p99, 실패율, 초당 처리량을 함께 확인한다. 로컬 Docker 결과는 JVM 상태, 로그 출력, CPU 사용률의 영향을 받으므로 최소 3회 이상 반복하고 중앙값을 사용하는 것이 좋다.

결과는 다음 표처럼 기록한다.

| 경로 | avg | p50 | p95 | p99 | 요청/초 | 실패율 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| DB-only |  |  |  |  |  |  |
| Redis cache hit |  |  |  |  |  |  |

SET NX race는 캐시 성능표와 분리해 기록한다.

| VU | avg | p50 | p95 | p99 | 실패율 | 키당 실제 거래 수 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 20 |  |  |  |  |  | 1 |

공정하게 비교하려면 다음 조건을 동일하게 유지한다.

- `K6_VUS`와 `K6_DURATION`
- 로그인 계정과 이체 요청 본문
- Docker MySQL/Redis와 애플리케이션 실행 환경
- JVM 워밍업 시간과 SQL 로그 비활성화 설정
- 반복 실행 횟수

반면 각 실행의 `K6_IDEMPOTENCY_KEY`는 반드시 다르게 지정한다. 같은 키를 재사용하면 이전 실행에서 저장한 DB 또는 Redis 결과가 다음 측정에 섞일 수 있다.

## 8. 테스트 강도 변경

VU와 실행 시간을 환경변수로 조절할 수 있다.

```bash
K6_MODE=redis \
K6_VUS=50 \
K6_DURATION=1m \
K6_IDEMPOTENCY_KEY=k6-redis-50vu \
K6_SCRIPT=idempotency-cache-hit.js \
docker compose --profile loadtest run --rm k6
```

단계적으로 부하를 올릴 때는 10, 20, 50 VU 순서로 실행하고 각 단계에서 다음 항목을 확인한다.

- p95와 p99의 증가 폭
- `http_req_failed`와 check 실패 여부
- 초당 처리량이 더 이상 증가하지 않는 지점
- 애플리케이션 HikariCP 커넥션 풀 대기
- MySQL CPU와 커넥션 수
- Redis 명령 처리량과 메모리 사용량

SET NX race의 동시 요청 수는 `K6_VUS`만 변경한다. 각 VU의 반복 횟수는 항상 1로 유지한다.

```bash
K6_MODE=redis-setnx-race-50vu \
K6_VUS=50 \
K6_MAX_DURATION=30s \
K6_IDEMPOTENCY_KEY=k6-setnx-race-50vu-001 \
K6_SCRIPT=idempotency-setnx-race.js \
docker compose --profile loadtest run --rm k6
```

## 9. 결과 해석 시 주의사항

캐시 적중 시나리오는 이미 완료된 하나의 이체에 재시도가 집중되는 상황을 측정한다. SET NX race는 하나의 최초 이체에 동시 요청이 집중되는 상황을 측정한다. 두 시나리오 모두 서로 다른 이체 키가 계속 생성되는 일반 트래픽이나 여러 계좌에 대한 실제 이체 처리량을 대표하지 않는다.

Redis 완료 응답이 적중하면 MySQL 조회 없이 최초 응답을 바로 반환한다. 동시에 도착한 최초 요청은 `SET NX` 처리 잠금으로 하나만 DB 처리에 진입하고, 나머지는 완료 응답을 기다린다. Redis 장애나 대기 시간 초과 시에는 MySQL 유니크 제약 경로로 폴백한다.

정확성 확인을 위해 테스트 후 다음 조건도 함께 검증해야 한다.

```text
account_transaction: 테스트 키별 거래 1건
idempotency_record:  테스트 키별 레코드 1건
출금 계좌: 테스트 키 하나당 1원 감소
입금 계좌: 테스트 키 하나당 1원 증가
```

특히 SET NX race는 응답시간만으로 성공을 판단할 수 없다. 모든 요청의 transaction id가 동일하고, DB의 거래와 멱등성 레코드가 해당 키로 각각 정확히 1건인지 반드시 함께 확인한다. Redis 장애 또는 TTL 만료 상황에서도 DB 유니크 제약이 이 조건을 최종 보장해야 한다.
