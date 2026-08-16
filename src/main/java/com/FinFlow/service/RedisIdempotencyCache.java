package com.FinFlow.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIdempotencyCache {

  private static final String KEY_PREFIX = "finflow:idempotency:transfer:";

  private final StringRedisTemplate redisTemplate;

  @Value("${finflow.idempotency.redis-enabled:true}")
  private boolean enabled;

  @Value("${finflow.idempotency.redis-ttl:24h}")
  private Duration ttl;

  public Optional<String> getRequestHash(String idempotencyKey) {
    if (!enabled) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(redisTemplate.opsForValue().get(cacheKey(idempotencyKey)));
    } catch (DataAccessException e) {
      log.warn("Redis 멱등성 캐시 조회 실패. DB 경로로 폴백합니다.", e);
      return Optional.empty();
    }
  }

  public void putCompleted(String idempotencyKey, String requestHash) {
    if (!enabled) {
      return;
    }
    try {
      redisTemplate.opsForValue().set(cacheKey(idempotencyKey), requestHash, ttl);
    } catch (DataAccessException e) {
      log.warn("Redis 멱등성 캐시 저장 실패. DB 기록은 유지됩니다.", e);
    }
  }

  public void evict(String idempotencyKey) {
    if (!enabled) {
      return;
    }
    try {
      redisTemplate.delete(cacheKey(idempotencyKey));
    } catch (DataAccessException e) {
      log.warn("Redis 멱등성 캐시 삭제 실패.", e);
    }
  }

  private String cacheKey(String idempotencyKey) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
      return KEY_PREFIX + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
    }
  }
}
