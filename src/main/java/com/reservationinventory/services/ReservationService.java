/*
 * Copyright 2025 adityamehta.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.reservationinventory.services;

import com.reservationinventory.dto.ReservationItemRequestDTO;
import com.reservationinventory.dto.ReservationResponseDTO;
import com.reservationinventory.entity.Customer;
import com.reservationinventory.entity.Product;
import com.reservationinventory.entity.Reservation;
import com.reservationinventory.entity.ReservationItem;
import com.reservationinventory.entity.ReservationStatus;
import com.reservationinventory.exceptions.ResourceNotFoundException;
import com.reservationinventory.mapper.ReservationMapper;
import com.reservationinventory.repository.CustomerRepository;
import com.reservationinventory.repository.ReservationRepository;
import com.reservationinventory.services.redis.IdempotencyRedisService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@Slf4j
public class ReservationService {

    private final CustomerRepository customerRepository;
    private final ReservationRepository reservationRepository;
    private final ProductService productService;
    private final ReservationMapper reservationMapper;
    private final IdempotencyRedisService idempotencyRedisService;

    public ReservationService(
            CustomerRepository customerRepository,
            ReservationRepository reservationRepository,
            ProductService productService,
            ReservationMapper reservationMapper,
            IdempotencyRedisService idempotencyRedisService) {
        this.customerRepository = customerRepository;
        this.reservationRepository = reservationRepository;
        this.productService = productService;
        this.reservationMapper = reservationMapper;
        this.idempotencyRedisService = idempotencyRedisService;
    }

    public ReservationResponseDTO createReservation(UUID customerId, List<ReservationItemRequestDTO> reservationItemRequest) {
        String idempotencyKey = generateIdempotencyKey(customerId, reservationItemRequest);

        log.info("Creating reservation with idempotency key: {}", idempotencyKey);

        Optional<ReservationResponseDTO> existingResponse = idempotencyRedisService.getResponse(idempotencyKey);
        if (existingResponse.isPresent()) {
            log.info("Returning cached response for idempotency key: {}", idempotencyKey);
            return existingResponse.get();
        }

        if (!idempotencyRedisService.tryLock(idempotencyKey)) {
            log.info("Lock acquisition failed, waiting for result for idempotency key: {}", idempotencyKey);

            Optional<ReservationResponseDTO> waitResult = idempotencyRedisService.waitForResult(idempotencyKey);

            if (waitResult.isPresent()) {
                return waitResult.get();
            } else {
                log.warn("Wait for result failed, attempting to process again for idempotency key: {}", idempotencyKey);
                return handleFailedWait(customerId, reservationItemRequest, idempotencyKey);
            }
        }

        try {
            idempotencyRedisService.markAsProcessing(idempotencyKey);

            Optional<ReservationResponseDTO> doubleCheckResponse = idempotencyRedisService.getResponse(idempotencyKey);
            if (doubleCheckResponse.isPresent()) {
                log.info("Found response after acquiring lock for idempotency key: {}", idempotencyKey);
                return doubleCheckResponse.get();
            }

            ReservationResponseDTO response = processReservation(customerId, reservationItemRequest);

            idempotencyRedisService.storeResponseAndReleaseLock(idempotencyKey, response);

            log.info("Successfully created reservation with idempotency key: {}", idempotencyKey);
            return response;

        } catch (Exception e) {
            log.error("Error processing reservation with idempotency key: {}", idempotencyKey, e);

            idempotencyRedisService.releaseLock(idempotencyKey);
            idempotencyRedisService.removeProcessingMarker(idempotencyKey);

            throw e;
        }
    }

    public ReservationResponseDTO confirmReservation(UUID reservationId) {
        log.info("Confirming reservation with ID: {}", reservationId);

        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> {
                    log.error("Reservation not found with ID: {}", reservationId);
                    return new ResourceNotFoundException("Reservation not found with ID: " + reservationId);
                });

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("Reservation {} is already confirmed", reservationId);
            return reservationMapper.toResponseDTO(reservation);
        }

        if (reservation.getExpiresAt().isBefore(OffsetDateTime.now())) {
            log.warn("Reservation {} has expired", reservationId);
            reservation.setStatus(ReservationStatus.EXPIRED);
            reservationRepository.save(reservation);
            throw new RuntimeException("Reservation has expired and cannot be confirmed");
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            log.error("Reservation {} is not in PENDING status. Current status: {}", 
                    reservationId, reservation.getStatus());
            throw new RuntimeException("Reservation is not in PENDING status and cannot be confirmed");
        }

        try {
            Reservation confirmedReservation = processConfirmReservation(reservation);
            ReservationResponseDTO response = reservationMapper.toResponseDTO(confirmedReservation);
            
            log.info("Successfully confirmed reservation with ID: {}", reservationId);
            return response;

        } catch (Exception e) {
            log.error("Error confirming reservation with ID: {}", reservationId, e);
            throw new RuntimeException("Failed to confirm reservation: " + e.getMessage(), e);
        }
    }

    @Transactional
    public ReservationResponseDTO cancelReservation(UUID reservationId) {
        log.info("Cancelling reservation with ID: {}", reservationId);

        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> {
                    log.error("Reservation not found with ID: {}", reservationId);
                    return new ResourceNotFoundException("Reservation not found with ID: " + reservationId);
                });

        // Check if reservation can be cancelled
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            throw new RuntimeException("Cannot cancel confirmed reservation");
        }

        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            log.info("Reservation {} is already cancelled", reservationId);
            return reservationMapper.toResponseDTO(reservation);
        }

        try {
            // Release all reserved inventory
            for (ReservationItem item : reservation.getReservationItems()) {
                try {
                    productService.releaseReservation(item.getSku(), item.getQuantity());
                    log.debug("Released {} units of SKU: {} for cancelled reservation", 
                            item.getQuantity(), item.getSku());
                } catch (Exception e) {
                    log.error("Error releasing reservation for SKU: {} in reservation: {}", 
                            item.getSku(), reservationId, e);
                    // Continue with other items even if one fails
                }
            }

            // Update reservation status
            reservation.setStatus(ReservationStatus.CANCELLED);
            reservation.setCancelledAt(OffsetDateTime.now());
            Reservation savedReservation = reservationRepository.save(reservation);

            log.info("Successfully cancelled reservation with ID: {}", reservationId);
            return reservationMapper.toResponseDTO(savedReservation);

        } catch (Exception e) {
            log.error("Error cancelling reservation with ID: {}", reservationId, e);
            throw new RuntimeException("Failed to cancel reservation: " + e.getMessage(), e);
        }
    }

    private ReservationResponseDTO handleFailedWait(UUID customerId,
            List<ReservationItemRequestDTO> reservationItemRequest, String idempotencyKey) {

        if (idempotencyRedisService.tryLock(idempotencyKey)) {
            try {
                idempotencyRedisService.markAsProcessing(idempotencyKey);

                Optional<ReservationResponseDTO> response = idempotencyRedisService.getResponse(idempotencyKey);
                if (response.isPresent()) {
                    return response.get();
                }

                ReservationResponseDTO result = processReservation(customerId, reservationItemRequest);
                idempotencyRedisService.storeResponseAndReleaseLock(idempotencyKey, result);
                return result;

            } catch (Exception e) {
                idempotencyRedisService.releaseLock(idempotencyKey);
                idempotencyRedisService.removeProcessingMarker(idempotencyKey);
                throw e;
            }
        } else {
            throw new RuntimeException("Unable to process reservation due to concurrent processing issues");
        }
    }

    private ReservationResponseDTO processReservation(UUID customerId,
            List<ReservationItemRequestDTO> reservationItemRequest) {

        log.debug("Processing reservation for customer: {}", customerId);

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found."));

        Reservation reservation = Reservation.builder()
                .customer(customer)
                .status(ReservationStatus.PENDING)
                .expiresAt(OffsetDateTime.now().plusMinutes(15))
                .createdAt(OffsetDateTime.now())
                .build();

        reservation = reservationRepository.save(reservation);

        List<ReservationItem> reservationItems = new ArrayList<>();
        List<String> failedSkus = new ArrayList<>();

        for (ReservationItemRequestDTO item : reservationItemRequest) {
            try {
                Product product = productService.reserveInventory(item.getSku(), item.getQuantity());

                ReservationItem reservationItem = ReservationItem.builder()
                        .product(product)
                        .quantity(item.getQuantity())
                        .sku(item.getSku())
                        .reservation(reservation)
                        .build();

                reservationItems.add(reservationItem);

            } catch (Exception e) {
                log.error("Error reserving inventory for SKU: {} with quantity: {}",
                        item.getSku(), item.getQuantity(), e);
                failedSkus.add(item.getSku());
            }
        }

        // If any SKU failed to reserve, rollback all reservations
        if (!failedSkus.isEmpty()) {
            log.error("Failed to reserve inventory for SKUs: {}", failedSkus);
            
            // Release any successfully reserved items
            for (ReservationItem item : reservationItems) {
                try {
                    productService.releaseReservation(item.getSku(), item.getQuantity());
                } catch (Exception e) {
                    log.error("Error rolling back reservation for SKU: {}", item.getSku(), e);
                }
            }
            
            throw new RuntimeException("Failed to reserve inventory for SKUs: " + failedSkus);
        }

        reservation.setReservationItems(reservationItems);
        Reservation savedReservation = reservationRepository.save(reservation);

        return reservationMapper.toResponseDTO(savedReservation);
    }

    @Transactional
    private Reservation processConfirmReservation(Reservation reservation) {
        log.debug("Processing confirmation for reservation ID: {}", reservation.getId());

        List<String> failedSkus = new ArrayList<>();

        // Confirm all reservation items (move from reserved to sold)
        for (ReservationItem reservationItem : reservation.getReservationItems()) {
            try {
                productService.confirmReservation(reservationItem.getSku(), reservationItem.getQuantity());
                log.debug("Confirmed {} units of SKU: {} for reservation: {}", 
                        reservationItem.getQuantity(), reservationItem.getSku(), reservation.getId());
            } catch (Exception e) {
                log.error("Error confirming reservation for SKU: {} in reservation: {}", 
                        reservationItem.getSku(), reservation.getId(), e);
                failedSkus.add(reservationItem.getSku());
            }
        }

        // If any confirmations failed, this is a critical issue
        if (!failedSkus.isEmpty()) {
            log.error("Failed to confirm reservation for SKUs: {} in reservation: {}", 
                    failedSkus, reservation.getId());
            throw new RuntimeException("Failed to confirm reservation for SKUs: " + failedSkus);
        }

        // Update reservation status
        reservation.setConfirmedAt(OffsetDateTime.now());
        reservation.setStatus(ReservationStatus.CONFIRMED);
        
        return reservationRepository.save(reservation);
    }

    private String generateIdempotencyKey(UUID customerId, List<ReservationItemRequestDTO> items) {
        try {
            // Create a consistent hash based on customer ID and items
            StringBuilder sb = new StringBuilder();
            sb.append(customerId.toString());

            // Sort items to ensure consistent ordering
            items.stream()
                    .sorted(Comparator.comparing(ReservationItemRequestDTO::getSku))
                    .forEach(item -> {
                        sb.append(":").append(item.getSku()).append(":").append(item.getQuantity());
                    });

            // Use SHA-256 to create a consistent hash
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple concatenation if SHA-256 is not available
            log.warn("SHA-256 not available, using simple concatenation for idempotency key");
            return customerId.toString() + ":" + items.hashCode();
        }
    }

    @Scheduled(fixedRate = 300000)
    @Transactional
    public void cleanupExpiredReservations() {
        log.debug("Starting cleanup of expired reservations");

        try {
            List<Reservation> expiredReservations = reservationRepository
                    .findByStatusAndExpiresAtBefore(ReservationStatus.PENDING, OffsetDateTime.now());

            for (Reservation reservation : expiredReservations) {
                try {
                    log.info("Cleaning up expired reservation: {}", reservation.getId());

                    for (ReservationItem item : reservation.getReservationItems()) {
                        try {
                            productService.releaseReservation(item.getSku(), item.getQuantity());
                        } catch (Exception e) {
                            log.error("Error releasing inventory for expired reservation item: {}", 
                                    item.getId(), e);
                        }
                    }

                    reservation.setStatus(ReservationStatus.EXPIRED);
                    reservationRepository.save(reservation);

                } catch (Exception e) {
                    log.error("Error cleaning up expired reservation: {}", reservation.getId(), e);
                }
            }

            if (!expiredReservations.isEmpty()) {
                log.info("Cleaned up {} expired reservations", expiredReservations.size());
            }

        } catch (Exception e) {
            log.error("Error during expired reservations cleanup", e);
        }
    }
}