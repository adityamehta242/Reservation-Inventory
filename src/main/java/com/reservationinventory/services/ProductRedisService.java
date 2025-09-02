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
import com.reservationinventory.entity.Product;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 *
 * @author adityamehta
 */
@Service
public class ProductRedisService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String PRODUCT_PREFIX = "product:";
    private static final Duration PRODUCT_TTL = Duration.ofMinutes(5);

    public ProductRedisService(RedisTemplate<String, Object> redisTemlate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemlate;
        this.objectMapper = objectMapper;
    }

    public void cacheProduct(String sku, Product product) {
        String redisKey = PRODUCT_PREFIX + sku;
        redisTemplate.opsForValue().set(redisKey, product, PRODUCT_TTL);
    }

    public Optional<Product> getCachedProduct(String sku) {
        String redisKey = PRODUCT_PREFIX + sku;
        Object cached = redisTemplate.opsForValue().get(redisKey);

        if (cached != null) {
            if (cached instanceof Product) {
                return Optional.of((Product) cached);
            }
            try {
                Product product = objectMapper.convertValue(cached, Product.class);
                return Optional.of(product);
            } catch (Exception e) {
                System.err.println("Error deserializing cached product: " + e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
    
    public void evictProduct(String sku) {
        String redisKey = PRODUCT_PREFIX + sku;
        redisTemplate.delete(redisKey);
    }
}
