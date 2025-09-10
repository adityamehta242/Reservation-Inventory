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
package com.reservationinventory.services.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reservationinventory.entity.Product;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 *
 * @author adityamehta
 */
@Service
@Slf4j
public class ProductRedisService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String PRODUCT_PREFIX = "product:";
    private static final String LOCK_PREFIX = "product:lock:";
    private static final String PROCESSING_PREFIX = "product:processing:";
    private static final String INVENTORY_PREFIX = "inventory:";

    private static final Duration PRODUCT_TTL = Duration.ofMinutes(10);
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final Duration PROCESSING_TTL = Duration.ofMinutes(2);

    private static final int MAX_WAIT_ATTEMPTS = 30; // 30 seconds total wait
    private static final int WAIT_INTERVAL_MS = 1000; // 1 second between attempts

    public ProductRedisService(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean tryLock(String sku) {
        try {
            String lockkey = LOCK_PREFIX + sku;
            Boolean success = redisTemplate.opsForValue()
                    .setIfAbsent(lockkey, "inventory-update", LOCK_TTL);

            if (Boolean.TRUE.equals(success)) {
                log.debug("Successfully acquired inventory lock for SKU: {}", sku);
                return true;
            }

            log.debug("Failed to acquire inventory lock for SKU: {}", sku);
            return false;

        } catch (Exception e) {
            log.error("Error acquiring inventory lock for SKU: {}", sku, e);
            return false;
        }
    }

    public void releaseLock(String sku) {
        try {
            String lockKey = LOCK_PREFIX + sku;
            redisTemplate.delete(lockKey);
            log.debug("Released inventory lock for SKU: {}", sku);

        } catch (Exception e) {
            log.error("Error releasing inventory lock for SKU: {}", sku, e);
        }
    }

    public void markAsProcessing(String sku) {
        try {
            String processingKey = PROCESSING_PREFIX + sku;
            redisTemplate.opsForValue().set(processingKey, "processing", PROCESSING_TTL);
            log.debug("Marked as processing for SKU: {}", sku);
        } catch (Exception e) {
            log.error("Error marking as processing for SKU: {}", sku, e);
        }
    }

    public boolean isProcessing(String sku) {
        try {
            String processingKey = PROCESSING_PREFIX + sku;
            return Boolean.TRUE.equals(redisTemplate.hasKey(processingKey));
        } catch (Exception e) {
            log.error("Error checking processing status for SKU: {}", sku, e);
            return false;
        }
    }

    public void removeProcessingMarker(String sku) {
        try {
            String processingKey = PROCESSING_PREFIX + sku;
            redisTemplate.delete(processingKey);
            log.debug("Removed processing marker for SKU: {}", sku);
        } catch (Exception e) {
            log.error("Error removing processing marker for SKU: {}", sku, e);
        }
    }

    public void cacheProduct(String sku, Product product) {
        try {
            String productKey = PRODUCT_PREFIX + sku;
            redisTemplate.opsForValue().set(productKey, product, PRODUCT_TTL);
            log.debug("Cached product for SKU: {}", sku);
        } catch (Exception e) {
            log.error("Error caching product for SKU: {}", sku, e);
        }
    }

    public Optional<Product> getCachedProduct(String sku) {
        try {
            String productKey = PRODUCT_PREFIX + sku;
            Object cached = redisTemplate.opsForValue().get(productKey);
            
            if (cached == null) {
                log.debug("No cached product found for SKU: {}", sku);
                return Optional.empty();
            }
            
            if (cached instanceof Product) {
                log.debug("Found cached product for SKU: {}", sku);
                return Optional.of((Product) cached);
            }
            
            // Try to deserialize
            Product product = objectMapper.convertValue(cached, Product.class);
            log.debug("Deserialized cached product for SKU: {}", sku);
            return Optional.of(product);
            
        } catch (Exception e) {
            log.error("Error retrieving cached product for SKU: {}", sku, e);
            return Optional.empty();
        }
    }
    
        public void evictProduct(String sku) {
        try {
            String productKey = PRODUCT_PREFIX + sku;
            redisTemplate.delete(productKey);
            log.debug("Evicted cached product for SKU: {}", sku);
        } catch (Exception e) {
            log.error("Error evicting cached product for SKU: {}", sku, e);
        }
    }
    

}
