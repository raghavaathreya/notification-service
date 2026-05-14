package com.raghav.notificationservice.dto;

import com.raghav.notificationservice.model.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    @NotNull(message = "type is required")
    private NotificationType type;

    @NotBlank(message = "recipient is required")
    private String recipient;  // email / phone / device token

    @NotBlank(message = "subject is required")
    private String subject;

    @NotBlank(message = "content is required")
    private String content;

    // Optional: if true, RAG pipeline will personalize the content
    private boolean personalize = false;
}
