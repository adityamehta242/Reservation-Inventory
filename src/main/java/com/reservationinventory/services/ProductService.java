package com.reservationinventory.services;

import com.reservationinventory.dto.AvailabilityResponse;
import com.reservationinventory.dto.InventoryUpdateRequestDTO;
import com.reservationinventory.entity.Product;
import com.reservationinventory.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

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

}
