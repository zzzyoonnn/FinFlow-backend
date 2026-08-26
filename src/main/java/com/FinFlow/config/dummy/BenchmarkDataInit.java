package com.FinFlow.config.dummy;

import com.FinFlow.domain.User;
import com.FinFlow.repository.AccountRepository;
import com.FinFlow.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("benchmark")
public class BenchmarkDataInit extends DummyObject {

  public static final int ACCOUNT_PAIR_COUNT = 20;

  @Bean
  CommandLineRunner benchmarkData(UserRepository userRepository,
      AccountRepository accountRepository) {
    return args -> {
      User user = userRepository.save(newUser("benchmark", "benchmark"));
      for (int index = 0; index < ACCOUNT_PAIR_COUNT; index++) {
        var withdraw = newAccount(String.valueOf(7_100_000_000L + index), user);
        var deposit = newAccount(String.valueOf(7_200_000_000L + index), user);
        withdraw.deposit(999_999_000L);
        deposit.deposit(999_999_000L);
        accountRepository.save(withdraw);
        accountRepository.save(deposit);
      }
    };
  }
}
