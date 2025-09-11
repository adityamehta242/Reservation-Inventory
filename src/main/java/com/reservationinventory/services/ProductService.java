package com.reservationinventory.services;

import com.reservationinventory.dto.AvailabilityResponse;
import com.reservationinventory.dto.InventoryUpdateRequestDTO;
import com.reservationinventory.entity.Product;
import com.reservationinventory.repository.ProductRepository;
import com.reservationinventory.services.redis.ProductRedisService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
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
        return productRepository.save(product);
    }

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public Product getProductById(UUID id) {
        return productRepository.getReferenceById(id);
    }

    public List<Product> addBatchProducts(List<Product> products) {
        return productRepository.saveAll(products);
    }

    public Integer checkAvailability(String sku) {
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new RuntimeException("Product not found with sku : " + sku));

        return product.getAvailable();

    }

    public List checkBatchAvailability(List<String> skus) {
        return productRepository.findAllBySkuIn(skus)
                .stream()
                .map(p -> new AvailabilityResponse(p.getSku(), p.getAvailable()))
                .toList();
    }

    @Transactional
    public Product updateInventory(String sku, InventoryUpdateRequestDTO request) {
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new RuntimeException("Product not found with sku " + sku));

        if (request.getAvailable() != null) {
            product.setAvailable(request.getAvailable());
        }
        if (request.getTotal() != null) {
            product.setTotal(request.getTotal());
        }
        if (request.getReserved() != null) {
            product.setReserved(request.getReserved());
        }

        // Optional since @Transactional will flush automatically
        // productRepository.save(product);
        return product;
    }

    @Transactional
    public Product reserveInventory(String sku, int quantity) {

        Optional<Product> cachedProduct = productRedisService.getCachedProduct(sku);

        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new RuntimeException("Product not found with SKU: " + sku));

        if (product.getAvailable() < quantity) {
            throw new RuntimeException("Not enough inventory available for product SKU: " + sku);
        }

        int newReserved = product.getReserved() + quantity;
        int newAvailable = product.getAvailable() - quantity;

        product.setReserved(newReserved);
        product.setAvailable(newAvailable);

        try {
            Product savedProduct = productRepository.save(product);

            // Update cache after successful save
            productRedisService.cacheProduct(sku, savedProduct);

            return savedProduct;
        } catch (OptimisticLockingFailureException e) {
            // Evict from cache on concurrency issues
            productRedisService.evictProduct(sku);
            throw new RuntimeException("Inventory was updated concurrently. Please try again.");
        }
    }

    // Method to get product with caching
    public Product getProductBySku(String sku) {
        // Try cache first
        Optional<Product> cached = productRedisService.getCachedProduct(sku);
        if (cached.isPresent()) {
            return cached.get();
        }

        // Load from database and cache
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new RuntimeException("Product not found with SKU: " + sku));

        productRedisService.cacheProduct(sku, product);
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
                throw new RuntimeException("Not enough reserved inventory for product SKU: " + sku + 
                        ". Reserved: " + product.getReserved() + ", Requested: " + quantity);
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
    
    

}
