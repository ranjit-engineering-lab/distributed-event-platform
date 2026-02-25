package com.platform.shared.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Saga State Store — Redis-backed persistence for saga state.
 *
 * The orchestrator is stateless. All saga state lives here.
 * This means:
 *  - Orchestrator instances can be scaled horizontally
 *  - Crash recovery: on restart, saga events resume from stored state
 *  - Observability: saga state can be inspected at any point
 *
 * Key format: saga:order:{correlationId}
 * TTL:        saga timeout + 5 minutes (cleanup after saga completes/fails)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaStateStore {

    private static final String KEY_PREFIX = "saga:order:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(35); // 30m timeout + 5m grace

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void save(SagaState state) {
        String key = buildKey(state.getCorrelationId());
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key, json, DEFAULT_TTL);
            log.debug("Saga state saved: correlationId={}, status={}", state.getCorrelationId(), state.getStatus());
        } catch (JsonProcessingException e) {
            throw new SagaStateException("Failed to serialize saga state: " + state.getCorrelationId(), e);
        }
    }

    public Optional<SagaState> load(String correlationId) {
        String key = buildKey(correlationId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, SagaState.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize saga state: correlationId={}", correlationId, e);
            return Optional.empty();
        }
    }

    public void delete(String correlationId) {
        redisTemplate.delete(buildKey(correlationId));
    }

    /**
     * Schedule deletion with a grace period (for debugging — state remains visible briefly).
     */
    public void scheduleDelete(String correlationId, Duration delay) {
        redisTemplate.expire(buildKey(correlationId), delay);
    }

    private String buildKey(String correlationId) {
        return KEY_PREFIX + correlationId;
    }

    public static class SagaStateException extends RuntimeException {
        public SagaStateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
