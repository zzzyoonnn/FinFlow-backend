package com.FinFlow.service;

import com.FinFlow.dto.account.AccountReqDTO.AccountTransferReqDTO;
import com.FinFlow.dto.account.AccountRespDTO.AccountTransferRespDTO;
import com.FinFlow.handler.ex.CustomApiException;
import com.FinFlow.service.RedisIdempotencyCache.CachedResponse;
import com.FinFlow.service.RedisIdempotencyCache.ClaimResult;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedisIdempotentTransferService {

  private final IdempotentTransferService databaseService;
  private final RedisIdempotencyCache cache;

  @Value("${finflow.idempotency.wait-timeout:2s}")
  private Duration waitTimeout;

  @Value("${finflow.idempotency.poll-interval:25ms}")
  private Duration pollInterval;

  public AccountTransferRespDTO transfer(String idempotencyKey, AccountTransferReqDTO request,
      Long userId) {
    databaseService.validateKey(idempotencyKey);
    String requestHash = databaseService.requestHash(request, userId);

    Optional<AccountTransferRespDTO> completed = completedResponse(idempotencyKey, requestHash);
    if (completed.isPresent()) {
      return completed.get();
    }

    ClaimResult claim = cache.tryClaim(idempotencyKey, requestHash);
    if (claim.status() == RedisIdempotencyCache.ClaimStatus.CONFLICT) {
      throw reusedKeyException();
    }
    if (claim.status() == RedisIdempotencyCache.ClaimStatus.IN_PROGRESS) {
      return waitForCompletionOrFallback(idempotencyKey, requestHash, request, userId);
    }
    if (!claim.acquired()) {
      return executeDatabaseAndCache(idempotencyKey, requestHash, request, userId);
    }

    try {
      // 완료 저장과 잠금 해제 사이에 진입한 요청이 잠금을 다시 얻은 경우를 방어한다.
      completed = completedResponse(idempotencyKey, requestHash);
      if (completed.isPresent()) {
        return completed.get();
      }
      return executeDatabaseAndCache(idempotencyKey, requestHash, request, userId);
    } finally {
      cache.release(idempotencyKey, claim);
    }
  }

  private AccountTransferRespDTO waitForCompletionOrFallback(String idempotencyKey,
      String requestHash, AccountTransferReqDTO request, Long userId) {
    long deadline = System.nanoTime() + waitTimeout.toNanos();
    while (System.nanoTime() < deadline) {
      Optional<AccountTransferRespDTO> completed = completedResponse(idempotencyKey, requestHash);
      if (completed.isPresent()) {
        return completed.get();
      }
      LockSupport.parkNanos(pollInterval.toNanos());
      if (Thread.currentThread().isInterrupted()) {
        break;
      }
    }
    return executeDatabaseAndCache(idempotencyKey, requestHash, request, userId);
  }

  private AccountTransferRespDTO executeDatabaseAndCache(String idempotencyKey,
      String requestHash, AccountTransferReqDTO request, Long userId) {
    AccountTransferRespDTO response = databaseService.transfer(idempotencyKey, request, userId);
    cache.putCompleted(idempotencyKey, requestHash, response);
    return response;
  }

  private Optional<AccountTransferRespDTO> completedResponse(String idempotencyKey,
      String requestHash) {
    return cache.getCompleted(idempotencyKey).map(cached -> validateAndGet(cached, requestHash));
  }

  private AccountTransferRespDTO validateAndGet(CachedResponse cached, String requestHash) {
    if (!cached.requestHash().equals(requestHash)) {
      throw reusedKeyException();
    }
    return cached.response();
  }

  private CustomApiException reusedKeyException() {
    return new CustomApiException("Idempotency-Key가 다른 이체 요청에 이미 사용되었습니다.");
  }
}
