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
@Table(name = "idempotency_key")
public class IdempotencyKey {
    @Id
    @Column(nullable = false)
    private String key;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "response_json", columnDefinition = "jsonb")
    private String responseJson;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT NOW()")
    private OffsetDateTime createdAt;

    // Optional: Add relationship to Reservation if needed
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", insertable = false, updatable = false)
    private Reservation reservation;
}
