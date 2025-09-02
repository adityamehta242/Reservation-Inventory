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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.reservationinventory.repository.IdempotencyKeyRepository;
import com.reservationinventory.repository.ReservationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.DialectOverride.Check;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 *
 * @author adityamehta
 */
@Service
public class ReservationService {

    private final CustomerRepository customerRepository;
    private final ProductService productService;
    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final IdempotencyRedisService idempotencyRedisService;

    private static final MessageDigest MD5_DIGEST;

    static {
        try {
            MD5_DIGEST = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    public ReservationService(CustomerRepository customerRepository, ProductService productService, ReservationRepository reservationRepository, ReservationMapper reservationMapper, IdempotencyKeyRepository idempotencyKeyRepository, ObjectMapper objectMapper , IdempotencyRedisService idempotencyRedisService) {
        this.customerRepository = customerRepository;
        this.productService = productService;
        this.reservationRepository = reservationRepository;
        this.reservationMapper = reservationMapper;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
        this.idempotencyRedisService = idempotencyRedisService;
    }

    @Transactional
    public ReservationResponseDTO createReservation(UUID customerId, List<ReservationItemRequestDTO> reservationItemRequest) {
        
        String idempotencyKey = generateIdempotencyKey(customerId, reservationItemRequest);
        
        // Check Redis for existing response
        Optional<ReservationResponseDTO> existingResponse = idempotencyRedisService.getResponse(idempotencyKey);
        if (existingResponse.isPresent()) {
            return existingResponse.get();
        }


        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found."));
        
        Reservation reservation =new Reservation().builder()
                .customer(customer)
                .status(ReservationStatus.PENDING)
                .expiresAt(OffsetDateTime.now().plusMinutes(15)).build();
        
        reservation = reservationRepository.save(reservation);
        
        List<ReservationItem> reservationItems = new ArrayList<>();
        
        for(ReservationItemRequestDTO item : reservationItemRequest)
        {
            Product product = productService.reserveInventory(item.getSku(), item.getQuantity());
            
            ReservationItem rs = new ReservationItem().builder()
                    .product(product)
                    .quantity(item.getQuantity())
                    .sku(item.getSku())
                    .reservation(reservation)
                    .build();
            
            reservationItems.add(rs);
        }
        
        reservation.setReservationItems(reservationItems);
        Reservation saveReservation = reservationRepository.save(reservation);
        
        ReservationResponseDTO response = reservationMapper.toResponseDTO(reservation); 
        idempotencyRedisService.storeResponse(idempotencyKey, response);
       
        return response;
    }

    ReservationResponseDTO confirmReservation(UUID reservationId) {
        return null;
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
    
    /**
     * Generate idempotency key from customer ID and request data
     */
    private String generateIdempotencyKey(UUID customerId, List<ReservationItemRequestDTO> request) {
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            String combined = customerId + ":" + requestJson;
            
            synchronized (MD5_DIGEST) {
                byte[] hashBytes = MD5_DIGEST.digest(combined.getBytes(StandardCharsets.UTF_8));
                return bytesToHex(hashBytes);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize request for idempotency key", e);
        }
    }
    
    /**
     * Convert byte array to hex string
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
