# 거래 처리와 DB 트랜잭션

## 1. 문서 목적

이 문서는 FinFlow의 입금, 출금, 이체 처리 흐름과 DB 트랜잭션으로 보장하는 원자성을 정리한다. 거래내역 조회 기능과 실패 롤백 검증은 목적과 실행 환경이 다르므로 별도의 테스트로 관리한다.

## 2. 거래 처리 구조

쓰기 작업은 [AccountService](../src/main/java/com/FinFlow/service/AccountService.java)가 담당한다. 클래스에는 읽기 전용 트랜잭션을 기본으로 적용하고, 데이터를 변경하는 메서드에만 `@Transactional`을 선언했다.

```java
@Service
@Transactional(readOnly = true)
public class AccountService {

  @Transactional
  public AccountDepositRespDTO depositAccount(...) { ... }

  @Transactional
  public AccountWithdrawRespDTO withdrawAccount(...) { ... }

  @Transactional
  public AccountTransferRespDTO transferAccount(...) { ... }
}
```

`@Transactional`이 적용된 메서드 안에서 `RuntimeException`이 발생하면, 해당 메서드에서 수행한 DB 변경은 하나의 작업 단위로 롤백된다.

## 3. 입금·출금·이체 흐름

| 기능 | 처리 흐름 | 저장되는 거래내역 |
| --- | --- | --- |
| 입금 | 계좌 조회 → 금액 검증 → 잔액 증가 → 거래내역 저장 | 입금 계좌, 입금 후 잔액, 금액, 거래 유형(`DEPOSIT`) |
| 출금 | 계좌 조회 → 소유자·비밀번호·잔액 검증 → 잔액 감소 → 거래내역 저장 | 출금 계좌, 출금 후 잔액, 금액, 거래 유형(`WITHDRAW`) |
| 이체 | 출금/입금 계좌 조회 → 소유자·비밀번호·잔액 검증 → 출금 잔액 감소 → 입금 잔액 증가 → 거래내역 저장 | 두 계좌, 각 처리 후 잔액, 금액, 거래 유형(`TRANSFER`) |

이체는 두 계좌의 잔액과 거래내역을 함께 변경하므로, 세 작업이 모두 성공하거나 모두 실패해야 한다.

```text
출금 계좌 잔액 감소
  → 입금 계좌 잔액 증가
  → 거래내역 저장
  → 커밋
```

## 4. 실패 롤백 검증

### 검증하려는 문제

트랜잭션 경계가 없다면 거래내역 저장 단계에서 예외가 발생했을 때, 두 계좌의 잔액만 변경되는 부분 성공 상태가 남을 수 있다. 금융 거래에서는 이 상태를 허용할 수 없다.

### 테스트 방식

[AccountTransactionRollbackIntegrationTest](../src/test/java/com/FinFlow/service/AccountTransactionRollbackIntegrationTest.java)는 Docker MySQL에 연결하는 통합 테스트다.

1. 잔액이 각각 1,000원인 출금·입금 계좌를 생성한다.
2. 500원 이체 요청을 만든다.
3. `TransactionRepository.save()`가 `DataIntegrityViolationException`을 던지도록 대체한다.
4. 예외 발생 뒤 MySQL에서 두 계좌와 거래내역 테이블을 다시 조회한다.
5. 두 잔액이 모두 1,000원이고 거래내역이 없음을 검증한다.

핵심 검증 코드는 다음과 같다.

```java
given(transactionRepository.save(any()))
        .willThrow(new DataIntegrityViolationException("거래내역 저장 실패"));

assertThatThrownBy(() -> accountService.transferAccount(request, userId))
        .isInstanceOf(DataIntegrityViolationException.class);

assertThat(persistedWithdrawAccount.getBalance()).isEqualTo(1000L);
assertThat(persistedDepositAccount.getBalance()).isEqualTo(1000L);
assertThat(transactionCount).isZero();
```

이 테스트는 **거래내역 저장 실패가 잔액 변경까지 함께 롤백시키는지** 검증한다. MySQL 컨테이너와 테스트 프로필은 각각 [compose.yaml](../compose.yaml), [application-mysql.yml](../src/main/resources/application-mysql.yml)에서 관리한다.

### 실행 방법

```bash
docker compose up -d mysql
./gradlew integrationTest
```

## 5. 거래내역 조회 테스트

[TransactionRepositoryImplTests](../src/test/java/com/FinFlow/domain/transaction/TransactionRepositoryImplTests.java)는 실패 롤백 테스트와 별도로 유지한다.

이 테스트의 관심사는 `TransactionRepositoryImpl`의 조회 쿼리다.

- 특정 계좌의 전체 거래내역 조회
- 입금(`DEPOSIT`) 거래내역 조회
- 출금(`WITHDRAW`) 거래내역 조회
- Fetch Join을 통한 연관 엔티티 조회

즉, 이 테스트는 거래 처리의 원자성을 검증하지 않는다. 사전에 입금·출금·이체 거래 데이터를 저장한 뒤, 거래내역 조회와 엔티티 매핑이 올바른지 확인하는 Repository 테스트다.

| 테스트 | 관심사 | 실행 환경 |
| --- | --- | --- |
| `TransactionRepositoryImplTests` | 거래내역 조회 쿼리, 필터링, Fetch Join, 매핑 | H2 기반 `@DataJpaTest` |
| `AccountTransactionRollbackIntegrationTest` | 이체 실패 시 잔액 및 거래내역의 전체 롤백 | Docker MySQL 기반 `@SpringBootTest` |

## 6. 결론 및 기대 효과

입금, 출금, 이체를 서비스 계층의 트랜잭션으로 묶어 계좌 잔액 변경과 거래내역 저장을 하나의 작업 단위로 처리했다. 또한 MySQL 통합 테스트에서 거래내역 저장 실패를 재현하여, 예외 발생 시 두 계좌의 잔액이 기존 값으로 유지되는지를 검증했다.

이를 통해 이체 처리 중 일부 작업만 반영되는 부분 성공 상태를 방지하고, 잔액과 거래내역 사이의 기본적인 정합성을 보장한다. 다만 이는 하나의 요청 내부에서의 원자성을 검증한 결과이며, 동시에 들어오는 여러 요청 사이의 충돌 문제는 아직 해결하지 않는다.

다음 단계에서는 낙관적 락과 비관적 락을 각각 적용하고 동시 이체 테스트로 검증하여, 동시성 환경에서도 계좌 잔액의 정합성을 유지하도록 개선한다.
