package com.FinFlow.dto.transaction;

import com.FinFlow.domain.Account;
import com.FinFlow.domain.Transaction;
import com.FinFlow.util.CustomDateUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;

public class TransactionRespDTO {

  @Getter
  @Setter
  public static class TransactionListRespDTO {
    private List<TransactionDTO> transactionList = new ArrayList<>();

    public TransactionListRespDTO(List<Transaction> transactionList, Account account) {
      this.transactionList = transactionList.stream().map((transaction) -> new TransactionDTO(transaction, account.getNumber())).collect(
              Collectors.toList());
    }

    @Getter
    @Setter
    public class TransactionDTO {
      private Long id;
      private String transactionType;
      private Long amount;
      private String sender;
      private String receiver;
      private String tel;
      private String createdAt;
      private Long balance;

      public TransactionDTO(Transaction transaction, String accountNumber) {
        this.id = transaction.getId();
        this.transactionType = transaction.getTransaction_type().getValue();
        this.amount = transaction.getAmount();
        this.sender = transaction.getSender();
        this.receiver = transaction.getReceiver();
        this.createdAt = CustomDateUtil.toStringFormat(transaction.getCreatedAt());
        this.tel = transaction.getTel() == null ? "" : transaction.getTel();

        // 계좌의 입출금 내역(출금 계좌 = null, 입금 계좌 = 값), (출금 계좌 = 값, 입금 계좌 = null)
        if (transaction.getDepositAccount() == null) {
          this.balance = transaction.getWithdrawAccountBalance();
        } else if (transaction.getWithdrawAccount() == null) {
          this.balance = transaction.getDepositAccount().getBalance();
        } else {
          // 계좌의 입출금 내역(출금 계좌 = 값, 입금 계좌 = 값)
          if (accountNumber.equals(transaction.getDepositAccount().getNumber())) {
            this.balance = transaction.getDepositAccountBalance();
          } else {
            this.balance = transaction.getWithdrawAccountBalance();
          }
        }

      }
    }
  }
}
