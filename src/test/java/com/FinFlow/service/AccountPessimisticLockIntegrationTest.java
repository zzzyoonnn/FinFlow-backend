package com.FinFlow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.FinFlow.config.dummy.DummyObject;
import com.FinFlow.domain.Account;
import com.FinFlow.domain.User;
import com.FinFlow.dto.account.AccountReqDTO.AccountDepositReqDTO;
import com.FinFlow.dto.account.AccountReqDTO.AccountTransferReqDTO;
import com.FinFlow.dto.account.AccountReqDTO.AccountWithdrawReqDTO;
import com.FinFlow.handler.ex.CustomApiException;
import com.FinFlow.repository.AccountRepository;
import com.FinFlow.repository.TransactionRepository;
import com.FinFlow.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("mysql")
@SpringBootTest
class AccountPessimisticLockIntegrationTest extends DummyObject {

  private static final int DEPOSIT_REQUEST_COUNT = 8;

  @Autowired
  private AccountService accountService;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private TransactionRepository transactionRepository;

  private Account sourceAccount;
  private Account targetAccount;
  private Long sourceUserId;

  @BeforeEach
  void setUp() {
    User sourceUser = userRepository.save(newUser("source-user", "송금자"));
    User targetUser = userRepository.save(newUser("target-user", "수금자"));
    sourceUserId = sourceUser.getId();
    sourceAccount = accountRepository.save(newAccount("1111111111", sourceUser));
    targetAccount = accountRepository.save(newAccount("2222222222", targetUser));
  }

  @AfterEach
  void tearDown() {
    transactionRepository.deleteAll();
    accountRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  void concurrentDeposits_areSerializedAndAllSucceed() throws Exception {
    ConcurrentExecutionResult result = executeConcurrently(DEPOSIT_REQUEST_COUNT, () ->
            accountService.depositAccount(depositRequest(100L))
    );

    Account persistedAccount = accountRepository.findById(sourceAccount.getId()).orElseThrow();

    assertThat(result.successCount()).isEqualTo(DEPOSIT_REQUEST_COUNT);
    assertThat(result.failures()).isEmpty();
    assertThat(persistedAccount.getBalance()).isEqualTo(1_800L);
    assertThat(transactionRepository.count()).isEqualTo(DEPOSIT_REQUEST_COUNT);

    logResult("비관적 락 동시 입금", result);
  }

  @Test
  void concurrentWithdrawals_areSerializedAndOnlyInsufficientBalanceRequestFails() throws Exception {
    ConcurrentExecutionResult result = executeConcurrently(2, () ->
            accountService.withdrawAccount(withdrawRequest(700L), sourceUserId)
    );

    Account persistedAccount = accountRepository.findById(sourceAccount.getId()).orElseThrow();

    assertThat(result.successCount()).isEqualTo(1);
    assertThat(result.failures()).hasSize(1);
    assertThat(result.failures().get(0)).isInstanceOf(CustomApiException.class);
    assertThat(persistedAccount.getBalance()).isEqualTo(300L);
    assertThat(transactionRepository.count()).isEqualTo(1);

    logResult("비관적 락 동시 출금", result);
  }

  @Test
  void concurrentDepositAndWithdrawal_areSerializedAndBothSucceed() throws Exception {
    ConcurrentExecutionResult result = executeConcurrently(List.of(
            () -> accountService.depositAccount(depositRequest(500L)),
            () -> accountService.withdrawAccount(withdrawRequest(300L), sourceUserId)
    ));

    Account persistedAccount = accountRepository.findById(sourceAccount.getId()).orElseThrow();

    assertThat(result.successCount()).isEqualTo(2);
    assertThat(result.failures()).isEmpty();
    assertThat(persistedAccount.getBalance()).isEqualTo(1_200L);
    assertThat(transactionRepository.count()).isEqualTo(2);

    logResult("비관적 락 입금·출금 동시 요청", result);
  }

  @Test
  void concurrentTransfers_areSerializedAndCannotOverdrawSourceAccount() throws Exception {
    ConcurrentExecutionResult result = executeConcurrently(2, () ->
            accountService.transferAccount(transferRequest(700L), sourceUserId)
    );

    Account persistedSourceAccount = accountRepository.findById(sourceAccount.getId()).orElseThrow();
    Account persistedTargetAccount = accountRepository.findById(targetAccount.getId()).orElseThrow();

    assertThat(result.successCount()).isEqualTo(1);
    assertThat(result.failures()).hasSize(1);
    assertThat(result.failures().get(0)).isInstanceOf(CustomApiException.class);
    assertThat(persistedSourceAccount.getBalance()).isEqualTo(300L);
    assertThat(persistedTargetAccount.getBalance()).isEqualTo(1_700L);
    assertThat(transactionRepository.count()).isEqualTo(1);

    logResult("비관적 락 동시 이체", result);
  }

  private AccountDepositReqDTO depositRequest(Long amount) {
    AccountDepositReqDTO request = new AccountDepositReqDTO();
    request.setNumber(sourceAccount.getNumber());
    request.setAmount(amount);
    request.setTransactionType("DEPOSIT");
    request.setTel("010-1234-5678");
    return request;
  }

  private AccountWithdrawReqDTO withdrawRequest(Long amount) {
    AccountWithdrawReqDTO request = new AccountWithdrawReqDTO();
    request.setNumber(sourceAccount.getNumber());
    request.setPassword(1234L);
    request.setAmount(amount);
    request.setTransactionType("WITHDRAW");
    return request;
  }

  private AccountTransferReqDTO transferRequest(Long amount) {
    AccountTransferReqDTO request = new AccountTransferReqDTO();
    request.setWithdrawNumber(sourceAccount.getNumber());
    request.setDepositNumber(targetAccount.getNumber());
    request.setWithdrawPassword(1234L);
    request.setAmount(amount);
    request.setTransactionType("TRANSFER");
    return request;
  }

  private ConcurrentExecutionResult executeConcurrently(int requestCount, ConcurrentOperation operation) throws Exception {
    List<ConcurrentOperation> operations = new ArrayList<>();
    for (int index = 0; index < requestCount; index++) {
      operations.add(operation);
    }
    return executeConcurrently(operations);
  }

  private ConcurrentExecutionResult executeConcurrently(List<ConcurrentOperation> operations) throws Exception {
    ExecutorService executorService = Executors.newFixedThreadPool(operations.size());
    CountDownLatch ready = new CountDownLatch(operations.size());
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Throwable>> futures = new ArrayList<>();

    try {
      for (ConcurrentOperation operation : operations) {
        futures.add(executorService.submit(() -> {
          ready.countDown();
          await(start);
          try {
            operation.run();
            return null;
          } catch (Throwable throwable) {
            return throwable;
          }
        }));
      }

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      long startedAt = System.nanoTime();
      start.countDown();

      int successCount = 0;
      List<Throwable> failures = new ArrayList<>();
      for (Future<Throwable> future : futures) {
        Throwable failure = future.get(10, TimeUnit.SECONDS);
        if (failure == null) {
          successCount++;
        } else {
          failures.add(failure);
        }
      }

      return new ConcurrentExecutionResult(
              successCount,
              failures,
              TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
      );
    } finally {
      executorService.shutdownNow();
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
            "%s - 성공: %d, 실패: %d, 소요 시간: %d ms%n",
            scenario,
            result.successCount(),
            result.failures().size(),
            result.elapsedMilliseconds()
    );
  }

  @FunctionalInterface
  private interface ConcurrentOperation {
    void run();
  }

  private record ConcurrentExecutionResult(
          int successCount,
          List<Throwable> failures,
          long elapsedMilliseconds
  ) {
  }
}
