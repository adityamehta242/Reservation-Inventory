package com.reservationinventory.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@Entity
@Table(name = "product", indexes = {
    @Index(name = "idx_product_sku", columnList = "sku")
})
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(length = 255, unique = true, nullable = false)
    private String sku;

    @Column(length = 100)
    private String name;

    @Column(nullable = false, columnDefinition = "INT DEFAULT 0")
    private Integer total = 0;

    @Column(nullable = false, columnDefinition = "INT DEFAULT 0")
    private Integer available = 0;

    @Column(nullable = false, columnDefinition = "INT DEFAULT 0")
    private Integer reserved = 0;

    @Version // Important for optimistic locking
    @Column(nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT NOW()")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT NOW()")
    private OffsetDateTime updatedAt;
}
