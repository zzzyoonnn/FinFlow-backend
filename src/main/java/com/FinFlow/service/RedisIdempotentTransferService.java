package com.FinFlow.service;

import com.FinFlow.dto.account.AccountReqDTO.AccountTransferReqDTO;
import com.FinFlow.dto.account.AccountRespDTO.AccountTransferRespDTO;
import com.FinFlow.handler.ex.CustomApiException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedisIdempotentTransferService {

  private final IdempotentTransferService databaseService;
  private final RedisIdempotencyCache cache;

  public AccountTransferRespDTO transfer(String idempotencyKey, AccountTransferReqDTO request,
      Long userId) {
    databaseService.validateKey(idempotencyKey);
    String requestHash = databaseService.requestHash(request, userId);
    Optional<String> cachedHash = cache.getRequestHash(idempotencyKey);

    if (cachedHash.isPresent()) {
      if (!cachedHash.get().equals(requestHash)) {
        throw new CustomApiException("Idempotency-Key가 다른 이체 요청에 이미 사용되었습니다.");
      }
      Optional<AccountTransferRespDTO> completed =
          databaseService.findCompleted(idempotencyKey, requestHash);
      if (completed.isPresent()) {
        return completed.get();
      }
      // DB가 초기화됐거나 캐시가 오래된 경우 캐시를 버리고 DB를 최종 기준으로 삼는다.
      cache.evict(idempotencyKey);
    }

    AccountTransferRespDTO response = databaseService.transfer(idempotencyKey, request, userId);
    cache.putCompleted(idempotencyKey, requestHash);
    return response;
  }
}
