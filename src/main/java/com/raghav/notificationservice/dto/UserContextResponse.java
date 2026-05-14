package com.raghav.notificationservice.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserContextResponse {
    private String userId;
    private String vectorId;
    private int dimension;
    private String status;  // "upserted", "failed"
    private String message;
}
