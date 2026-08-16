package com.FinFlow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.FinFlow.config.dummy.DummyObject;
import com.FinFlow.domain.Account;
import com.FinFlow.domain.User;
import com.FinFlow.dto.account.AccountReqDTO.AccountTransferReqDTO;
import com.FinFlow.dto.account.AccountRespDTO.AccountTransferRespDTO;
import com.FinFlow.handler.ex.CustomApiException;
import com.FinFlow.repository.AccountRepository;
import com.FinFlow.repository.IdempotencyRecordRepository;
import com.FinFlow.repository.TransactionRepository;
import com.FinFlow.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("mysql")
@SpringBootTest
class IdempotentTransferServiceIntegrationTest extends DummyObject {

  @Autowired
  private IdempotentTransferService idempotentTransferService;
  @Autowired
  private IdempotencyRecordRepository idempotencyRecordRepository;
  @Autowired
  private TransactionRepository transactionRepository;
  @Autowired
  private AccountRepository accountRepository;
  @Autowired
  private UserRepository userRepository;

  private User sender;

  @BeforeEach
  void setUp() {
    idempotencyRecordRepository.deleteAll();
    transactionRepository.deleteAll();
    accountRepository.deleteAll();
    userRepository.deleteAll();

    sender = userRepository.save(newUser("sender", "sender"));
    User receiver = userRepository.save(newUser("receiver", "receiver"));
    accountRepository.save(newAccount("1111111111", sender));
    accountRepository.save(newAccount("2222222222", receiver));
  }

  @Test
  void sameKeyRetryReturnsOriginalResultAndTransfersOnlyOnce() {
    AccountTransferReqDTO request = transferRequest(500L);

    AccountTransferRespDTO first = idempotentTransferService.transfer("retry-key", request,
        sender.getId());
    AccountTransferRespDTO retry = idempotentTransferService.transfer("retry-key", request,
        sender.getId());

    Account withdraw = accountRepository.findByNumber("1111111111").orElseThrow();
    Account deposit = accountRepository.findByNumber("2222222222").orElseThrow();
    assertThat(withdraw.getBalance()).isEqualTo(500L);
    assertThat(deposit.getBalance()).isEqualTo(1500L);
    assertThat(transactionRepository.count()).isEqualTo(1L);
    assertThat(idempotencyRecordRepository.count()).isEqualTo(1L);
    assertThat(retry.getTransaction().getId()).isEqualTo(first.getTransaction().getId());
  }

  @Test
  void sameKeyWithDifferentRequestIsRejected() {
    idempotentTransferService.transfer("reused-key", transferRequest(100L), sender.getId());

    assertThatThrownBy(() -> idempotentTransferService.transfer(
        "reused-key", transferRequest(200L), sender.getId()))
        .isInstanceOf(CustomApiException.class)
        .hasMessage("Idempotency-Key가 다른 이체 요청에 이미 사용되었습니다.");

    assertThat(transactionRepository.count()).isEqualTo(1L);
  }

  private AccountTransferReqDTO transferRequest(Long amount) {
    AccountTransferReqDTO request = new AccountTransferReqDTO();
    request.setWithdrawNumber("1111111111");
    request.setDepositNumber("2222222222");
    request.setWithdrawPassword(1234L);
    request.setAmount(amount);
    request.setTransactionType("TRANSFER");
    return request;
  }
}
