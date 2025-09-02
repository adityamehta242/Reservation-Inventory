package com.reservationinventory.repository;

import com.reservationinventory.entity.ReservationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReservationItemRepository extends JpaRepository<ReservationItem, UUID> {
}
