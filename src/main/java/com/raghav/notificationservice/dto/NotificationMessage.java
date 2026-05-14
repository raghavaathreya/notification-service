package com.raghav.notificationservice.dto;

import com.raghav.notificationservice.model.NotificationType;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationMessage implements Serializable {
    private UUID notificationId;
    private String userId;
    private NotificationType type;
    private String recipient;
    private String subject;
    private String content;
    private boolean personalize;
}
