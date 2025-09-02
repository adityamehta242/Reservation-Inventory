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
import java.util.Optional;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 *
 * @author adityamehta
 */
@Service
public class IdempotencyRedisService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String IDEMPOTENCY_PREFIX = "idempotency:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(10);

    public IdempotencyRedisService(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void storeResponse(String idempotencyKey, ReservationResponseDTO response) {
        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        redisTemplate.opsForValue().set(redisKey, response, IDEMPOTENCY_TTL);
    }

    public Optional<ReservationResponseDTO> getResponse(String idempotencyKey) {
        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        Object cached = redisTemplate.opsForValue().get(redisKey);

        if (cached != null) {
            if (cached instanceof ReservationResponseDTO) {
                return Optional.of((ReservationResponseDTO) cached);
            }

            try {
                ReservationResponseDTO response = objectMapper.convertValue(cached, ReservationResponseDTO.class);
                return Optional.of(response);

            } catch (Exception e) {
                System.err.println("Error deserializing cached response: " + e.getMessage());
                return Optional.empty();
            }

        }

        return null;
    }

    public void delete(String idempotencyKey) {
        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        redisTemplate.delete(redisKey);
    }

    public boolean exists(String idempotencyKey) {
        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        return Boolean.TRUE.equals(redisTemplate.hasKey(redisKey));
    }

}
