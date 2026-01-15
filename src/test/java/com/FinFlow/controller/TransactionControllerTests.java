package com.FinFlow.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.FinFlow.config.dummy.DummyObject;
import com.FinFlow.domain.Account;
import com.FinFlow.domain.Transaction;
import com.FinFlow.domain.User;
import com.FinFlow.repository.AccountRepository;
import com.FinFlow.repository.TransactionRepository;
import com.FinFlow.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
public class TransactionControllerTests extends DummyObject {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private TransactionRepository transactionRepository;

  @Autowired
  private EntityManager entityManager;

  @BeforeEach
  public void setUp() throws Exception {
    dataSetting();
    entityManager.clear();
  }

  private void dataSetting() {
    User Alice = userRepository.save(newUser("Alice", "Alice"));
    User Bob = userRepository.save(newUser("Bob", "Bob"));
    User Charlie = userRepository.save(newUser("Charlie", "Charlie"));
    User Admin = userRepository.save(newUser("Admin", "Admin"));

    Account alicesAccount = accountRepository.save(newAccount("1111111111", Alice));
    Account bobAccount = accountRepository.save(newAccount("2222222222", Bob));
    Account charlieAccount = accountRepository.save(newAccount("3333333333", Charlie));
    Account adminAccount = accountRepository.save(newAccount("4444444444", Admin));

    Transaction withdrawTransaction1 = transactionRepository
            .save(newWithdrawTransaction(alicesAccount, accountRepository));
    Transaction depositTransaction1 = transactionRepository
            .save(newDepositTransaction(bobAccount, accountRepository));
    Transaction transferTransaction1 = transactionRepository
            .save(newTransferTransaction(alicesAccount, bobAccount, accountRepository));
    Transaction transferTransaction2 = transactionRepository
            .save(newTransferTransaction(alicesAccount, charlieAccount, accountRepository));
    Transaction transferTransaction3 = transactionRepository
            .save(newTransferTransaction(bobAccount, alicesAccount, accountRepository));
  }

  @Test
  @WithUserDetails(value = "Alice", setupBefore = TestExecutionEvent.TEST_EXECUTION)
  public void getAccountTransactions_test() throws Exception {
    // given
    String number = "1111111111";
    String transaction_type = "ALL";
    String page = "0";

    // when
    ResultActions resultActions = mockMvc.perform(get("/api/s/account/" + number + "/transaction").param("transaction_type", transaction_type).param("page", page));
    String responseBody = resultActions.andReturn().getResponse().getContentAsString();
    System.out.println(responseBody);

    // then
    resultActions.andExpect(jsonPath("$.data.transactionList[0].balance").value(900L));
    resultActions.andExpect(jsonPath("$.data.transactionList[1].balance").value(800L));
    resultActions.andExpect(jsonPath("$.data.transactionList[2].balance").value(700L));
    resultActions.andExpect(jsonPath("$.data.transactionList[3].balance").value(800L));
  }
}
