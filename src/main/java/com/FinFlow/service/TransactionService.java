package com.FinFlow.service;

import com.FinFlow.domain.Account;
import com.FinFlow.domain.Transaction;
import com.FinFlow.dto.transaction.TransactionRespDTO.TransactionListRespDTO;
import com.FinFlow.handler.ex.CustomApiException;
import com.FinFlow.repository.AccountRepository;
import com.FinFlow.repository.TransactionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionService {
  private final TransactionRepository transactionRepository;
  private final AccountRepository accountRepository;

  public TransactionListRespDTO getAccountTransactions(Long userId, String accountNumber, String transaction_type, int page) {
    Account account = accountRepository.findByNumber(accountNumber)
            .orElseThrow(() -> new CustomApiException("해당 계좌를 찾을 수 없습니다."));

    account.checkOwner(userId);

    List<Transaction> transactionList = transactionRepository.findTransactionList(account.getId(), transaction_type, page);
    return new TransactionListRespDTO(transactionList, account);
  }
}
