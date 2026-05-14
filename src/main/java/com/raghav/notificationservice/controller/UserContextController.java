package com.raghav.notificationservice.controller;

import com.raghav.notificationservice.dto.UserContextRequest;
import com.raghav.notificationservice.dto.UserContextResponse;
import com.raghav.notificationservice.rag.UserContextService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/user-context")
@RequiredArgsConstructor
@Slf4j
public class UserContextController {

    private final UserContextService userContextService;

    /**
     * POST /api/v1/user-context
     * Index a single user context event into Pinecone.
     *
     * Example request:
     * {
     *   "userId": "user-123",
     *   "text": "User purchased Sony WH-1000XM5 headphones",
     *   "metadata": {
     *     "category": "electronics",
     *     "product": "Sony WH-1000XM5",
     *     "action": "purchase",
     *     "price_range": "premium"
     *   }
     * }
     */
    @PostMapping
    public ResponseEntity<UserContextResponse> upsert(@Valid @RequestBody UserContextRequest request) {
        log.info("API: upsert user context for userId={}", request.getUserId());
        UserContextResponse response = userContextService.upsert(request);
        HttpStatus status = "upserted".equals(response.getStatus()) ? HttpStatus.CREATED : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * POST /api/v1/user-context/batch
     * Bulk upsert — useful for backfilling historical user data.
     */
    @PostMapping("/batch")
    public ResponseEntity<List<UserContextResponse>> upsertBatch(@Valid @RequestBody List<UserContextRequest> requests) {
        log.info("API: batch upsert {} user context items", requests.size());
        List<UserContextResponse> responses = userContextService.upsertBatch(requests);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses);
    }

    /**
     * DELETE /api/v1/user-context/{userId}
     * Delete all context vectors for a user (GDPR / account deletion).
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteUserContext(@PathVariable String userId) {
        log.info("API: delete all context for userId={}", userId);
        userContextService.deleteUserContext(userId);
        return ResponseEntity.noContent().build();
    }
}
