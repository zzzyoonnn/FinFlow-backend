package com.FinFlow.repository;

import com.FinFlow.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

  // select * from account where number = :number
  Optional<Account> findByNumber(String number);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from Account a where a.number = :number")
  Optional<Account> findByNumberWithPessimisticWriteLock(@Param("number") String number);

  // select * from account where user_id = :id
  List<Account> findByUserId(Long id);

}
