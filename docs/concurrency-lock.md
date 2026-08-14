# 계좌 잔액 동시성 제어: 낙관적 락과 비관적 락

## 1. 문서 목적

이 문서는 동일 계좌에 입금, 출금, 이체 요청이 동시에 들어올 때 발생할 수 있는 잔액 충돌 문제를 해결하기 위해 낙관적 락과 비관적 락을 적용한 과정을 정리한다.

두 방식은 모두 잔액 정합성을 지키지만, 충돌을 처리하는 시점과 요청의 결과가 다르다.

## 2. 구현 순서

이 문서에서는 공통 동시성 테스트 실행 인프라를 먼저 설명하지만, 실제 구현은 다음 순서로 진행했다.

1. `@Version` 기반 낙관적 락을 적용하고 동시성 테스트를 작성했다.
2. `PESSIMISTIC_WRITE` 기반 비관적 락을 적용하고 동시성 테스트를 작성했다.
3. 두 테스트에서 반복된 스레드 풀, `CountDownLatch`, 결과 수집, 소요 시간 측정 코드를 `ConcurrentTestExecutor`로 추출했다.

즉, 공통 실행 인프라는 락 구현의 선행 설계가 아니라, 중복을 발견한 뒤 리팩터링한 결과다.

## 3. 공통 동시성 테스트 실행 인프라

[ConcurrentTestExecutor](../src/test/java/com/FinFlow/support/ConcurrentTestExecutor.java)는 테스트에서 여러 요청을 같은 시점에 실행하고 결과를 수집하는 지원 코드다.

```text
작업 목록 생성
  → ExecutorService에 작업 제출
  → 모든 스레드가 준비 지점에 도달할 때까지 대기
  → CountDownLatch로 동시 시작
  → 성공 수, 실패 예외, 전체 소요 시간 집계
```

| 구성 요소 | 역할 |
| --- | --- |
| `ExecutorService` | 지정한 수만큼 작업을 병렬 실행 |
| `StartGate` | 모든 작업의 시작 시점을 맞춤 |
| `ConcurrentOperation` | 각 스레드가 실행할 작업의 형식 |
| `ConcurrentExecutionResult` | 성공 수, 실패 예외 목록, 소요 시간 반환 |

`StartGate.readyAndAwaitStart()`의 호출 위치는 락 방식별로 다르다.

- 낙관적 락 테스트는 계좌를 조회해 같은 버전을 읽은 **뒤** 호출한다.
- 비관적 락 테스트는 서비스 요청을 실행하기 **직전** 호출한다.

이 차이로 낙관적 락에서는 버전 충돌을 의도적으로 재현하고, 비관적 락에서는 DB 락 대기 상태를 검증한다.

## 4. 낙관적 락

### 도입 배경

여러 요청이 같은 계좌 잔액을 읽고 각각 수정하면, 나중에 저장된 값이 먼저 저장된 값을 덮어쓰는 Lost Update가 발생할 수 있다.

### 구현

[Account](../src/main/java/com/FinFlow/domain/Account.java)에 `@Version` 필드를 추가했다.

```java
@Version
private Long version;
```

JPA는 수정 시 기존 버전을 조건으로 포함한다.

```sql
UPDATE account
SET balance = ?, version = ?
WHERE id = ? AND version = ?
```

먼저 커밋한 요청이 버전을 증가시키면, 이전 버전을 기준으로 수정하려던 요청은 영향을 받은 행이 없어 `OptimisticLockingFailureException`으로 실패한다.

### 동시성 테스트

[AccountOptimisticLockIntegrationTest](../src/test/java/com/FinFlow/service/AccountOptimisticLockIntegrationTest.java)는 Docker MySQL에서 다음 시나리오를 검증한다.

| 시나리오 | 요청 | 기대 결과 |
| --- | --- | --- |
| 동시 입금 | 100원 입금 8건 | 1건 성공, 7건 버전 충돌, 잔액 1,100원 |
| 동시 출금 | 700원 출금 2건 | 1건 성공, 1건 버전 충돌, 잔액 300원 |
| 입금·출금 동시 요청 | 입금 500원, 출금 300원 | 1건 성공, 1건 버전 충돌, 잔액 700원 또는 1,500원 |

낙관적 락의 충돌 예외는 버그가 아니라, 오래된 상태를 기반으로 한 업데이트가 커밋되지 않도록 막았다는 신호다. 충돌한 요청을 성공시키려면 별도의 재시도 정책이 필요하다.

## 5. 비관적 락

### 도입 배경

낙관적 락은 충돌을 감지해 정합성을 지키지만, 동일 계좌에 요청이 집중되면 충돌 요청마다 예외 처리와 재시도가 필요하다. 계좌 잔액처럼 충돌 비용이 큰 데이터에는 요청을 순차 처리하는 방식도 검토할 필요가 있다.

### 구현

[AccountRepository](../src/main/java/com/FinFlow/repository/AccountRepository.java)에 `PESSIMISTIC_WRITE` 조회 메서드를 추가했다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select a from Account a where a.number = :number")
Optional<Account> findByNumberWithPessimisticWriteLock(String number);
```

[AccountService](../src/main/java/com/FinFlow/service/AccountService.java)의 입금, 출금, 이체는 이 메서드로 계좌를 조회한다. 데이터베이스는 트랜잭션이 끝날 때까지 해당 계좌 행의 수정 락을 유지하며, 뒤따르는 수정 요청은 락이 해제될 때까지 대기한다.

이체는 두 계좌를 수정하므로 계좌번호의 문자열 오름차순으로 락을 획득한다. 반대 방향 이체 요청이 동시에 들어와도 같은 순서로 락을 획득하게 해 데드락 가능성을 낮춘다.

### 동시성 테스트

[AccountPessimisticLockIntegrationTest](../src/test/java/com/FinFlow/service/AccountPessimisticLockIntegrationTest.java)는 실제 `AccountService`를 동시에 호출한다.

| 시나리오 | 요청 | 기대 결과 |
| --- | --- | --- |
| 동시 입금 | 100원 입금 8건 | 8건 모두 성공, 잔액 1,800원 |
| 동시 출금 | 700원 출금 2건 | 1건 성공, 1건 잔액 부족 예외, 잔액 300원 |
| 입금·출금 동시 요청 | 입금 500원, 출금 300원 | 2건 모두 성공, 잔액 1,200원 |
| 동시 이체 | 700원 이체 2건 | 1건 성공, 1건 잔액 부족 예외, 출금 계좌 300원 |

여기서 잔액 부족 예외는 락 충돌이 아니라, 먼저 처리된 요청의 최신 잔액을 기준으로 정상 검증한 비즈니스 예외다.

## 6. 비교와 해석

| 항목 | 낙관적 락 | 비관적 락 |
| --- | --- | --- |
| 충돌 처리 시점 | 수정 및 커밋 시점 | 조회 및 락 획득 시점 |
| 충돌 요청의 결과 | 예외 발생, 재시도 필요 | 락 해제까지 대기 후 최신 상태로 처리 |
| 동시 입금 8건 | 1건 성공, 7건 충돌 | 8건 성공 |
| 주요 비용 | 충돌 예외와 재시도 | 락 대기 시간 |
| 적합한 상황 | 충돌이 드문 읽기·수정 작업 | 충돌이 잦고 재시도 비용이 큰 잔액 변경 작업 |

비관적 락을 단순히 성능 개선으로 해석하지 않는다. 비관적 락은 충돌 예외를 줄이는 대신 요청을 대기시키므로, 전체 소요 시간이나 처리량이 항상 더 좋아진다고 단정할 수 없다.

현재 두 테스트는 서비스 호출 범위가 서로 다르므로, 출력되는 소요 시간을 직접 비교해 성능 우열을 판단할 수 없다. 의미 있는 성능 비교를 하려면 동일한 서비스 로직, 요청 수, 데이터베이스 환경에서 두 방식을 반복 실행하고 다음 지표를 함께 비교해야 한다.

- 전체 소요 시간
- 평균 및 최대 응답 시간
- 성공 요청 수
- 충돌 예외 수
- 재시도 횟수

## 7. 실행 방법

Docker MySQL을 실행한 뒤 통합 테스트를 수행한다.

```bash
docker compose up -d mysql

./gradlew integrationTest \
  --tests "com.FinFlow.service.AccountOptimisticLockIntegrationTest" \
  --tests "com.FinFlow.service.AccountPessimisticLockIntegrationTest" \
  --info
```

`--info` 옵션을 사용하면 각 시나리오의 성공 수, 실패 수, 소요 시간을 출력에서 확인할 수 있다.

## 8. 결론

낙관적 락은 버전 검증으로 Lost Update를 방지했고, 비관적 락은 계좌 행을 순차적으로 잠가 충돌 요청을 최신 잔액 기준으로 처리했다. 두 방식 모두 정합성을 지키지만, 충돌이 빈번한 계좌 잔액 변경에서는 예외와 재시도 비용을 줄일 수 있는 비관적 락을 적용했다.

향후에는 실제 트래픽 조건을 가정한 반복 측정과 재시도 정책을 추가해, 충돌 빈도에 따른 적절한 락 전략을 검증한다.
