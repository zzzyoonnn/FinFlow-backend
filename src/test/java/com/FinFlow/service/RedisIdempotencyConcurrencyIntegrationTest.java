package com.FinFlow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.FinFlow.config.dummy.DummyObject;
import com.FinFlow.domain.Account;
import com.FinFlow.domain.User;
import com.FinFlow.dto.account.AccountReqDTO.AccountTransferReqDTO;
import com.FinFlow.dto.account.AccountRespDTO.AccountTransferRespDTO;
import com.FinFlow.repository.AccountRepository;
import com.FinFlow.repository.IdempotencyRecordRepository;
import com.FinFlow.repository.TransactionRepository;
import com.FinFlow.repository.UserRepository;
import com.FinFlow.support.ConcurrentTestExecutor;
import com.FinFlow.support.ConcurrentTestExecutor.ConcurrentExecutionResult;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("mysql")
@SpringBootTest
class RedisIdempotencyConcurrencyIntegrationTest extends DummyObject {

  private static final String CONCURRENT_KEY = "redis-set-nx-concurrent";
  private static final String RESPONSE_CACHE_KEY = "redis-completed-response";

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

  private final ConcurrentTestExecutor concurrentExecutor = new ConcurrentTestExecutor();
  private Long senderId;
  private AccountTransferReqDTO request;

  @BeforeEach
  void setUp() {
    redisCache.evict(CONCURRENT_KEY);
    redisCache.evict(RESPONSE_CACHE_KEY);
    idempotencyRecordRepository.deleteAll();
    transactionRepository.deleteAll();
    accountRepository.deleteAll();
    userRepository.deleteAll();

    User sender = userRepository.save(newUser("redis-sender", "sender"));
    User receiver = userRepository.save(newUser("redis-receiver", "receiver"));
    senderId = sender.getId();
    accountRepository.save(newAccount("1111111111", sender));
    accountRepository.save(newAccount("2222222222", receiver));
    request = transferRequest(100L);
  }

  @Test
  void setNxAllowsExactlyOneProcessingOwner() throws Exception {
    String requestHash = "a".repeat(64);
    ConcurrentLinkedQueue<RedisIdempotencyCache.ClaimResult> claims =
        new ConcurrentLinkedQueue<>();

    ConcurrentExecutionResult result = concurrentExecutor.execute(8, startGate -> {
      startGate.readyAndAwaitStart();
      claims.add(redisCache.tryClaim(CONCURRENT_KEY, requestHash));
    });

    assertThat(result.failures()).isEmpty();
    assertThat(claims.stream().filter(RedisIdempotencyCache.ClaimResult::acquired).count())
        .isEqualTo(1L);
    assertThat(claims.stream()
        .filter(claim -> claim.status() == RedisIdempotencyCache.ClaimStatus.IN_PROGRESS)
        .count()).isEqualTo(7L);

    claims.stream().filter(RedisIdempotencyCache.ClaimResult::acquired).findFirst()
        .ifPresent(claim -> redisCache.release(CONCURRENT_KEY, claim));
  }

  @Test
  void setNxAllowsOnlyOneConcurrentTransferAndAllRequestsReceiveSameResponse() throws Exception {
    Set<Long> transactionIds = ConcurrentHashMap.newKeySet();

    ConcurrentExecutionResult result = concurrentExecutor.execute(8, startGate -> {
      startGate.readyAndAwaitStart();
      AccountTransferRespDTO response = redisService.transfer(CONCURRENT_KEY, request, senderId);
      transactionIds.add(response.getTransaction().getId());
    });

    Account withdraw = accountRepository.findByNumber("1111111111").orElseThrow();
    Account deposit = accountRepository.findByNumber("2222222222").orElseThrow();
    assertThat(result.failures()).isEmpty();
    assertThat(result.successCount()).isEqualTo(8);
    assertThat(transactionIds).hasSize(1);
    assertThat(transactionRepository.count()).isEqualTo(1L);
    assertThat(idempotencyRecordRepository.count()).isEqualTo(1L);
    assertThat(withdraw.getBalance()).isEqualTo(900L);
    assertThat(deposit.getBalance()).isEqualTo(1100L);
  }

  @Test
  void completedResponseIsReturnedFromRedisWithoutDatabaseRecord() {
    AccountTransferRespDTO first = redisService.transfer(RESPONSE_CACHE_KEY, request, senderId);

    idempotencyRecordRepository.deleteAll();
    transactionRepository.deleteAll();
    assertThat(idempotencyRecordRepository.count()).isZero();
    assertThat(transactionRepository.count()).isZero();

    AccountTransferRespDTO retry = redisService.transfer(RESPONSE_CACHE_KEY, request, senderId);

    assertThat(retry.getTransaction().getId()).isEqualTo(first.getTransaction().getId());
    assertThat(retry.getTransaction().getAmount()).isEqualTo(first.getTransaction().getAmount());
    assertThat(retry.getBalance()).isEqualTo(first.getBalance());
    assertThat(idempotencyRecordRepository.count()).isZero();
    assertThat(transactionRepository.count()).isZero();
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
}
