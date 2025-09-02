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
package com.reservationinventory.dto;

import com.reservationinventory.entity.ReservationStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 *
 * @author adityamehta
 */

@Data
@Builder
public class ReservationResponseDTO {
    private UUID id;
    private String customerFirstName;
    private String customerLastName;
    private String customerEmail;
    private ReservationStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime confirmedAt;
    private OffsetDateTime cancelledAt;
    private List<ReservationItemResponseDTO> reservationItems;
}
