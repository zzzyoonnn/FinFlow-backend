package com.FinFlow.dto.account;

import com.FinFlow.domain.Account;
import com.FinFlow.domain.Transaction;
import com.FinFlow.domain.User;
import com.FinFlow.util.CustomDateUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;

public class AccountRespDTO {

  @Getter
  @Setter
  public static class AccountSaveRespDto {
    private Long id;
    private String number;
    private Long balance;

    public AccountSaveRespDto(Account account) {
      this.id = account.getId();
      this.number = account.getNumber();
      this.balance = account.getBalance();
    }
  }

  @Getter
  @Setter
  public static class AccountListRespDTO {
    private String fullname;
    private List<AccountDTO> accountList = new ArrayList<>();

    public AccountListRespDTO(User user, List<Account> accountList) {
      this.fullname = user.getFullname();
      //this.accountList = accountList.stream().map((account) -> new AccountDTO(account)).collect(Collectors.toList()));
      this.accountList = accountList.stream().map(AccountDTO::new).collect(Collectors.toList());
      // [account, account, account, ...]
    }

    @Getter
    @Setter
    public class AccountDTO {
      private Long id;
      private String number;
      private Long balance;

      public AccountDTO(Account account) {
        this.id = account.getId();
        this.number = account.getNumber();
        this.balance = account.getBalance();
      }
    }
  }

  @Getter
  @Setter
  public static class AccountDepositRespDTO {
    private Long id;
    private String number;
    private TransactionDTO transaction;

    public AccountDepositRespDTO(Account account, Transaction transaction) {
      this.id = account.getId();
      this.number = account.getNumber();
      this.transaction = new TransactionDTO(transaction);
    }

    @Getter
    @Setter
    public class TransactionDTO {
      private Long id;
      private String transactionType;
      private String sender;
      private String receiver;
      private Long amount;

      @JsonIgnore
      private Long depositAccountBalance;   // 클라이언트에게 전달 X(서비스단에서 테스트 용도)
      private String tel;
      private String createdAt;


      public TransactionDTO(Transaction transaction) {
        this.id = transaction.getId();
        this.transactionType = transaction.getTransaction_type().getValue();
        this.sender = transaction.getSender();
        this.receiver = transaction.getReceiver();
        this.amount = transaction.getAmount();
        this.depositAccountBalance = transaction.getDepositAccountBalance();
        this.tel = transaction.getTel();
        this.createdAt = CustomDateUtil.toStringFormat(transaction.getCreatedAt());
      }
    }
  }

  // DTO가 똑같아도 재사용 X
  // 나중에 출금할 때 DTO가 조금 달라져야 한다면 DTO를 공유하는 모든 메서드에 영향감
  // 독립적으로 만들기
  @Getter
  @Setter
  public static class AccountWithdrawRespDTO {
    private Long id;
    private String number;
    private Long balance;
    private TransactionDTO transaction;

    public AccountWithdrawRespDTO(Account account, Transaction transaction) {
      this.id = account.getId();
      this.number = account.getNumber();
      this.balance = account.getBalance();
      this.transaction = new TransactionDTO(transaction);
    }

    @Getter
    @Setter
    public class TransactionDTO {
      private Long id;
      private String transactionType;
      private String sender;
      private String receiver;
      private Long amount;
      private String createdAt;


      public TransactionDTO(Transaction transaction) {
        this.id = transaction.getId();
        this.transactionType = transaction.getTransaction_type().getValue();
        this.sender = transaction.getSender();
        this.receiver = transaction.getReceiver();
        this.amount = transaction.getAmount();
        this.createdAt = CustomDateUtil.toStringFormat(transaction.getCreatedAt());
      }
    }
  }

  @Getter
  @Setter
  public static class AccountTransferRespDTO {
    private Long id;
    private String number;
    private Long balance;
    private TransactionDTO transaction;

    public AccountTransferRespDTO(Account account, Transaction transaction) {
      this.id = account.getId();
      this.number = account.getNumber();
      this.balance = account.getBalance();
      this.transaction = new TransactionDTO(transaction);
    }

    @Getter
    @Setter
    public class TransactionDTO {
      private Long id;
      private String transactionType;
      private String sender;
      private String receiver;
      private Long amount;

      @JsonIgnore
      Long depositAccountBalance;

      private String createdAt;


      public TransactionDTO(Transaction transaction) {
        this.id = transaction.getId();
        this.transactionType = transaction.getTransaction_type().getValue();
        this.sender = transaction.getSender();
        this.receiver = transaction.getReceiver();
        this.amount = transaction.getAmount();
        this.createdAt = CustomDateUtil.toStringFormat(transaction.getCreatedAt());
      }
    }
  }

  @Getter
  @Setter
  public static class AccountDetailRespDto {
    private Long id;
    private String number; // 계좌번호
    private Long balance; // 그 계좌의 최종 잔액
    private List<TransactionDto> transactionList = new ArrayList<>();

    public AccountDetailRespDto(Account account, List<Transaction> transactions) {
      this.id = account.getId();
      this.number = account.getNumber();
      this.balance = account.getBalance();
      this.transactionList = transactions.stream()
              .map((transaction) -> new TransactionDto(transaction, account.getNumber()))
              .collect(Collectors.toList());
    }

    @Getter
    @Setter
    public class TransactionDto {
      private Long id;
      private String transactionType;
      private Long amount;

      private String sender;
      private String reciver;

      private String tel;
      private String createdAt;
      private Long balance;

      public TransactionDto(Transaction transaction, String accountNumber) {
        this.id = transaction.getId();
        this.transactionType = transaction.getTransaction_type().getValue();
        this.amount = transaction.getAmount();
        this.sender = transaction.getSender();
        this.reciver = transaction.getReceiver();
        this.createdAt = CustomDateUtil.toStringFormat(transaction.getCreatedAt());
        this.tel = transaction.getTel() == null ? "없음" : transaction.getTel();

        if (transaction.getDepositAccount() == null) {
          this.balance = transaction.getWithdrawAccountBalance();
        } else if (transaction.getWithdrawAccount() == null) {
          this.balance = transaction.getDepositAccountBalance();
        } else {
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
