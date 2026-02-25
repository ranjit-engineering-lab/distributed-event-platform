package com.platform.shared.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Idempotency Guard — Redis-backed event deduplication.
 *
 * Problem: Kafka guarantees at-least-once delivery. A consumer may receive
 * the same message multiple times (network retries, rebalances, crashes).
 * Without deduplication, we'd process an order twice, charge a customer twice, etc.
 *
 * Solution: Before processing any event, check Redis for its event ID.
 * If found, skip (duplicate). If not found, set it and process.
 *
 * Key format:  idempotency:{topic}:{eventId}
 * TTL:         24 hours — long enough to catch duplicates, short enough not to bloat Redis
 *
 * Redis SET NX (Set if Not Exists) is atomic — no race condition between check and set.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /**
     * Check if an event has already been processed, and mark it as processing if not.
     * This is atomic — uses Redis SET NX.
     *
     * @param eventId  Unique event ID
     * @param topic    Kafka topic (for key namespacing)
     * @return true if this is a duplicate (already processed), false if new
     */
    public boolean isDuplicate(String eventId, String topic) {
        String key = KEY_PREFIX + topic + ":" + eventId;
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(key, "1", DEFAULT_TTL);

        if (Boolean.FALSE.equals(isNew)) {
            log.debug("Duplicate event detected and skipped: eventId={}, topic={}", eventId, topic);
            return true;
        }
        return false;
    }

    /**
     * Check idempotency with custom TTL.
     * Use longer TTL for long-running sagas.
     */
    public boolean isDuplicate(String eventId, String topic, Duration ttl) {
        String key = KEY_PREFIX + topic + ":" + eventId;
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
        return Boolean.FALSE.equals(isNew);
    }

    /**
     * Manually mark an event as processed.
     * Use when you want fine-grained control (e.g., mark after successful DB commit).
     */
    public void markProcessed(String eventId, String topic) {
        String key = KEY_PREFIX + topic + ":" + eventId;
        redisTemplate.opsForValue().set(key, "1", DEFAULT_TTL);
    }

    /**
     * Remove an idempotency key (e.g., for testing or manual replay).
     */
    public void remove(String eventId, String topic) {
        String key = KEY_PREFIX + topic + ":" + eventId;
        redisTemplate.delete(key);
    }
}
