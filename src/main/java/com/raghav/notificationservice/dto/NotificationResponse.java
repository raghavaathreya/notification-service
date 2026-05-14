package com.raghav.notificationservice.dto;

import com.raghav.notificationservice.model.NotificationStatus;
import com.raghav.notificationservice.model.NotificationType;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationResponse {
    private UUID id;
    private String userId;
    private NotificationType type;
    private NotificationStatus status;
    private String recipient;
    private String subject;
    private String message;
    private LocalDateTime createdAt;
}
