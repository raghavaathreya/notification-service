package com.raghav.notificationservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserContextRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    /**
     * A short human-readable description of the event/preference.
     * This is what gets embedded into a vector.
     *
     * Examples:
     *  - "User purchased Sony WH-1000XM5 headphones"
     *  - "User browsed electronics category for 15 minutes"
     *  - "User prefers morning notifications around 9 AM"
     *  - "User clicked promotional email about summer sale"
     */
    @NotBlank(message = "text is required")
    private String text;

    /**
     * Arbitrary metadata stored alongside the vector in Pinecone.
     * This is what gets returned on query and passed to OpenAI as context.
     *
     * Examples:
     *  { "category": "electronics", "product": "Sony WH-1000XM5", "action": "purchase" }
     *  { "preference": "morning_notifications", "timezone": "Asia/Kolkata" }
     */
    @NotNull(message = "metadata is required")
    private Map<String, String> metadata;

    /**
     * Optional: a stable ID for this context entry so re-upserts overwrite
     * instead of creating duplicates. E.g. "user-123:order-456"
     * If null, a UUID will be generated.
     */
    private String vectorId;
}
