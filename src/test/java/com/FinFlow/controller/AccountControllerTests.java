package com.FinFlow.controller;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.FinFlow.config.dummy.DummyObject;
import com.FinFlow.domain.Account;
import com.FinFlow.domain.Transaction;
import com.FinFlow.domain.User;
import com.FinFlow.dto.account.AccountReqDTO.AccountDepositReqDTO;
import com.FinFlow.dto.account.AccountReqDTO.AccountSaveReqDto;
import com.FinFlow.dto.account.AccountReqDTO.AccountTransferReqDTO;
import com.FinFlow.dto.account.AccountReqDTO.AccountWithdrawReqDTO;
import com.FinFlow.handler.ex.CustomApiException;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
public class AccountControllerTests extends DummyObject {

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
  public void saveAccount_test() throws Exception {
    // given
    AccountSaveReqDto accountSaveReqDto = new AccountSaveReqDto();
    accountSaveReqDto.setNumber("9999999999");
    accountSaveReqDto.setPassword(1234L);
    String requestBody = objectMapper.writeValueAsString(accountSaveReqDto);
    System.out.println(requestBody);

    // when
    ResultActions resultActions = mockMvc.perform(post("/api/s/account").content(requestBody).contentType(
            MediaType.APPLICATION_JSON));
    String responseBody = resultActions.andReturn().getResponse().getContentAsString();
    System.out.println(responseBody);

    // then
    resultActions.andExpect(status().isCreated());
  }

  @Test
  @WithUserDetails(value = "Alice", setupBefore = TestExecutionEvent.TEST_EXECUTION)
  public void findUserAccount_test() throws Exception {
    // given

    // when
    ResultActions resultActions = mockMvc.perform(get("/api/s/account/loginUser"));
    String responseBody = resultActions.andReturn().getResponse().getContentAsString();
    System.out.println(responseBody);

    // then
    resultActions.andExpect(status().isOk());
  }

  @Test
  @WithUserDetails(value = "Alice", setupBefore = TestExecutionEvent.TEST_EXECUTION)
  public void deleteAccount_test() throws Exception {
    // given
    String number = "1111111111";
//    String number = "2222222222";

    // when
    ResultActions resultActions = mockMvc.perform(delete("/api/s/account/" + number));
    String responseBody = resultActions.andReturn().getResponse().getContentAsString();
    System.out.println(responseBody);

    // then
    assertThrows(CustomApiException.class, () -> accountRepository.findByNumber(number).orElseThrow(
            () -> new CustomApiException("계좌를 찾을 수 없습니다.")
    ));
  }

  @Test
  public void depositAccount_test() throws Exception {
    // given
    AccountDepositReqDTO accountDepositReqDTO = new AccountDepositReqDTO();
    accountDepositReqDTO.setNumber("1111111111");
    accountDepositReqDTO.setAmount(500L);
    accountDepositReqDTO.setTransactionType("DEPOSIT");
    accountDepositReqDTO.setTel("010-1111-1111");

    String requestBody = objectMapper.writeValueAsString(accountDepositReqDTO);
    System.out.println(requestBody);

    // when
    ResultActions resultActions = mockMvc.perform(post("/api/account/deposit").content(requestBody).contentType(
            MediaType.APPLICATION_JSON));
    String responseBody = resultActions.andReturn().getResponse().getContentAsString();
    System.out.println(responseBody);

    // then
    resultActions.andExpect(status().isCreated());
  }

  @Test
  @WithUserDetails(value = "Alice", setupBefore = TestExecutionEvent.TEST_EXECUTION)
  public void withdrawAccount_test() throws Exception {
    // given
    AccountWithdrawReqDTO accountWithdrawReqDTO = new AccountWithdrawReqDTO();
    accountWithdrawReqDTO.setNumber("1111111111");
    accountWithdrawReqDTO.setPassword(1234L);
    accountWithdrawReqDTO.setAmount(500L);
    accountWithdrawReqDTO.setTransactionType("WITHDRAW");

    String requestBody = objectMapper.writeValueAsString(accountWithdrawReqDTO);
    System.out.println(requestBody);

    // when
    ResultActions resultActions = mockMvc.perform(post("/api/s/account/withdraw").content(requestBody).contentType(
            MediaType.APPLICATION_JSON));
    String responseBody = resultActions.andReturn().getResponse().getContentAsString();
    System.out.println(responseBody);

    // then
    resultActions.andExpect(status().isCreated());
  }

  @Test
  @WithUserDetails(value = "Alice", setupBefore = TestExecutionEvent.TEST_EXECUTION)
  public void transferAccount_test() throws Exception {
    // given
    AccountTransferReqDTO accountTransferReqDTO = new AccountTransferReqDTO();
    accountTransferReqDTO.setWithdrawNumber("1111111111");
    accountTransferReqDTO.setDepositNumber("2222222222");
    accountTransferReqDTO.setWithdrawPassword(1234L);
    accountTransferReqDTO.setAmount(500L);
    accountTransferReqDTO.setTransactionType("TRANSFER");

    String requestBody = objectMapper.writeValueAsString(accountTransferReqDTO);
    System.out.println(requestBody);

    // when
    ResultActions resultActions = mockMvc.perform(post("/api/s/account/transfer").content(requestBody).contentType(
            MediaType.APPLICATION_JSON));
    String responseBody = resultActions.andReturn().getResponse().getContentAsString();
    System.out.println(responseBody);

    // then
    resultActions.andExpect(status().isCreated());
  }

  @Test
  @WithUserDetails(value = "Alice", setupBefore = TestExecutionEvent.TEST_EXECUTION)
  public void findDetailAccount_test() throws Exception {
    // given
    String number = "1111111111";
    String page = "0";

    // when
    ResultActions resultActions = mockMvc
            .perform(get("/api/s/account/" + number).param("page", page));
    String responseBody = resultActions.andReturn().getResponse().getContentAsString();
    System.out.println(responseBody);

    // then
    resultActions.andExpect(MockMvcResultMatchers.jsonPath("$.data.transactionList[0].balance").value(900L));
    resultActions.andExpect(MockMvcResultMatchers.jsonPath("$.data.transactionList[1].balance").value(800L));
    resultActions.andExpect(MockMvcResultMatchers.jsonPath("$.data.transactionList[2].balance").value(700L));
    resultActions.andExpect(MockMvcResultMatchers.jsonPath("$.data.transactionList[3].balance").value(800L));
  }
}
