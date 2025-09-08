/*
 * Copyright 2025 adityamehta.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.reservationinventory.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reservationinventory.dto.ReservationResponseDTO;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

/**
 *
 * @author adityamehta
 */
@Service
@Slf4j
public class IdempotencyRedisService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String IDEMPOTENCY_PREFIX = "idempotency:";
    private static final String LOCK_PREFIX = "idempotency:lock:";
    private static final String PROCESSING_PREFIX = "idempotency:processing:";

    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(10);
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final Duration PROCESSING_TTL = Duration.ofMinutes(2);

    private static final int MAX_WAIT_ATTEMPTS = 30; // 30 seconds total wait
    private static final int WAIT_INTERVAL_MS = 1000; // 1 second between attempts

    public IdempotencyRedisService(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Attempts to acquire an idempotency lock atomically
     */
    public boolean tryLock(String idempotencyKey) {
        try {
            String lockKey = LOCK_PREFIX + idempotencyKey;
            Boolean success = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "processing", LOCK_TTL);

            if (Boolean.TRUE.equals(success)) {
                log.debug("Successfully acquired lock for idempotency key: {}", idempotencyKey);
                return true;
            }

            log.debug("Failed to acquire lock for idempotency key: {}", idempotencyKey);
            return false;

        } catch (Exception e) {
            log.error("Error acquiring lock for idempotency key: {}", idempotencyKey, e);
            return false;
        }
    }

    /**
     * Releases the idempotency lock
     */
    public void releaseLock(String idempotencyKey) {
        try {
            String lockKey = LOCK_PREFIX + idempotencyKey;
            redisTemplate.delete(lockKey);
            log.debug("Released lock for idempotency key: {}", idempotencyKey);
        } catch (Exception e) {
            log.error("Error releasing lock for idempotency key: {}", idempotencyKey, e);
        }
    }

    /**
     * Marks a request as being processed
     */
    public void markAsProcessing(String idempotencyKey) {
        try {
            String processingKey = PROCESSING_PREFIX + idempotencyKey;
            redisTemplate.opsForValue().set(processingKey, "processing", PROCESSING_TTL);
            log.debug("Marked as processing for idempotency key: {}", idempotencyKey);
        } catch (Exception e) {
            log.error("Error marking as processing for idempotency key: {}", idempotencyKey, e);
        }
    }

    /**
     * Checks if a request is currently being processed
     */
    public boolean isProcessing(String idempotencyKey) {
        try {
            String processingKey = PROCESSING_PREFIX + idempotencyKey;
            return Boolean.TRUE.equals(redisTemplate.hasKey(processingKey));
        } catch (Exception e) {
            log.error("Error checking processing status for idempotency key: {}", idempotencyKey, e);
            return false;
        }
    }

    /**
     * Removes the processing marker
     */
    public void removeProcessingMarker(String idempotencyKey) {
        try {
            String processingKey = PROCESSING_PREFIX + idempotencyKey;
            redisTemplate.delete(processingKey);
            log.debug("Removed processing marker for idempotency key: {}", idempotencyKey);
        } catch (Exception e) {
            log.error("Error removing processing marker for idempotency key: {}", idempotencyKey, e);
        }
    }

    /**
     * Stores the response atomically with lock release
     */
    public void storeResponseAndReleaseLock(String idempotencyKey, ReservationResponseDTO response) {
        try {
            redisTemplate.execute(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    String responseKey = IDEMPOTENCY_PREFIX + idempotencyKey;
                    String lockKey = LOCK_PREFIX + idempotencyKey;
                    String processingKey = PROCESSING_PREFIX + idempotencyKey;

                    operations.multi();
                    // Store the response
                    operations.opsForValue().set(responseKey, response, IDEMPOTENCY_TTL);
                    // Release the lock
                    operations.delete(lockKey);
                    // Remove processing marker
                    operations.delete(processingKey);

                    return operations.exec();
                }
            });

            log.debug("Stored response and released lock for idempotency key: {}", idempotencyKey);

        } catch (Exception e) {
            log.error("Error storing response for idempotency key: {}", idempotencyKey, e);
            // Ensure cleanup even if transaction fails
            releaseLock(idempotencyKey);
            removeProcessingMarker(idempotencyKey);
            throw new RuntimeException("Failed to store idempotency response", e);
        }
    }

    /**
     * Retrieves cached response with proper error handling
     */
    public Optional<ReservationResponseDTO> getResponse(String idempotencyKey) {
        try {
            String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
            Object cached = redisTemplate.opsForValue().get(redisKey);

            if (cached == null) {
                log.debug("No cached response found for idempotency key: {}", idempotencyKey);
                return Optional.empty();
            }

            if (cached instanceof ReservationResponseDTO) {
                log.debug("Found cached response for idempotency key: {}", idempotencyKey);
                return Optional.of((ReservationResponseDTO) cached);
            }

            // Try to deserialize
            ReservationResponseDTO response = objectMapper.convertValue(cached, ReservationResponseDTO.class);
            log.debug("Deserialized cached response for idempotency key: {}", idempotencyKey);
            return Optional.of(response);

        } catch (Exception e) {
            log.error("Error retrieving cached response for idempotency key: {}", idempotencyKey, e);
            return Optional.empty();
        }
    }

    /**
     * Waits for another thread to complete processing and return the result
     */
    public Optional<ReservationResponseDTO> waitForResult(String idempotencyKey) {
        log.debug("Waiting for result for idempotency key: {}", idempotencyKey);

        for (int attempt = 0; attempt < MAX_WAIT_ATTEMPTS; attempt++) {
            try {
                // Check if result is available
                Optional<ReservationResponseDTO> result = getResponse(idempotencyKey);
                if (result.isPresent()) {
                    log.debug("Found result after waiting for idempotency key: {}", idempotencyKey);
                    return result;
                }

                // Check if still processing
                if (!isProcessing(idempotencyKey)) {
                    log.warn("Processing stopped but no result found for idempotency key: {}", idempotencyKey);
                    break;
                }

                // Wait before next attempt
                Thread.sleep(WAIT_INTERVAL_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for result for idempotency key: {}", idempotencyKey);
                break;
            } catch (Exception e) {
                log.error("Error while waiting for result for idempotency key: {}", idempotencyKey, e);
            }
        }

        log.warn("Timeout waiting for result for idempotency key: {}", idempotencyKey);
        return Optional.empty();
    }

    /**
     * Cleanup utility methods
     */
    public void delete(String idempotencyKey) {
        try {
            String responseKey = IDEMPOTENCY_PREFIX + idempotencyKey;
            String lockKey = LOCK_PREFIX + idempotencyKey;
            String processingKey = PROCESSING_PREFIX + idempotencyKey;

            redisTemplate.delete(Arrays.asList(responseKey, lockKey, processingKey));
            log.debug("Deleted all keys for idempotency key: {}", idempotencyKey);

        } catch (Exception e) {
            log.error("Error deleting keys for idempotency key: {}", idempotencyKey, e);
        }
    }

    public boolean exists(String idempotencyKey) {
        try {
            String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
            return Boolean.TRUE.equals(redisTemplate.hasKey(redisKey));
        } catch (Exception e) {
            log.error("Error checking existence for idempotency key: {}", idempotencyKey, e);
            return false;
        }
    }
}
