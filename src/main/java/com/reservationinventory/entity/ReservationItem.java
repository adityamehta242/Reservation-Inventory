package com.reservationinventory.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"reservation", "product"}) // Avoid circular references
@Entity
@Table(name = "reservation_item")
public class ReservationItem {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private Integer quantity;

    // Helper method to get reservation_id as UUID (useful for queries)
    public UUID getReservationId() {
        return reservation != null ? reservation.getId() : null;
    }

    // Helper method to get product_id as UUID (useful for queries)
    public UUID getProductId() {
        return product != null ? product.getId() : null;
    }
}