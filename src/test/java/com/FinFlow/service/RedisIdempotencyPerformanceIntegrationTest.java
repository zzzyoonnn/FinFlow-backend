package com.FinFlow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.FinFlow.config.dummy.DummyObject;
import com.FinFlow.domain.Account;
import com.FinFlow.domain.User;
import com.FinFlow.dto.account.AccountReqDTO.AccountTransferReqDTO;
import com.FinFlow.repository.AccountRepository;
import com.FinFlow.repository.IdempotencyRecordRepository;
import com.FinFlow.repository.TransactionRepository;
import com.FinFlow.repository.UserRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("mysql")
@SpringBootTest
class RedisIdempotencyPerformanceIntegrationTest extends DummyObject {

  private static final int WARM_UP_COUNT = 10;
  private static final int MEASUREMENT_COUNT = 100;
  private static final String DB_ONLY_KEY = "performance-db-only";
  private static final String REDIS_KEY = "performance-redis";

  @Autowired
  private IdempotentTransferService databaseService;
  @Autowired
  private RedisIdempotentTransferService redisService;
  @Autowired
  private RedisIdempotencyCache redisCache;
  @Autowired
  private IdempotencyRecordRepository idempotencyRecordRepository;
  @Autowired
  private TransactionRepository transactionRepository;
  @Autowired
  private AccountRepository accountRepository;
  @Autowired
  private UserRepository userRepository;

  private Long senderId;
  private AccountTransferReqDTO request;

  @BeforeEach
  void setUp() {
    redisCache.evict(DB_ONLY_KEY);
    redisCache.evict(REDIS_KEY);
    idempotencyRecordRepository.deleteAll();
    transactionRepository.deleteAll();
    accountRepository.deleteAll();
    userRepository.deleteAll();

    User sender = userRepository.save(newUser("performance-sender", "sender"));
    User receiver = userRepository.save(newUser("performance-receiver", "receiver"));
    senderId = sender.getId();
    accountRepository.save(newAccount("1111111111", sender));
    accountRepository.save(newAccount("2222222222", receiver));
    request = transferRequest(1L);

    // 각 경로에서 최초 거래를 한 번만 생성한 뒤 중복 재시도 비용을 비교한다.
    databaseService.transfer(DB_ONLY_KEY, request, senderId);
    redisService.transfer(REDIS_KEY, request, senderId);
  }

  @Test
  void compareDuplicateRetryLatencyBeforeAndAfterRedis() {
    for (int i = 0; i < WARM_UP_COUNT; i++) {
      databaseService.transfer(DB_ONLY_KEY, request, senderId);
      redisService.transfer(REDIS_KEY, request, senderId);
    }

    List<Long> databaseOnly = new ArrayList<>();
    List<Long> redisFirst = new ArrayList<>();
    for (int i = 0; i < MEASUREMENT_COUNT; i++) {
      databaseOnly.add(measureNanos(
          () -> databaseService.transfer(DB_ONLY_KEY, request, senderId)));
      redisFirst.add(measureNanos(
          () -> redisService.transfer(REDIS_KEY, request, senderId)));
    }

    LatencyStats dbStats = LatencyStats.from(databaseOnly);
    LatencyStats redisStats = LatencyStats.from(redisFirst);
    System.out.printf("DB-only 중복 재시도  - avg: %.3f ms, p50: %.3f ms, p95: %.3f ms%n",
        dbStats.averageMillis(), dbStats.p50Millis(), dbStats.p95Millis());
    System.out.printf("Redis 우선 중복 재시도 - avg: %.3f ms, p50: %.3f ms, p95: %.3f ms%n",
        redisStats.averageMillis(), redisStats.p50Millis(), redisStats.p95Millis());
    System.out.printf("평균 응답시간 개선율     - %.2f%%%n",
        (1.0 - redisStats.averageNanos() / dbStats.averageNanos()) * 100.0);

    Account withdraw = accountRepository.findByNumber("1111111111").orElseThrow();
    Account deposit = accountRepository.findByNumber("2222222222").orElseThrow();
    assertThat(transactionRepository.count()).isEqualTo(2L);
    assertThat(idempotencyRecordRepository.count()).isEqualTo(2L);
    assertThat(withdraw.getBalance()).isEqualTo(998L);
    assertThat(deposit.getBalance()).isEqualTo(1002L);
  }

  private long measureNanos(Runnable operation) {
    long startedAt = System.nanoTime();
    operation.run();
    return System.nanoTime() - startedAt;
  }

  private AccountTransferReqDTO transferRequest(Long amount) {
    AccountTransferReqDTO transferRequest = new AccountTransferReqDTO();
    transferRequest.setWithdrawNumber("1111111111");
    transferRequest.setDepositNumber("2222222222");
    transferRequest.setWithdrawPassword(1234L);
    transferRequest.setAmount(amount);
    transferRequest.setTransactionType("TRANSFER");
    return transferRequest;
  }

  private record LatencyStats(double averageNanos, long p50Nanos, long p95Nanos) {

    private static LatencyStats from(List<Long> samples) {
      List<Long> sorted = new ArrayList<>(samples);
      Collections.sort(sorted);
      double average = sorted.stream().mapToLong(Long::longValue).average().orElse(0.0);
      return new LatencyStats(average, percentile(sorted, 0.50), percentile(sorted, 0.95));
    }

    private static long percentile(List<Long> sorted, double percentile) {
      int index = (int) Math.ceil(percentile * sorted.size()) - 1;
      return sorted.get(Math.max(0, index));
    }

    private double averageMillis() {
      return averageNanos / 1_000_000.0;
    }

    private double p50Millis() {
      return p50Nanos / 1_000_000.0;
    }

    private double p95Millis() {
      return p95Nanos / 1_000_000.0;
    }
  }
}
