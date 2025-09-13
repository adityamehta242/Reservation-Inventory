package com.reservationinventory.repository;

import com.reservationinventory.entity.Reservation;
import com.reservationinventory.entity.ReservationStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, OffsetDateTime expiredBefore);
    List<Reservation> findByCustomerIdAndStatus(UUID customerId, ReservationStatus status);
    List<Reservation> findByStatus(ReservationStatus status);
}