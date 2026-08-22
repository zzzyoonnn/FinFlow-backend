# 이체 요청 멱등성: Idempotency-Key, DB 유니크 제약, Redis

## 1. 문서 목적

이 문서는 클라이언트가 네트워크 오류나 응답 유실로 동일한 이체를 재시도하더라도 거래가 한 번만 실행되도록 구현한 FinFlow의 멱등성 구조를 설명한다.

현재 멱등성 처리는 `POST /api/s/account/transfer`에 적용되어 있다. 구현의 핵심 원칙은 다음과 같다.

- `Idempotency-Key`로 논리적으로 동일한 요청을 식별한다.
- 요청 해시로 같은 키가 다른 이체에 재사용되는 것을 막는다.
- MySQL 유니크 제약을 최종 중복 방지 수단으로 사용한다.
- 멱등성 기록, 잔액 변경, 거래내역을 하나의 트랜잭션으로 처리한다.
- Redis는 완료된 중복 요청을 빠르게 판별하는 보조 계층으로 사용한다.
- Redis가 중단되거나 캐시가 유실되어도 DB만으로 정확성을 유지한다.

## 2. 해결하려는 문제

서버에서 이체가 정상적으로 커밋된 뒤 응답 전송 중 네트워크가 끊기면 클라이언트는 처리 결과를 알 수 없다.

```text
클라이언트 요청
  → 서버 이체 처리 및 DB 커밋
  → 응답 전송 중 네트워크 오류
  → 클라이언트가 동일 요청 재시도
```

보호 장치가 없다면 재시도를 새로운 이체로 처리해 출금이 두 번 발생할 수 있다. 멱등성 처리는 재시도를 최초 요청과 연결하고 이미 생성된 거래 결과를 반환한다.

## 3. API 계약

클라이언트는 새로운 이체마다 고유한 `Idempotency-Key`를 생성한다. UUID처럼 충돌 가능성이 낮은 값을 권장한다.

```http
POST /api/s/account/transfer HTTP/1.1
Authorization: Bearer {access-token}
Idempotency-Key: 76b14c41-8cd4-4b55-9588-c47bf6c356c9
Content-Type: application/json

{
  "withdrawNumber": "1111111111",
  "depositNumber": "2222222222",
  "withdrawPassword": 1234,
  "amount": 500,
  "transactionType": "TRANSFER"
}
```

| 상황 | 사용할 키 |
| --- | --- |
| 새로운 이체 | 새로운 키 |
| 응답을 받지 못해 같은 이체 재시도 | 최초 요청과 같은 키 |
| 금액, 계좌 등 요청 내용 변경 | 새로운 키 |

키는 공백일 수 없으며 최대 100자다. 헤더가 없으면 요청 매핑 단계에서 실패하고, 값이 비어 있거나 100자를 초과하면 비즈니스 예외로 거부한다.

최초 처리와 재시도는 모두 기존 API 계약에 따라 `201 Created`를 반환한다. 재시도 응답에는 최초 처리와 같은 거래 ID가 포함된다.

## 4. 전체 구성

| 구성 요소 | 역할 |
| --- | --- |
| [AccountController](../src/main/java/com/FinFlow/controller/AccountController.java) | 헤더와 요청 본문 수신, Redis 우선 서비스 호출 |
| [RedisIdempotentTransferService](../src/main/java/com/FinFlow/service/RedisIdempotentTransferService.java) | Redis 우선 조회, 캐시 미스·장애 시 DB 경로 호출 |
| [RedisIdempotencyCache](../src/main/java/com/FinFlow/service/RedisIdempotencyCache.java) | `SET NX` 처리 잠금과 완료 응답 캐시 관리 |
| [IdempotentTransferService](../src/main/java/com/FinFlow/service/IdempotentTransferService.java) | 키 검증, 요청 해시 계산, DB 중복 충돌 처리 |
| [TransferTransactionService](../src/main/java/com/FinFlow/service/TransferTransactionService.java) | 멱등성 레코드 선점, 계좌 잠금, 이체와 거래 저장 |
| [IdempotencyRecordRepository](../src/main/java/com/FinFlow/repository/IdempotencyRecordRepository.java) | 멱등성 레코드 저장 및 완료 결과 조회 |

```text
AccountController
  → RedisIdempotentTransferService
      ├─ 완료 응답 적중 → Redis 응답 즉시 반환
      └─ Redis 미스/장애 → IdempotentTransferService
                           → TransferTransactionService
                           → MySQL 트랜잭션 실행
```

## 5. 멱등성 기록 테이블

[IdempotencyRecord](../src/main/java/com/FinFlow/domain/IdempotencyRecord.java)는 최초 요청의 키와 요청 해시, 생성된 거래를 연결한다.

| 컬럼 | 제약 | 역할 |
| --- | --- | --- |
| `id` | Primary Key | 멱등성 레코드 식별자 |
| `idempotency_key` | `NOT NULL`, `VARCHAR(100)`, Unique | 클라이언트 요청 키 |
| `request_hash` | `NOT NULL`, `VARCHAR(64)` | 사용자와 요청 내용의 SHA-256 |
| `transaction_id` | Unique, FK | 최초 요청에서 생성된 거래 |
| `created_at` | `NOT NULL` | 최초 요청 처리 시각 |

핵심은 `idempotency_key`의 유니크 제약이다.

```java
@Table(
    name = "idempotency_record",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_idempotency_record_key",
        columnNames = "idempotency_key"
    )
)
```

애플리케이션의 사전 조회만으로는 두 요청이 동시에 키의 부재를 확인하는 경쟁 조건을 막을 수 없다. DB 유니크 인덱스는 동시 INSERT 중 하나만 허용하므로 최종 원자적 판정 지점이 된다.

```sql
CREATE TABLE idempotency_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    transaction_id BIGINT,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_record_key UNIQUE (idempotency_key),
    CONSTRAINT uk_idempotency_record_transaction UNIQUE (transaction_id),
    FOREIGN KEY (transaction_id) REFERENCES account_transaction(id)
);
```

개발 및 MySQL 통합 테스트 환경에서는 Hibernate가 이 제약을 생성한다. 운영 프로필은 `ddl-auto: none`이므로 운영 배포 시 별도 스키마 마이그레이션에 반드시 포함해야 한다.

## 6. 요청 해시

키가 같아도 요청 내용이 같다고 단정할 수 없다. 같은 키로 금액이나 계좌를 바꿔 요청했을 때 최초 결과를 그대로 반환하면 잘못된 요청을 성공으로 오인하게 된다.

[IdempotentTransferService](../src/main/java/com/FinFlow/service/IdempotentTransferService.java)는 다음 값을 구분자와 연결한 뒤 SHA-256 해시를 계산한다.

```text
사용자 ID + 출금 계좌번호 + 입금 계좌번호 + 출금 비밀번호
  + 금액 + 거래 유형
  → SHA-256 request_hash
```

사용자 ID도 포함하므로 다른 사용자가 같은 키로 기존 결과를 재사용할 수 없다. 저장된 해시와 현재 요청 해시가 다르면 `Idempotency-Key가 다른 이체 요청에 이미 사용되었습니다.` 오류로 거부한다.

계좌 비밀번호 원문은 DB 멱등성 테이블이나 Redis에 저장하지 않고 해시 계산 입력으로만 사용한다.

## 7. 최초 요청 처리

Redis와 DB에 키가 없는 최초 요청은 다음 순서로 처리한다.

```text
1. Idempotency-Key 형식 검증 및 요청 해시 계산
2. Redis 완료 응답 조회: 캐시 미스
3. 처리 키를 SET NX와 짧은 TTL로 선점
4. 멱등성 레코드 INSERT 및 saveAndFlush()
5. 두 계좌를 계좌번호 순서로 비관적 락
6. 소유자, 비밀번호, 잔액 검증
7. 출금·입금 계좌 잔액 변경
8. 거래내역 저장 및 멱등성 레코드에 거래 연결
9. MySQL 트랜잭션 커밋
10. Redis에 요청 해시와 완료 응답 저장
11. 소유자 토큰을 확인해 처리 키 해제
12. 거래 결과 응답
```

멱등성 레코드, 두 계좌 잔액, 거래내역은 하나의 MySQL 트랜잭션으로 처리한다. 어느 단계에서든 예외가 발생하면 모두 롤백되므로 실패한 키만 남거나 잔액만 변경되는 부분 성공을 방지한다.

Redis 완료 응답 저장은 DB 커밋 이후에 수행한다. 저장이 실패해도 거래는 MySQL에 안전하게 기록되어 있고, 다음 재시도는 DB 유니크 제약 경로로 처리된다. 처리 키 해제는 Lua 스크립트로 현재 값과 소유자 토큰을 비교하므로, TTL 만료 뒤 다른 요청이 획득한 잠금을 이전 처리자가 삭제할 수 없다.

## 8. 중복 요청 처리

### 완료 응답 캐시 적중

완료된 요청의 키가 Redis에 있으면 DB 접근 없이 최초 API 응답을 역직렬화해 즉시 반환한다.

```text
Redis에서 요청 해시와 완료 응답 조회
  → 현재 요청 해시와 비교
  → 최초 거래 결과 반환
```

요청 해시가 다르면 캐시된 응답을 반환하지 않고 키 재사용 오류로 거부한다.

### 진행 중 요청

완료 응답이 없으면 처리 키에 `SET NX`를 실행한다. 한 요청만 선점에 성공하고, 같은 키로 들어온 나머지 요청은 완료 응답이 생성될 때까지 기본 25ms 간격으로 최대 2초 동안 조회한다.

```text
요청 A: SET NX 성공 → DB 이체 → 완료 응답 캐시
요청 B: SET NX 실패 → 완료 응답 대기 → 같은 응답 반환
```

대기 시간이 끝나거나 처리자가 중단되면 DB-only 경로로 폴백한다. 이때 원래 요청이 커밋된 상태라면 DB 유니크 제약 충돌 뒤 기존 결과를 반환하므로 중복 이체는 발생하지 않는다.

### Redis 비활성화 또는 장애

`redis-enabled=false`이거나 Redis 명령이 실패하면 DB-only 경로를 사용한다.

```text
요청 A: 키 INSERT 성공 → 이체 처리 → 커밋
요청 B: 같은 키 INSERT → DB 유니크 충돌 → 트랜잭션 롤백
                                               ↓
                                      최초 완료 거래 조회
                                               ↓
                                      같은 거래 결과 반환
```

유니크 충돌이 발생한 트랜잭션은 실패 상태이므로 그 안에서 기존 결과를 조회하지 않는다. 트랜잭션 바깥의 [IdempotentTransferService](../src/main/java/com/FinFlow/service/IdempotentTransferService.java)가 `DataIntegrityViolationException`을 처리한 뒤 새로운 조회로 최초 결과를 반환한다.

[RedisIdempotencyCache](../src/main/java/com/FinFlow/service/RedisIdempotencyCache.java)는 Redis 조회·잠금·저장 중 `DataAccessException`이 발생하면 로그를 남기고 이 경로로 폴백한다.

## 9. 계층별 정합성 책임

| 보호 장치 | 책임 |
| --- | --- |
| `Idempotency-Key` | 클라이언트 재시도를 하나의 논리 요청으로 식별 |
| 요청 해시 | 동일 키의 다른 사용자·금액·계좌 요청 거부 |
| Redis `SET NX` | 같은 키의 동시 처리 요청을 진행 단계에서 1차 차단 |
| Redis 완료 응답 | 완료된 재시도에 최초 응답을 DB 조회 없이 반환 |
| DB 유니크 제약 | 캐시 상태와 무관하게 동시 중복 요청 최종 차단 |
| DB 트랜잭션 | 멱등성 기록, 두 잔액, 거래내역의 원자성 보장 |
| 계좌 비관적 락 | 서로 다른 키를 가진 동시 잔액 변경 직렬화 |

Redis는 정확성의 필수 구성 요소가 아니다. Redis를 끄거나 데이터가 사라져도 성능만 DB-only 수준으로 돌아가며 중복 이체 방지 기능은 유지된다.

## 10. Redis 구성

Docker Compose는 MySQL 8.4와 Redis 7.4를 제공한다.

로컬 Redis와 충돌하지 않도록 호스트에서는 `6380`을 사용하고, 컨테이너 내부 Redis는 기본 포트 `6379`를 사용한다. Spring Boot의 공통 기본값도 `localhost:6380`이므로 별도 환경변수가 없으면 Docker Redis에 연결된다.

```text
Spring Boot → localhost:6380 → Docker Redis:6379
로컬 Redis  → localhost:6379  (사용하지 않음)
```

```bash
docker compose up -d mysql redis
```

[application-mysql.yml](../src/main/resources/application-mysql.yml)의 기본 설정은 다음과 같다.

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6380}

finflow:
  idempotency:
    redis-enabled: true
    redis-ttl: 24h
    processing-ttl: 30s
    wait-timeout: 2s
    poll-interval: 25ms
```

```text
처리 key:   finflow:idempotency:transfer:{키 해시}:processing
처리 value: 요청 해시 + 소유자 UUID
처리 TTL:   30초

완료 key:   finflow:idempotency:transfer:{키 해시}:completed
완료 value: 요청 해시 + 직렬화된 이체 응답
완료 TTL:   24시간
```

`FINFLOW_IDEMPOTENCY_REDIS_ENABLED=false` 또는 `true`로 Redis 사용 여부를 전환할 수 있다.

## 11. 정확성 통합 테스트

[IdempotentTransferServiceIntegrationTest](../src/test/java/com/FinFlow/service/IdempotentTransferServiceIntegrationTest.java)는 Docker MySQL에서 다음을 검증한다.

| 시나리오 | 기대 결과 |
| --- | --- |
| 같은 키와 요청을 재시도 | 잔액 변경 1회, 거래 1건, 멱등성 기록 1건 |
| 동일 요청 재시도 응답 | 최초 요청과 동일한 거래 ID |
| 같은 키로 다른 금액 요청 | `CustomApiException`, 최초 거래만 유지 |

[RedisIdempotencyConcurrencyIntegrationTest](../src/test/java/com/FinFlow/service/RedisIdempotencyConcurrencyIntegrationTest.java)는 Redis가 포함된 다음 시나리오를 검증한다.

| 시나리오 | 기대 결과 |
| --- | --- |
| 같은 키의 동시 요청 8건 | 8건 성공, 동일 거래 ID, 실제 거래 1건 |
| DB 거래·멱등성 레코드 제거 후 재시도 | Redis 완료 응답 반환, DB 접근으로 새 거래를 만들지 않음 |

```bash
docker compose up -d mysql redis

./gradlew integrationTest \
  --tests "com.FinFlow.service.IdempotentTransferServiceIntegrationTest" \
  --tests "com.FinFlow.service.RedisIdempotencyConcurrencyIntegrationTest"
```

일반 `test` 태스크는 `*IntegrationTest`를 제외한다. IntelliJ에서도 기본 `:test`가 아니라 다음 Gradle 실행 구성을 사용한다.

```text
integrationTest --tests "com.FinFlow.service.IdempotentTransferServiceIntegrationTest"
```

## 12. 서비스 계층 성능 비교

[RedisIdempotencyPerformanceIntegrationTest](../src/test/java/com/FinFlow/service/RedisIdempotencyPerformanceIntegrationTest.java)는 동일 요청을 DB-only와 Redis 우선 경로로 각각 10회 워밍업한 뒤 100회씩 번갈아 호출한다.

| 경로 | 수행 작업 |
| --- | --- |
| DB-only | INSERT → 유니크 충돌 → 롤백 → 완료 거래 조회 |
| Redis 우선 | Redis 완료 응답 조회 및 역직렬화 |

2026-08-21 로컬 Docker 환경의 단일 실행 결과는 다음과 같다.

| 경로 | 평균 | p50 | p95 |
| --- | ---: | ---: | ---: |
| DB-only | 5.036 ms | 5.151 ms | 6.227 ms |
| Redis 우선 | 0.506 ms | 0.499 ms | 0.699 ms |

이 실행에서는 평균 응답시간이 약 89.94% 감소했다. 장비, JVM 워밍업, Docker 상태, 로그 출력에 따라 달라지는 참고값이며 운영 성능을 보장하지 않는다. 속도 차이는 테스트 성공 조건으로 사용하지 않고 측정 뒤 거래와 멱등성 레코드 수로 정확성을 검증한다.

```bash
./gradlew integrationTest \
  --tests "com.FinFlow.service.RedisIdempotencyPerformanceIntegrationTest" \
  --info
```

## 13. k6 HTTP 부하 테스트

[idempotency-retry.js](../loadtest/k6/idempotency-retry.js)는 로그인과 JWT 인증을 포함한 전체 HTTP 경로에서 여러 VU가 동일한 키를 반복 전송하는 상황을 측정한다.

기본 설정은 20 VU, 30초이며 평균, p50, p95, p99, 최대 응답시간, 처리량, 실패율을 출력한다. SQL 콘솔 출력은 결과를 크게 왜곡하므로 비활성화한다.

```bash
SPRING_PROFILES_ACTIVE=dev,mysql \
SPRING_JPA_SHOW_SQL=false \
FINFLOW_IDEMPOTENCY_REDIS_ENABLED=false \
./gradlew bootRun
```

프로젝트 루트에서 DB-only 테스트를 실행한다.

```bash
K6_MODE=db-only \
K6_IDEMPOTENCY_KEY=k6-db-only \
docker compose --profile loadtest run --rm k6
```

애플리케이션을 Redis 활성화 상태로 재시작한 뒤 다른 키로 실행한다.

```bash
K6_MODE=redis \
K6_IDEMPOTENCY_KEY=k6-redis \
docker compose --profile loadtest run --rm k6
```

```text
loadtest/k6/results/db-only-summary.json
loadtest/k6/results/redis-summary.json
```

상세 실행법과 지표 해석은 [k6-load-test.md](k6-load-test.md)에 정리했다.

| 테스트 | 포함 범위 |
| --- | --- |
| 성능 통합 테스트 | 서비스, JPA, MySQL, Redis |
| k6 부하 테스트 | HTTP, JSON, 보안 필터, JWT, 서비스, MySQL, Redis |

## 14. 현재 구조의 한계

- 진행 중 요청은 최대 2초 동안 현재 서블릿 스레드를 점유하며 폴링한다.
- 처리 시간이 `processing-ttl`을 넘으면 다른 요청이 새 처리 잠금을 획득할 수 있지만 DB 유니크 제약이 중복 이체를 최종 차단한다.
- 완료 응답 DTO 구조가 바뀌면 이전 Redis JSON과의 역직렬화 호환성을 고려해야 한다.
- 현재 응답만으로 최초 실행과 재시도 응답을 구분할 수 없다.
- 멱등성 레코드와 Redis 캐시의 정리 정책은 아직 자동화되어 있지 않다.
- 요청 해시는 문자열 연결 방식이므로 요청 필드 변경 시 해시 대상도 함께 관리해야 한다.
- 로컬 통합 테스트와 k6 결과는 운영 환경의 처리량과 지연시간을 보장하지 않는다.

완료 응답 캐시는 DB 조회를 생략하는 대신 응답 스키마 버전 관리와 민감정보 저장 범위를 함께 관리해야 한다. Redis 장애와 TTL 만료 시에는 여전히 MySQL 레코드가 원본 역할을 한다.

## 15. 운영 고려사항

- 운영 스키마 마이그레이션에 `idempotency_record`와 유니크 제약을 반영한다.
- DB 멱등성 레코드의 보관 기간과 삭제 작업을 정의한다.
- Redis TTL과 클라이언트의 최대 재시도 기간을 일치시킨다.
- Redis TTL이 끝나도 DB 레코드는 필요한 재시도 기간 동안 유지한다.
- 오래된 멱등성 레코드를 삭제할 때 실제 거래내역은 삭제하지 않는다.
- 로그에 계좌 비밀번호, JWT, 요청 해시 입력 원문을 남기지 않는다.
- Redis 장애율, 캐시 적중률, DB 유니크 충돌 횟수, p95·p99를 관찰한다.

레코드를 너무 일찍 삭제하면 지연된 재시도가 새로운 이체로 처리될 수 있다. 보관 기간은 네트워크 재시도 정책, 메시지 재전송 기간, 감사 요구사항을 함께 고려해 결정한다.

## 16. 결론

FinFlow의 이체 멱등성은 MySQL을 최종 정합성 기준으로 삼는다. `Idempotency-Key`와 요청 해시는 동일 요청을 식별하고 키 오용을 거부하며, DB 유니크 제약은 동시 중복 요청 중 하나만 실제 이체로 처리되도록 보장한다.

멱등성 레코드, 두 계좌 잔액, 거래내역을 하나의 트랜잭션으로 묶어 부분 성공을 방지한다. Redis는 완료된 재시도에서 중복 INSERT와 롤백 비용을 제거하지만, 장애가 발생해도 DB-only 경로로 폴백하므로 정확성에는 영향을 주지 않는다.

정확성 통합 테스트, 서비스 계층 성능 비교, k6 HTTP 부하 테스트를 분리해 기능 보장과 성능 관찰의 목적도 구분했다.
