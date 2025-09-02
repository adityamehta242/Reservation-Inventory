package com.reservationinventory.mapper;

import com.reservationinventory.dto.ReservationItemResponseDTO;
import com.reservationinventory.dto.ReservationResponseDTO;
import com.reservationinventory.entity.Customer;
import com.reservationinventory.entity.Product;
import com.reservationinventory.entity.Reservation;
import com.reservationinventory.entity.ReservationItem;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ReservationMapperImpl implements ReservationMapper {

    @Override
    public ReservationResponseDTO toResponseDTO(Reservation reservation) {
        if (reservation == null) {
            return null;
        }

        return ReservationResponseDTO.builder()
                .id(reservation.getId())
                .customerFirstName(reservation.getCustomer().getFirstName())
                .customerLastName(reservation.getCustomer().getLastName())
                .customerEmail(reservation.getCustomer().getEmail())
                .status(reservation.getStatus())
                .createdAt(reservation.getCreatedAt())
                .expiresAt(reservation.getExpiresAt())
                .confirmedAt(reservation.getConfirmedAt())
                .cancelledAt(reservation.getCancelledAt())
                .reservationItems(toReservationItemResponseDTOList(reservation.getReservationItems()))
                .build();
    }

    @Override
    public ReservationItemResponseDTO toReservationItemResponseDTO(ReservationItem reservationItem) {
        if (reservationItem == null) {
            return null;
        }

        return ReservationItemResponseDTO.builder()
                .id(reservationItem.getId())
                .sku(reservationItem.getSku())
                .quantity(reservationItem.getQuantity())
                .productName(reservationItem.getProduct() != null ? 
                           reservationItem.getProduct().getName() : null)
                .build();
    }

    // Helper method to convert list of ReservationItem to list of ReservationItemResponseDTO
    private List<ReservationItemResponseDTO> toReservationItemResponseDTOList(List<ReservationItem> reservationItems) {
        if (reservationItems == null) {
            return Collections.emptyList();
        }

        return reservationItems.stream()
                .map(this::toReservationItemResponseDTO)
                .collect(Collectors.toList());
    }
}