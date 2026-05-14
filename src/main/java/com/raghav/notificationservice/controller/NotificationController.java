package com.raghav.notificationservice.controller;

import com.raghav.notificationservice.dto.NotificationRequest;
import com.raghav.notificationservice.dto.NotificationResponse;
import com.raghav.notificationservice.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * POST /api/v1/notifications
     * Accepts a notification request and queues it for delivery.
     */
    @PostMapping
    public ResponseEntity<NotificationResponse> send(@Valid @RequestBody NotificationRequest request) {
        log.info("API: send notification request for userId={}, type={}", request.getUserId(), request.getType());
        NotificationResponse response = notificationService.send(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * GET /api/v1/notifications/user/{userId}
     * Fetch all notifications for a given user.
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<NotificationResponse>> getByUser(@PathVariable String userId) {
        return ResponseEntity.ok(notificationService.getByUserId(userId));
    }

    /**
     * GET /api/v1/notifications/{id}
     * Fetch a specific notification by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<NotificationResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(notificationService.getById(id));
    }
}
