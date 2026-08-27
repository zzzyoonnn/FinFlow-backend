package com.FinFlow.service;

import com.FinFlow.domain.Account;
import com.FinFlow.domain.IdempotencyRecord;
import com.FinFlow.domain.Transaction;
import com.FinFlow.domain.TransactionEnum;
import com.FinFlow.event.TransactionCompletedEvent;
import com.FinFlow.dto.account.AccountReqDTO.AccountTransferReqDTO;
import com.FinFlow.dto.account.AccountRespDTO.AccountTransferRespDTO;
import com.FinFlow.handler.ex.CustomApiException;
import com.FinFlow.repository.AccountRepository;
import com.FinFlow.repository.IdempotencyRecordRepository;
import com.FinFlow.repository.OutboxEventRepository;
import com.FinFlow.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferTransactionService {

  private final AccountRepository accountRepository;
  private final TransactionRepository transactionRepository;
  private final IdempotencyRecordRepository idempotencyRecordRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final TransactionAuditService transactionAuditService;

  @Value("${finflow.kafka.outbox-enabled:true}")
  private boolean outboxEnabled;

  @Transactional
  public AccountTransferRespDTO execute(String idempotencyKey, String requestHash,
      AccountTransferReqDTO request, Long userId) {
    IdempotencyRecord record = new IdempotencyRecord(idempotencyKey, requestHash);
    idempotencyRecordRepository.saveAndFlush(record);

    if (request.getWithdrawNumber().equals(request.getDepositNumber())) {
      throw new CustomApiException("입출금계좌가 동일할 수 없습니다.");
    }
    if (request.getAmount() <= 0L) {
      throw new CustomApiException("0원 이하의 금액을 이체할 수 없습니다.");
    }

    Account withdrawAccount;
    Account depositAccount;
    if (request.getWithdrawNumber().compareTo(request.getDepositNumber()) < 0) {
      withdrawAccount = lockAccount(request.getWithdrawNumber(), "출금 계좌를 찾을 수 없습니다.");
      depositAccount = lockAccount(request.getDepositNumber(), "입금 계좌를 찾을 수 없습니다.");
    } else {
      depositAccount = lockAccount(request.getDepositNumber(), "입금 계좌를 찾을 수 없습니다.");
      withdrawAccount = lockAccount(request.getWithdrawNumber(), "출금 계좌를 찾을 수 없습니다.");
    }

    withdrawAccount.checkOwner(userId);
    withdrawAccount.checkSamePassword(request.getWithdrawPassword());
    withdrawAccount.withdraw(request.getAmount());
    depositAccount.deposit(request.getAmount());

    Transaction transaction = transactionRepository.save(Transaction.builder()
        .withdrawAccount(withdrawAccount)
        .depositAccount(depositAccount)
        .withdrawAccountBalance(withdrawAccount.getBalance())
        .depositAccountBalance(depositAccount.getBalance())
        .amount(request.getAmount())
        .transaction_type(TransactionEnum.TRANSFER)
        .sender(request.getWithdrawNumber())
        .receiver(request.getDepositNumber())
        .build());
    record.complete(transaction);
    transactionAuditService.recordSynchronously(transaction);
    if (outboxEnabled) {
      outboxEventRepository.save(TransactionCompletedEvent.toOutbox(transaction));
    }

    return new AccountTransferRespDTO(withdrawAccount, transaction);
  }

  private Account lockAccount(String number, String message) {
    return accountRepository.findByNumberWithPessimisticWriteLock(number)
        .orElseThrow(() -> new CustomApiException(message));
  }
}
