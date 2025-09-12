package com.reservationinventory.services;

import com.reservationinventory.dto.AvailabilityResponse;
import com.reservationinventory.dto.InventoryUpdateRequestDTO;
import com.reservationinventory.entity.Product;
import com.reservationinventory.exceptions.ResourceNotFoundException;
import com.reservationinventory.repository.ProductRepository;
import com.reservationinventory.services.redis.ProductRedisService;
import java.util.ArrayList;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;
import java.util.function.Function;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@Slf4j
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductRedisService productRedisService;

    public ProductService(ProductRepository productRepository, ProductRedisService productRedisService) {
        this.productRepository = productRepository;
        this.productRedisService = productRedisService;
    }

    public Product addProduct(Product product) {
        Product saveProduct = productRepository.save(product);
        productRedisService.cacheProduct(saveProduct.getSku(), saveProduct);
        return saveProduct;
    }

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public Product getProductById(UUID id) {
        return productRepository.getReferenceById(id);
    }

    public List<Product> addBatchProducts(List<Product> products) {
        List<Product> savedProducts = productRepository.saveAll(products);

        Map<String, Product> productMap = savedProducts.stream()
                .collect(Collectors.toMap(Product::getSku, Function.identity()));
        productRedisService.batchCacheProducts(productMap);

        return savedProducts;
    }

    public Integer checkAvailability(String sku) {

        Optional<ProductRedisService.InventoryInfo> cachedInventory = productRedisService.getCachedInventory(sku);

        if (cachedInventory.isPresent()) {
            log.debug("Retrieved availability from cache for SKU: {}", sku);
            return cachedInventory.get().getAvailable();
        }

        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with sku : " + sku));

        productRedisService.cacheProduct(sku, product);
        productRedisService.cacheInventory(sku, product.getAvailable(),
                product.getReserved(), product.getTotal());

        return product.getAvailable();

    }

    public List checkBatchAvailability(List<String> skus) {
        List<AvailabilityResponse> responses = new ArrayList<>();
        List<String> uncachedSkus = new ArrayList<>();

        for (String sku : skus) {
            Optional<ProductRedisService.InventoryInfo> cached
                    = productRedisService.getCachedInventory(sku);

            if (cached.isPresent()) {
                responses.add(new AvailabilityResponse(sku, cached.get().getAvailable()));
            } else {
                uncachedSkus.add(sku);
            }
        }

        if (!uncachedSkus.isEmpty()) {
            List<Product> products = productRepository.findAllBySkuIn(uncachedSkus);

            Map<String, Product> productMap = products.stream()
                    .collect(Collectors.toMap(Product::getSku, Function.identity()));

            // Cache the loaded products
            productRedisService.batchCacheProducts(productMap);

            // Add to responses and cache inventory
            for (Product product : products) {
                responses.add(new AvailabilityResponse(product.getSku(), product.getAvailable()));
                productRedisService.cacheInventory(product.getSku(),
                        product.getAvailable(), product.getReserved(), product.getTotal());
            }

            // Add not found responses for missing SKUs
            Set<String> foundSkus = products.stream().map(Product::getSku).collect(Collectors.toSet());
            for (String sku : uncachedSkus) {
                if (!foundSkus.contains(sku)) {
                    responses.add(new AvailabilityResponse(sku, 0));
                }
            }
        }

        return responses;
    }

    @Transactional
    public Product updateInventory(String sku, InventoryUpdateRequestDTO request) {

        if (!productRedisService.tryLock(sku)) {
            if (productRedisService.waitForInventoryOperation(sku)) {
                if (!productRedisService.tryLock(sku)) {
                    throw new RuntimeException("Unable to acquire lock for inventory update of SKU: " + sku);
                }
            } else {
                throw new RuntimeException("Timeout waiting for inventory operation for SKU: " + sku);
            }
        }

        try {
            productRedisService.markAsProcessing(sku);

            Product product = productRepository.findBySku(sku)
                    .orElseThrow(() -> new RuntimeException("Product not found with sku: " + sku));

            if (request.getAvailable() != null) {
                product.setAvailable(request.getAvailable());
            }
            if (request.getTotal() != null) {
                product.setTotal(request.getTotal());
            }
            if (request.getReserved() != null) {
                product.setReserved(request.getReserved());
            }

            Product savedProduct = productRepository.save(product);

            // Update cache atomically with lock release
            productRedisService.updateInventoryAndCache(sku, savedProduct);

            log.info("Successfully updated inventory for SKU: {}", sku);
            return savedProduct;

        } catch (Exception e) {
            log.error("Error updating inventory for SKU: {}", sku, e);
            productRedisService.releaseLock(sku);
            productRedisService.removeProcessingMarker(sku);
            throw e;
        }
    }

    @Transactional
    public Product reserveInventory(String sku, int quantity) {

        if (!productRedisService.isProcessing(sku)) {
            if (!productRedisService.waitForInventoryOperation(sku)) {
                if (!productRedisService.tryLock(sku)) {
                    throw new RuntimeException("Unable to acquire lock for inventory reservation of SKU: " + sku);
                }
            } else {
                throw new RuntimeException("Timeout waiting for inventory operation for SKU: " + sku);
            }
        }

        try {
            productRedisService.markAsProcessing(sku);

            Product product = productRepository.findBySku(sku)
                    .orElseThrow(() -> new RuntimeException("Product not found with SKU: " + sku));

            if (product.getAvailable() < quantity) {
                throw new RuntimeException("Not enough inventory available for product SKU: " + sku
                        + ". Available: " + product.getAvailable() + ", Requested: " + quantity);
            }

            int newReserved = product.getReserved() + quantity;
            int newAvailable = product.getAvailable() - quantity;

            product.setReserved(newReserved);
            product.setAvailable(newAvailable);

            Product savedProduct = productRepository.save(product);

            productRedisService.updateInventoryAndCache(sku, savedProduct);

            log.info("Successfully reserved {} units of SKU: {}", quantity, sku);
            return savedProduct;

        } catch (OptimisticLockingFailureException e) {
            log.error("Optimistic locking failure for SKU: {}", sku, e);
            productRedisService.evictProduct(sku);
            productRedisService.releaseLock(sku);
            productRedisService.removeProcessingMarker(sku);
            throw new RuntimeException("Inventory was updated concurrently. Please try again for SKU: " + sku);
        } catch (Exception e) {
            log.error("Error reserving inventory for SKU: {}", sku, e);
            productRedisService.releaseLock(sku);
            productRedisService.removeProcessingMarker(sku);
            throw e;
        }
    }

    public Product getProductBySku(String sku) {

        Optional<Product> cached = productRedisService.getCachedProduct(sku);
        if (cached.isPresent()) {
            log.debug("Retrieved product from cache for SKU: {}", sku);
            return cached.get();
        }

        // Load from database and cache
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new RuntimeException("Product not found with SKU: " + sku));

        productRedisService.cacheProduct(sku, product);
        productRedisService.cacheInventory(sku, product.getAvailable(),
                product.getReserved(), product.getTotal());

        return product;
    }

    public Product confirmReservation(String sku, int quantity) {
        if (!productRedisService.tryLock(sku)) {

            if (productRedisService.waitForInventoryOperation(sku)) {
                if (!productRedisService.tryLock(sku)) {
                    throw new RuntimeException("Unable to acquire lock for inventory confirmation of SKU: " + sku);
                }
            } else {
                throw new RuntimeException("Timeout waiting for inventory operation for SKU: " + sku);
            }
        }

        try {
            Product product = productRepository.findBySku(sku)
                    .orElseThrow(() -> new RuntimeException("Product not found with SKU: " + sku));

            if (product.getReserved() < quantity) {
                throw new RuntimeException("Not enough reserved inventory for product SKU: " + sku
                        + ". Reserved: " + product.getReserved() + ", Requested: " + quantity);
            }

            int newReserved = product.getReserved() - quantity;
            product.setReserved(newReserved);

            Product savedProduct = productRepository.save(product);

            productRedisService.updateInventoryAndCache(sku, savedProduct);

            log.info("Successfully confirmed reservation of {} units for SKU: {}", quantity, sku);
            return savedProduct;

        } catch (Exception e) {
            log.error("Error confirming reservation for SKU: {}", sku, e);
            productRedisService.releaseLock(sku);
            productRedisService.removeProcessingMarker(sku);
            throw e;
        }
    }

    public Product releaseReservation(String sku, int quantity) {
        if (!productRedisService.tryLock(sku)) {
            if (productRedisService.waitForInventoryOperation(sku)) {
                if (!productRedisService.tryLock(sku)) {
                    throw new RuntimeException("Unable to acquire lock for releasing reservation of SKU: " + sku);
                }
            } else {
                throw new RuntimeException("Timeout waiting for inventory operation for SKU: " + sku);
            }
        }

        try {

            Product product = productRepository.findBySku(sku)
                    .orElseThrow(() -> new RuntimeException("Product not found with SKU: " + sku));

            if (product.getReserved() < quantity) {
                throw new RuntimeException("Not enough reserved inventory to release for product SKU: " + sku
                        + ". Reserved: " + product.getReserved() + ", Requested: " + quantity);
            }

            int newReserved = product.getReserved() - quantity;
            int newAvailable = product.getAvailable() + quantity;

            product.setReserved(newReserved);
            product.setAvailable(newAvailable);

            Product savedProduct = productRepository.save(product);

            productRedisService.updateInventoryAndCache(sku, savedProduct);

            log.info("Successfully released reservation of {} units for SKU: {}", quantity, sku);
            return savedProduct;

        } catch (Exception e) {

            log.error("Error releasing reservation for SKU: {}", sku, e);
            productRedisService.releaseLock(sku);
            productRedisService.removeProcessingMarker(sku);
            throw e;
        }
    }

}
