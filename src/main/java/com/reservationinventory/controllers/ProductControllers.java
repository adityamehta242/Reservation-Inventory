package com.reservationinventory.controllers;

import com.reservationinventory.dto.InventoryUpdateRequestDTO;
import com.reservationinventory.entity.Product;
import com.reservationinventory.services.CustomerService;
import com.reservationinventory.services.ProductService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/product")
public class ProductControllers {

    private final ProductService productService;
    private final CustomerService customerService;

    public ProductControllers(ProductService productService, CustomerService customerService) {
        this.productService = productService;
        this.customerService = customerService;
    }

    @PostMapping
    public Product addProduct(@RequestBody Product product) {
        return productService.addProduct(product);
    }

    @GetMapping
    public List<Product> getAllProducts() {
        return productService.getAllProducts();
    }

    @GetMapping("{id}")
    public Product getProductById(@PathVariable UUID id) {
        return productService.getProductById(id);
    }

    @PostMapping("batch")
    public List<Product> addProducts(@RequestBody List<Product> products) {
        return productService.addBatchProducts(products);
    }

    @PostMapping("/reserve")
    public ResponseEntity<String> reserveInventory(@RequestParam String sku, @RequestParam int quantity) {
        try {
            Product updatedProduct = productService.reserveInventory(sku, quantity);
            return new ResponseEntity<>("Inventory reserved successfully for product SKU: " + sku, HttpStatus.OK);
        } catch (RuntimeException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    @GetMapping("/{sku}/availability")
    public ResponseEntity<?> checkAvailability(@PathVariable String sku) {
        try {
            Integer availability = productService.checkAvailability(sku);
            return ResponseEntity.ok(availability);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("check-availability")
    public ResponseEntity<?> checkBatchAvailability(@RequestBody List<String> skus) {
        try {
            return ResponseEntity.ok(productService.checkBatchAvailability(skus));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());

        }
    }

    @PutMapping("/{sku}/inventory")
    public ResponseEntity<?> updateInventory(@PathVariable String sku, @RequestBody InventoryUpdateRequestDTO request) {
        try {
            return ResponseEntity.ok(productService.updateInventory(sku, request));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

}
