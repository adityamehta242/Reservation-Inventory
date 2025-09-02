package com.reservationinventory.repository;

import com.reservationinventory.entity.Product;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {
        Optional<Product> findBySku(String sku);
        List<Product> findAllBySkuIn(List<String> skus);
}
