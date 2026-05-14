package com.raghav.notificationservice.sender;

import com.google.firebase.messaging.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class PushSender {

    /**
     * Sends a push notification to a device via Firebase Cloud Messaging (FCM).
     *
     * FCM delivers to Android, iOS, and Web via a single API.
     * The device token is obtained by the client app on first launch
     * and stored server-side (typically in the user's profile).
     *
     * @param deviceToken FCM registration token for the target device
     * @param title       notification title (shown in the system tray)
     * @param body        notification body text
     * @throws PushSendException if FCM returns an error
     */
    public void send(String deviceToken, String title, String body) {
        log.info("[PUSH SENDER] Sending push to deviceToken={}", maskToken(deviceToken));

        try {
            // Build the FCM message
            Message message = Message.builder()
                    .setToken(deviceToken)
                    .setNotification(
                            Notification.builder()
                                    .setTitle(title)
                                    .setBody(body)
                                    .build()
                    )
                    // Android-specific config: high priority ensures delivery
                    // even when device is in Doze mode
                    .setAndroidConfig(
                            AndroidConfig.builder()
                                    .setPriority(AndroidConfig.Priority.HIGH)
                                    .setNotification(
                                            AndroidNotification.builder()
                                                    .setTitle(title)
                                                    .setBody(body)
                                                    .setSound("default")
                                                    .build()
                                    )
                                    .build()
                    )
                    // APNs (Apple) config: badge + sound
                    .setApnsConfig(
                            ApnsConfig.builder()
                                    .setAps(
                                            Aps.builder()
                                                    .setAlert(ApsAlert.builder()
                                                            .setTitle(title)
                                                            .setBody(body)
                                                            .build())
                                                    .setBadge(1)
                                                    .setSound("default")
                                                    .build()
                                    )
                                    .build()
                    )
                    .build();

            // Send and get the FCM message ID
            String messageId = FirebaseMessaging.getInstance().send(message);

            log.info("[PUSH SENDER] Push delivered successfully, fcmMessageId={}", messageId);

        } catch (FirebaseMessagingException e) {
            log.error("[PUSH SENDER] FCM error for token={}: code={}, message={}",
                    maskToken(deviceToken), e.getMessagingErrorCode(), e.getMessage());

            // Distinguish between retryable and non-retryable FCM errors
            if (isTokenInvalid(e)) {
                // Token is stale/unregistered — no point retrying, should be cleaned up
                log.warn("[PUSH SENDER] Device token is invalid/unregistered: {}", maskToken(deviceToken));
                throw new InvalidDeviceTokenException(
                        "Device token is invalid or unregistered: " + maskToken(deviceToken), e);
            }

            throw new PushSendException("FCM send failed [" + e.getMessagingErrorCode() + "]: " + e.getMessage(), e);
        }
    }

    /**
     * FCM error codes that indicate the device token is permanently invalid.
     * These should NOT be retried — the token should be removed from the DB.
     */
    private boolean isTokenInvalid(FirebaseMessagingException e) {
        MessagingErrorCode code = e.getMessagingErrorCode();
        return code == MessagingErrorCode.UNREGISTERED
                || code == MessagingErrorCode.INVALID_ARGUMENT;
    }

    /**
     * Masks device token for safe logging.
     * FCM tokens are ~163 chars — show first 8 and last 4.
     */
    private String maskToken(String token) {
        if (token == null || token.length() < 12) return "****";
        return token.substring(0, 8) + "..." + token.substring(token.length() - 4);
    }

    public static class PushSendException extends RuntimeException {
        public PushSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Thrown when FCM reports an invalid/unregistered device token.
     * Consumers can catch this specifically to trigger token cleanup.
     */
    public static class InvalidDeviceTokenException extends RuntimeException {
        public InvalidDeviceTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
