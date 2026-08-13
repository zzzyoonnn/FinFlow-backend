package com.FinFlow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.FinFlow.config.dummy.DummyObject;
import com.FinFlow.domain.Account;
import com.FinFlow.domain.User;
import com.FinFlow.dto.account.AccountReqDTO.AccountTransferReqDTO;
import com.FinFlow.repository.AccountRepository;
import com.FinFlow.repository.TransactionRepository;
import com.FinFlow.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("mysql")
@SpringBootTest
class AccountTransactionRollbackIntegrationTest extends DummyObject {

  @Autowired
  private AccountService accountService;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @MockitoBean
  private TransactionRepository transactionRepository;

  private Account withdrawAccount;
  private Account depositAccount;

  @BeforeEach
  void setUp() {
    User sender = userRepository.save(newUser("sender", "송금자"));
    User receiver = userRepository.save(newUser("receiver", "수금자"));
    withdrawAccount = accountRepository.save(newAccount("1111111111", sender));
    depositAccount = accountRepository.save(newAccount("2222222222", receiver));
  }

  @AfterEach
  void tearDown() {
    accountRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  void transfer_rollsBackBalancesAndHistory_whenTransactionHistorySaveFails() {
    AccountTransferReqDTO request = new AccountTransferReqDTO();
    request.setWithdrawNumber(withdrawAccount.getNumber());
    request.setDepositNumber(depositAccount.getNumber());
    request.setWithdrawPassword(1234L);
    request.setAmount(500L);
    request.setTransactionType("TRANSFER");

    given(transactionRepository.save(any()))
            .willThrow(new DataIntegrityViolationException("거래내역 저장 실패"));

    assertThatThrownBy(() -> accountService.transferAccount(request, withdrawAccount.getUser().getId()))
            .isInstanceOf(DataIntegrityViolationException.class);

    Account persistedWithdrawAccount = accountRepository.findById(withdrawAccount.getId()).orElseThrow();
    Account persistedDepositAccount = accountRepository.findById(depositAccount.getId()).orElseThrow();
    Integer transactionCount = jdbcTemplate.queryForObject(
            "select count(*) from account_transaction", Integer.class);

    assertThat(persistedWithdrawAccount.getBalance()).isEqualTo(1000L);
    assertThat(persistedDepositAccount.getBalance()).isEqualTo(1000L);
    assertThat(transactionCount).isZero();
  }
}
