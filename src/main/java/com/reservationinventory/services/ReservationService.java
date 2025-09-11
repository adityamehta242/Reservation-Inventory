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

import com.reservationinventory.services.redis.IdempotencyRedisService;
import com.reservationinventory.dto.ReservationItemRequestDTO;
import com.reservationinventory.dto.ReservationResponseDTO;
import com.reservationinventory.entity.Customer;
import com.reservationinventory.entity.Product;
import com.reservationinventory.entity.Reservation;
import com.reservationinventory.entity.ReservationItem;
import com.reservationinventory.entity.ReservationStatus;
import com.reservationinventory.exceptions.ReservationCancelledException;
import com.reservationinventory.exceptions.ReservationExpiredException;
import com.reservationinventory.exceptions.ResourceNotFoundException;
import com.reservationinventory.mapper.ReservationMapper;
import com.reservationinventory.repository.CustomerRepository;
import com.reservationinventory.repository.ReservationRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 *
 * @author adityamehta
 */
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
                .build();

        reservation = reservationRepository.save(reservation);

        List<ReservationItem> reservationItems = new ArrayList<>();

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
                throw new RuntimeException("Failed to reserve inventory for SKU: " + item.getSku(), e);
            }
        }

        reservation.setReservationItems(reservationItems);
        Reservation savedReservation = reservationRepository.save(reservation);

        return reservationMapper.toResponseDTO(savedReservation);
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
            throw new ReservationExpiredException("Reservation has expired and cannot be confirmed");
        }

        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            log.error("Reservation {} is in CANCELLED status.",
                    reservationId);
            throw new ReservationCancelledException("Reservation" + reservationId + "is in CANCELLED status.");
        }

        try {
            Reservation confirmReservation = processConfirmReservation(reservation);
        } catch (Exception e) {
        }
        return null;

    }

    private Reservation processConfirmReservation(Reservation reservation) {
        log.debug("Processing confirmation for reservation ID: {}", reservation.getId());

        List<String> failedSkus = new ArrayList<>();

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

        if (!failedSkus.isEmpty()) {
            log.error("Failed to confirm reservation for SKUs: {} in reservation: {}",
                    failedSkus, reservation.getId());
            throw new RuntimeException("Failed to confirm reservation for SKUs: " + failedSkus);
        }

        reservation.setConfirmedAt(OffsetDateTime.now());
        reservation.setStatus(ReservationStatus.CONFIRMED);

        return reservationRepository.save(reservation);

    }

    ReservationResponseDTO cancelReservation(UUID reservationId) {

        return null;

    }

    ReservationResponseDTO getReservationById(UUID reservationId) {

        return null;

    }

    List<ReservationResponseDTO> getReservationsByCustomerId(UUID customerId) {

        return null;

    }

    ReservationResponseDTO extendReservation(UUID reservationId, int additionalHours) {

        return null;

    }
}
