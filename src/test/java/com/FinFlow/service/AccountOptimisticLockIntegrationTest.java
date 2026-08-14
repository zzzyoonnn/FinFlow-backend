package com.FinFlow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.FinFlow.config.dummy.DummyObject;
import com.FinFlow.domain.Account;
import com.FinFlow.domain.User;
import com.FinFlow.repository.AccountRepository;
import com.FinFlow.repository.UserRepository;
import com.FinFlow.support.ConcurrentTestExecutor;
import com.FinFlow.support.ConcurrentTestExecutor.ConcurrentExecutionResult;
import com.FinFlow.support.ConcurrentTestExecutor.ConcurrentOperation;
import com.FinFlow.support.ConcurrentTestExecutor.StartGate;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("mysql")
@SpringBootTest
class AccountOptimisticLockIntegrationTest extends DummyObject {

  private static final int DEPOSIT_REQUEST_COUNT = 8;
  private static final long INITIAL_BALANCE = 1_000L;

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private TransactionTemplate transactionTemplate;
  private Account account;
  private final ConcurrentTestExecutor concurrentTestExecutor = new ConcurrentTestExecutor();

  @BeforeEach
  void setUp() {
    transactionTemplate = new TransactionTemplate(transactionManager);
    User user = userRepository.save(newUser("concurrency-user", "동시성 테스트 사용자"));
    account = accountRepository.save(newAccount("1111111111", user));
  }

  @AfterEach
  void tearDown() {
    accountRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  void concurrentDeposits_detectOptimisticLockConflicts() throws Exception {
    ConcurrentExecutionResult result = executeConcurrently(
            DEPOSIT_REQUEST_COUNT, loadedAccount -> loadedAccount.deposit(100L));

    Account persistedAccount = accountRepository.findById(account.getId()).orElseThrow();

    assertThat(result.successCount()).isEqualTo(1);
    assertOptimisticLockFailures(result, DEPOSIT_REQUEST_COUNT - 1);
    assertThat(persistedAccount.getBalance()).isEqualTo(INITIAL_BALANCE + 100L);

    logResult("동시 입금", result);
  }

  @Test
  void concurrentWithdrawals_detectOptimisticLockConflicts_beforeBalanceCanBeOverdrawn() throws Exception {
    ConcurrentExecutionResult result = executeConcurrently(
            2, loadedAccount -> loadedAccount.withdraw(700L));

    Account persistedAccount = accountRepository.findById(account.getId()).orElseThrow();

    assertThat(result.successCount()).isEqualTo(1);
    assertOptimisticLockFailures(result, 1);
    assertThat(persistedAccount.getBalance()).isEqualTo(300L);

    logResult("동시 출금", result);
  }

  @Test
  void concurrentDepositAndWithdrawal_detectOptimisticLockConflict() throws Exception {
    ConcurrentExecutionResult result = executeConcurrently(
            List.of(
                    loadedAccount -> loadedAccount.deposit(500L),
                    loadedAccount -> loadedAccount.withdraw(300L)
            ));

    Account persistedAccount = accountRepository.findById(account.getId()).orElseThrow();

    assertThat(result.successCount()).isEqualTo(1);
    assertOptimisticLockFailures(result, 1);
    assertThat(persistedAccount.getBalance()).isIn(700L, 1_500L);

    logResult("입금·출금 동시 요청", result);
  }

  private ConcurrentExecutionResult executeConcurrently(
          int requestCount, Consumer<Account> operation) throws Exception {
    return concurrentTestExecutor.execute(requestCount, startGate -> executeInNewTransaction(operation, startGate));
  }

  private ConcurrentExecutionResult executeConcurrently(List<Consumer<Account>> operations) throws Exception {
    List<ConcurrentOperation> concurrentOperations = operations.stream()
            .<ConcurrentOperation>map(operation -> startGate -> executeInNewTransaction(operation, startGate))
            .toList();
    return concurrentTestExecutor.execute(concurrentOperations);
  }

  private void executeInNewTransaction(Consumer<Account> operation, StartGate startGate) {
    transactionTemplate.executeWithoutResult(status -> {
      Account loadedAccount = accountRepository.findById(account.getId()).orElseThrow();
      startGate.readyAndAwaitStart();
      operation.accept(loadedAccount);
    });
  }

  private void assertOptimisticLockFailures(ConcurrentExecutionResult result, int expectedFailureCount) {
    assertThat(result.failures()).hasSize(expectedFailureCount);
    result.failures().forEach(failure -> {
      assertThat(failure).isInstanceOf(OptimisticLockingFailureException.class);
      System.out.printf("낙관적 락 충돌 예외: %s%n", failure.getClass().getName());
    });
  }

  private void logResult(String scenario, ConcurrentExecutionResult result) {
    System.out.printf(
            "%s - 성공: %d, 낙관적 락 충돌: %d, 소요 시간: %d ms%n",
            scenario,
            result.successCount(),
            result.failures().size(),
            result.elapsedMilliseconds()
    );
  }

}
