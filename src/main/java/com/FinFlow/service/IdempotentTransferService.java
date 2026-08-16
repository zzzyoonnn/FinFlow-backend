package com.FinFlow.service;

import com.FinFlow.domain.IdempotencyRecord;
import com.FinFlow.domain.Transaction;
import com.FinFlow.dto.account.AccountReqDTO.AccountTransferReqDTO;
import com.FinFlow.dto.account.AccountRespDTO.AccountTransferRespDTO;
import com.FinFlow.handler.ex.CustomApiException;
import com.FinFlow.repository.IdempotencyRecordRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IdempotentTransferService {

  private final TransferTransactionService transferTransactionService;
  private final IdempotencyRecordRepository idempotencyRecordRepository;

  public AccountTransferRespDTO transfer(String idempotencyKey, AccountTransferReqDTO request,
      Long userId) {
    validateKey(idempotencyKey);
    String requestHash = requestHash(request, userId);

    try {
      return transferTransactionService.execute(idempotencyKey, requestHash, request, userId);
    } catch (DataIntegrityViolationException duplicateKey) {
      IdempotencyRecord existing = idempotencyRecordRepository.findCompletedByKey(idempotencyKey)
          .orElseThrow(() -> duplicateKey);
      if (!existing.getRequestHash().equals(requestHash)) {
        throw new CustomApiException("Idempotency-Key가 다른 이체 요청에 이미 사용되었습니다.");
      }
      Transaction transaction = existing.getTransaction();
      return new AccountTransferRespDTO(transaction.getWithdrawAccount(), transaction);
    }
  }

  private void validateKey(String key) {
    if (key == null || key.isBlank() || key.length() > 100) {
      throw new CustomApiException("Idempotency-Key는 1자 이상 100자 이하여야 합니다.");
    }
  }

  private String requestHash(AccountTransferReqDTO request, Long userId) {
    String canonical = userId + "|" + request.getWithdrawNumber() + "|"
        + request.getDepositNumber() + "|" + request.getWithdrawPassword() + "|"
        + request.getAmount() + "|" + request.getTransactionType();
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
    }
  }
}
