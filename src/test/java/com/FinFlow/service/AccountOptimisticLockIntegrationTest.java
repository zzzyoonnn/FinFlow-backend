package com.FinFlow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.FinFlow.config.dummy.DummyObject;
import com.FinFlow.domain.Account;
import com.FinFlow.domain.User;
import com.FinFlow.repository.AccountRepository;
import com.FinFlow.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    assertThat(result.optimisticLockFailureCount()).isEqualTo(DEPOSIT_REQUEST_COUNT - 1);
    assertThat(persistedAccount.getBalance()).isEqualTo(INITIAL_BALANCE + 100L);

    logResult("동시 입금", result);
  }

  @Test
  void concurrentWithdrawals_detectOptimisticLockConflicts_beforeBalanceCanBeOverdrawn() throws Exception {
    ConcurrentExecutionResult result = executeConcurrently(
            2, loadedAccount -> loadedAccount.withdraw(700L));

    Account persistedAccount = accountRepository.findById(account.getId()).orElseThrow();

    assertThat(result.successCount()).isEqualTo(1);
    assertThat(result.optimisticLockFailureCount()).isEqualTo(1);
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
    assertThat(result.optimisticLockFailureCount()).isEqualTo(1);
    assertThat(persistedAccount.getBalance()).isIn(700L, 1_500L);

    logResult("입금·출금 동시 요청", result);
  }

  private ConcurrentExecutionResult executeConcurrently(
          int requestCount, Consumer<Account> operation) throws Exception {
    List<Consumer<Account>> operations = new ArrayList<>();
    for (int index = 0; index < requestCount; index++) {
      operations.add(operation);
    }
    return executeConcurrently(operations);
  }

  private ConcurrentExecutionResult executeConcurrently(List<Consumer<Account>> operations) throws Exception {
    ExecutorService executorService = Executors.newFixedThreadPool(operations.size());
    CountDownLatch ready = new CountDownLatch(operations.size());
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Throwable>> futures = new ArrayList<>();
    long startedAt = System.nanoTime();

    try {
      for (Consumer<Account> operation : operations) {
        futures.add(executorService.submit(() -> executeInNewTransaction(operation, ready, start)));
      }

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      startedAt = System.nanoTime();
      start.countDown();

      int successCount = 0;
      int optimisticLockFailureCount = 0;
      for (Future<Throwable> future : futures) {
        Throwable failure = future.get(10, TimeUnit.SECONDS);
        if (failure == null) {
          successCount++;
        } else {
          assertThat(failure).isInstanceOf(OptimisticLockingFailureException.class);
          System.out.printf("낙관적 락 충돌 예외: %s%n", failure.getClass().getName());
          optimisticLockFailureCount++;
        }
      }

      return new ConcurrentExecutionResult(
              successCount,
              optimisticLockFailureCount,
              TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
      );
    } finally {
      executorService.shutdownNow();
    }
  }

  private Throwable executeInNewTransaction(
          Consumer<Account> operation, CountDownLatch ready, CountDownLatch start) {
    try {
      transactionTemplate.executeWithoutResult(status -> {
        Account loadedAccount = accountRepository.findById(account.getId()).orElseThrow();
        ready.countDown();
        await(start);
        operation.accept(loadedAccount);
      });
      return null;
    } catch (Throwable throwable) {
      return throwable;
    }
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("동시성 테스트 시작 대기 시간이 초과되었습니다.");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("동시성 테스트가 중단되었습니다.", exception);
    }
  }

  private void logResult(String scenario, ConcurrentExecutionResult result) {
    System.out.printf(
            "%s - 성공: %d, 낙관적 락 충돌: %d, 소요 시간: %d ms%n",
            scenario,
            result.successCount(),
            result.optimisticLockFailureCount(),
            result.elapsedMilliseconds()
    );
  }

  private record ConcurrentExecutionResult(
          int successCount,
          int optimisticLockFailureCount,
          long elapsedMilliseconds
  ) {
  }
}
