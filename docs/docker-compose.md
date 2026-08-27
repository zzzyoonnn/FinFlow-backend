# Docker Compose 실행 가이드

## 1. 문서 목적

이 문서는 FinFlow의 Docker Compose 구성과 로컬 실행 방법을 정리한다. Docker가 기본 인프라 실행 환경이며, 로컬에 직접 설치한 MySQL이나 Redis 대신 Compose 서비스를 사용한다.

현재 [compose.yaml](../compose.yaml)은 MySQL, Redis, Kafka(KRaft), k6를 제공한다.

## 2. 구성 요소와 포트

| 서비스 | 컨테이너 | 호스트 포트 | 컨테이너 포트 | 용도 |
| --- | --- | ---: | ---: | --- |
| MySQL 8.4 | `finflow-mysql` | 3307 | 3306 | 거래와 멱등성 기록의 최종 저장소 |
| Redis 7.4 | `finflow-redis` | 6380 | 6379 | 진행 중 요청 차단과 완료 응답 캐시 |
| Kafka 3.9 | `finflow-kafka` | 9092 | 9092 | 이체 완료 이벤트 발행·소비 |
| k6 0.57 | 실행 시 생성 | 없음 | 없음 | HTTP 멱등성 부하 테스트 |

호스트 포트는 로컬에 설치된 MySQL의 `3306`, Redis의 `6379`와 충돌하지 않도록 각각 `3307`, `6380`을 사용한다.

MySQL과 Redis 데이터는 named volume에 저장된다.

```text
finflow-mysql-data
finflow-redis-data
```

## 3. 사전 준비

프로젝트 루트에서 Docker Compose 설정을 확인한다.

```bash
cd FinFlow
docker compose config
```

MySQL, Redis, Kafka를 시작한다.

```bash
docker compose up -d mysql redis kafka
docker compose ps
```

두 서비스가 `healthy` 상태가 되면 애플리케이션을 실행할 수 있다.

## 4. 애플리케이션 실행

`dev,mysql` 프로필 순서로 실행한다. `dev` 프로필은 기본 개발 데이터와 H2 설정을 제공하고, 뒤에 선언한 `mysql` 프로필이 데이터소스를 Docker MySQL 설정으로 덮어쓴다.

```bash
SPRING_PROFILES_ACTIVE=dev,mysql \
SPRING_JPA_SHOW_SQL=false \
./gradlew bootRun
```

기본 연결값은 다음과 같다.

| 환경변수 | 기본값 |
| --- | --- |
| `MYSQL_HOST` | `localhost` |
| `MYSQL_PORT` | `3307` |
| `MYSQL_DATABASE` | `finflow` |
| `MYSQL_USERNAME` | `finflow` |
| `MYSQL_PASSWORD` | `finflow` |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6380` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `KAFKA_ENABLED` | `false` |

`SPRING_JPA_SHOW_SQL=false`는 통합·부하 테스트 중 SQL 콘솔 출력 비용이 결과에 섞이는 것을 줄인다.

IntelliJ Spring Boot 실행 구성에서는 Active profiles를 다음과 같이 지정한다.

```text
dev,mysql
```

Environment variables에는 필요에 따라 다음 값을 추가한다.

```text
SPRING_JPA_SHOW_SQL=false
FINFLOW_IDEMPOTENCY_REDIS_ENABLED=true
KAFKA_ENABLED=true
```

## 5. 연결 확인

Docker MySQL 연결을 확인한다.

```bash
docker compose exec mysql mysqladmin ping -h localhost -proot
```

Docker Redis 연결을 확인한다.

```bash
docker compose exec redis redis-cli PING
```

Redis가 정상이라면 `PONG`이 출력된다. 저장된 멱등성 키를 확인하려면 다음 명령을 사용한다.

```bash
docker compose exec redis redis-cli --scan --pattern 'finflow:idempotency:transfer:*'
```

특정 키의 문자열 값을 확인한다.

```bash
docker compose exec redis redis-cli GET '<조회할 Redis 키>'
```

## 6. Redis 활성화와 DB-only 실행

Redis 멱등성 계층은 애플리케이션 환경변수로 전환한다.

Redis 활성화:

```bash
FINFLOW_IDEMPOTENCY_REDIS_ENABLED=true
```

DB-only:

```bash
FINFLOW_IDEMPOTENCY_REDIS_ENABLED=false
```

이 값은 애플리케이션 시작 시 적용되므로 변경한 뒤 서버를 재시작한다. Redis가 비활성화되거나 명령 실행에 실패하면 DB 멱등성 경로가 중복 이체를 최종 방지한다.

## 7. 테스트 실행

단위 테스트는 외부 컨테이너 없이 실행한다.

```bash
./gradlew test
```

MySQL·Redis 통합 테스트는 컨테이너를 시작한 뒤 실행한다.

```bash
docker compose up -d mysql redis
./gradlew integrationTest
```

특정 통합 테스트만 실행할 수도 있다.

```bash
./gradlew integrationTest \
  --tests 'com.FinFlow.service.RedisIdempotencyConcurrencyIntegrationTest'
```

## 8. k6 부하 테스트

k6는 `loadtest` 프로필에 포함되어 있으므로 일반 `docker compose up`에서는 시작되지 않는다. 애플리케이션을 먼저 실행한 뒤 별도 컨테이너로 호출한다.

```bash
K6_MODE=redis \
K6_IDEMPOTENCY_KEY=k6-redis-001 \
K6_VUS=20 \
K6_DURATION=30s \
K6_SCRIPT=idempotency-cache-hit.js \
docker compose --profile loadtest run --rm k6
```

k6 컨테이너는 `host.docker.internal:8081`을 통해 호스트에서 실행 중인 Spring Boot 애플리케이션에 접근한다. 결과 JSON은 다음 디렉터리에 저장된다.

```text
loadtest/k6/results/
```

DB-only, Redis 캐시 적중, SET NX 경합의 전체 실행 방법은 [k6 부하 테스트 문서](k6-load-test.md)를 참고한다.

## 9. 로그와 상태 확인

실행 중인 서비스 상태를 확인한다.

```bash
docker compose ps
```

MySQL과 Redis 로그를 확인한다.

```bash
docker compose logs -f mysql redis
```

## 10. 종료와 데이터 초기화

컨테이너만 종료하고 데이터 volume은 유지한다.

```bash
docker compose down
```

MySQL과 Redis 데이터를 포함해 완전히 초기화하려면 다음 명령을 사용한다.

```bash
docker compose down -v
```

`-v`는 `finflow-mysql-data`와 `finflow-redis-data` volume을 삭제하므로 기존 로컬 테스트 데이터는 복구할 수 없다. 데이터 초기화가 필요한 경우에만 사용한다.

## 11. 주의사항

- `application-mysql.yml`의 `ddl-auto: create-drop`은 개발과 통합 테스트용이다. 운영 배포에서는 `validate`와 Flyway 또는 Liquibase 같은 스키마 마이그레이션 도구가 필요하다.
- Compose의 MySQL 비밀번호는 로컬 개발용 기본값이다. 외부 환경에서는 환경변수나 Secret 관리 서비스를 사용해야 한다.
- 애플리케이션을 Docker 컨테이너로 실행하게 되면 DB와 Redis 주소는 `localhost`가 아니라 Compose 서비스 이름인 `mysql`, `redis`를 사용해야 한다.
- k6 결과는 로컬 CPU, Docker 자원, JVM 워밍업과 로그 출력에 영향을 받으므로 여러 번 반복해 중앙값으로 비교한다.
