package com.FinFlow.service;

import com.FinFlow.dto.account.AccountRespDTO.AccountTransferRespDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIdempotencyCache {

  private static final String KEY_PREFIX = "finflow:idempotency:transfer:";
  private static final String LOCK_SUFFIX = ":processing";
  private static final String RESULT_SUFFIX = ":completed";
  private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
      "if redis.call('get', KEYS[1]) == ARGV[1] then "
          + "return redis.call('del', KEYS[1]) else return 0 end",
      Long.class);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  @Value("${finflow.idempotency.redis-enabled:true}")
  private boolean enabled;

  @Value("${finflow.idempotency.redis-ttl:24h}")
  private Duration completedTtl;

  @Value("${finflow.idempotency.processing-ttl:30s}")
  private Duration processingTtl;

  public Optional<CachedResponse> getCompleted(String idempotencyKey) {
    if (!enabled) {
      return Optional.empty();
    }
    try {
      String json = redisTemplate.opsForValue().get(resultKey(idempotencyKey));
      if (json == null) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readValue(json, CachedResponse.class));
    } catch (DataAccessException e) {
      log.warn("Redis 완료 응답 조회 실패. DB 경로로 폴백합니다.", e);
      return Optional.empty();
    } catch (JsonProcessingException e) {
      log.warn("Redis 완료 응답 역직렬화 실패. 캐시를 제거합니다.", e);
      evict(idempotencyKey);
      return Optional.empty();
    }
  }

  public ClaimResult tryClaim(String idempotencyKey, String requestHash) {
    if (!enabled) {
      return ClaimResult.unavailable();
    }
    String lockValue = requestHash + ":" + UUID.randomUUID();
    try {
      Boolean acquired = redisTemplate.opsForValue()
          .setIfAbsent(lockKey(idempotencyKey), lockValue, processingTtl);
      if (Boolean.TRUE.equals(acquired)) {
        return ClaimResult.acquired(lockValue);
      }
      String existing = redisTemplate.opsForValue().get(lockKey(idempotencyKey));
      if (existing != null && existing.length() >= 64
          && !existing.substring(0, 64).equals(requestHash)) {
        return ClaimResult.conflict();
      }
      return ClaimResult.inProgress();
    } catch (DataAccessException e) {
      log.warn("Redis 처리 잠금 획득 실패. DB 경로로 폴백합니다.", e);
      return ClaimResult.unavailable();
    }
  }

  public void putCompleted(String idempotencyKey, String requestHash,
      AccountTransferRespDTO response) {
    if (!enabled) {
      return;
    }
    try {
      String json = objectMapper.writeValueAsString(new CachedResponse(requestHash, response));
      redisTemplate.opsForValue().set(resultKey(idempotencyKey), json, completedTtl);
    } catch (DataAccessException | JsonProcessingException e) {
      log.warn("Redis 완료 응답 저장 실패. DB 기록은 유지됩니다.", e);
    }
  }

  public void release(String idempotencyKey, ClaimResult claim) {
    if (!enabled || !claim.acquired()) {
      return;
    }
    try {
      redisTemplate.execute(RELEASE_SCRIPT, List.of(lockKey(idempotencyKey)), claim.lockValue());
    } catch (DataAccessException e) {
      log.warn("Redis 처리 잠금 해제 실패. TTL 만료로 자동 해제됩니다.", e);
    }
  }

  public void evict(String idempotencyKey) {
    if (!enabled) {
      return;
    }
    try {
      redisTemplate.delete(List.of(lockKey(idempotencyKey), resultKey(idempotencyKey)));
    } catch (DataAccessException e) {
      log.warn("Redis 멱등성 캐시 삭제 실패.", e);
    }
  }

  private String lockKey(String idempotencyKey) {
    return baseKey(idempotencyKey) + LOCK_SUFFIX;
  }

  private String resultKey(String idempotencyKey) {
    return baseKey(idempotencyKey) + RESULT_SUFFIX;
  }

  private String baseKey(String idempotencyKey) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
      return KEY_PREFIX + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
    }
  }

  public record CachedResponse(String requestHash, AccountTransferRespDTO response) {
  }

  public record ClaimResult(ClaimStatus status, String lockValue) {

    static ClaimResult acquired(String lockValue) {
      return new ClaimResult(ClaimStatus.ACQUIRED, lockValue);
    }

    static ClaimResult inProgress() {
      return new ClaimResult(ClaimStatus.IN_PROGRESS, null);
    }

    static ClaimResult conflict() {
      return new ClaimResult(ClaimStatus.CONFLICT, null);
    }

    static ClaimResult unavailable() {
      return new ClaimResult(ClaimStatus.UNAVAILABLE, null);
    }

    public boolean acquired() {
      return status == ClaimStatus.ACQUIRED;
    }
  }

  public enum ClaimStatus {
    ACQUIRED,
    IN_PROGRESS,
    CONFLICT,
    UNAVAILABLE
  }
}
