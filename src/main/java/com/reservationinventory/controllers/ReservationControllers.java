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
package com.reservationinventory.controllers;

import com.reservationinventory.dto.CreateReservationRequestDTO;
import com.reservationinventory.dto.ReservationResponseDTO;
import com.reservationinventory.services.ReservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 *
 * @author adityamehta
 */
@RestController
@RequestMapping("/api/reservations")
@Validated
public class ReservationControllers {

    private ReservationService reservationService;

    public ReservationControllers(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    public ResponseEntity<ReservationResponseDTO> createReservation(@RequestBody CreateReservationRequestDTO request) {
        ReservationResponseDTO reservation = reservationService.createReservation(request.getCustomerId(),request.getItems());
        return ResponseEntity.ok(reservation);
    }
    
//    @PutMapping("/{id}/confirm")
//    public ResponseEntity<ReservationResponseDTO> confirmReservation(@PathVariable UUID id) {
//        // Business logic: Validate → Confirm → Update Inventory → Return Response
//    }
//    
//    @PutMapping("/{id}/cancel")
//    public ResponseEntity<ReservationResponseDTO> cancelReservation(@PathVariable UUID id) {
//        // Business logic: Validate → Cancel → Release Inventory → Return Response
//    }
//    
//    @GetMapping("/{id}")
//    public ResponseEntity<ReservationResponseDTO> getReservation(@PathVariable UUID id) {
//        // Business logic: Find → Validate Existence → Return Response
//    }
//    
//    @GetMapping("/customer/{customerId}")
//    public ResponseEntity<List<ReservationResponseDTO>> getCustomerReservations(@PathVariable UUID customerId) {
//        // Business logic: Validate Customer → Query Reservations → Return List
//    }
//    
//    @PutMapping("/{id}/extend")
//    public ResponseEntity<ReservationResponseDTO> extendReservation(
//            @PathVariable UUID id,
//            @RequestParam @Min(1) @Max(24) int additionalHours) {
//        // Business logic: Validate → Check Limits → Extend → Return Response
//    }
}
