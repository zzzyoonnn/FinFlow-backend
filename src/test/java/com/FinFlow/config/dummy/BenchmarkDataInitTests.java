package com.FinFlow.config.dummy;

import static org.assertj.core.api.Assertions.assertThat;

import com.FinFlow.repository.AccountRepository;
import com.FinFlow.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("benchmark")
class BenchmarkDataInitTests {

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private AccountRepository accountRepository;

  @Test
  void createsFixedBenchmarkUserAndAccountPairs() {
    assertThat(userRepository.findByUsername("benchmark")).isPresent();
    assertThat(accountRepository.count()).isEqualTo(BenchmarkDataInit.ACCOUNT_PAIR_COUNT * 2L);
    assertThat(accountRepository.findByNumber("7100000000")).isPresent();
    assertThat(accountRepository.findByNumber("7200000019")).isPresent();
  }
}
