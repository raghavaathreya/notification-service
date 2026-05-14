package com.raghav.notificationservice.sender;

import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SmsSender {

    @Value("${twilio.account-sid}")
    private String accountSid;

    @Value("${twilio.auth-token}")
    private String authToken;

    @Value("${twilio.from-number}")
    private String fromNumber;

    /**
     * Initialize the Twilio SDK once on startup.
     * Twilio uses a static init pattern — credentials are set globally.
     */
    @PostConstruct
    public void init() {
        Twilio.init(accountSid, authToken);
        log.info("[SMS SENDER] Twilio SDK initialized with accountSid={}", maskSid(accountSid));
    }

    /**
     * Sends an SMS via Twilio's REST API.
     *
     * SMS constraints:
     * - Max 160 chars per segment (multi-segment messages cost more)
     * - Content is truncated to 160 chars before sending
     * - 'to' must be E.164 format: +[country code][number] e.g. +919876543210
     *
     * @param to      recipient phone number in E.164 format
     * @param content message body (will be truncated to 160 chars)
     * @throws SmsSendException if Twilio returns an error
     */
    public void send(String to, String content) {
        log.info("[SMS SENDER] Sending SMS to={}", maskPhone(to));

        // Enforce 160-char limit — truncate rather than fail
        String truncatedContent = truncate(content, 160);

        try {
            Message message = Message.creator(
                            new PhoneNumber(to),
                            new PhoneNumber(fromNumber),
                            truncatedContent)
                    .create();

            log.info("[SMS SENDER] SMS delivered successfully to={}, sid={}, status={}",
                    maskPhone(to), message.getSid(), message.getStatus());

        } catch (ApiException e) {
            log.error("[SMS SENDER] Twilio API error for to={}: code={}, message={}",
                    maskPhone(to), e.getCode(), e.getMessage());
            throw new SmsSendException("Twilio API error [" + e.getCode() + "]: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("[SMS SENDER] Unexpected error sending SMS to={}: {}", maskPhone(to), e.getMessage());
            throw new SmsSendException("Unexpected SMS send error: " + e.getMessage(), e);
        }
    }

    /**
     * Truncates content to maxLength, appending "..." if truncated.
     */
    private String truncate(String content, int maxLength) {
        if (content == null) return "";
        if (content.length() <= maxLength) return content;
        String truncated = content.substring(0, maxLength - 3) + "...";
        log.warn("[SMS SENDER] Content truncated from {} to {} chars", content.length(), maxLength);
        return truncated;
    }

    /**
     * Masks phone number for safe logging: +919876543210 → +91*****3210
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "****";
        return phone.substring(0, 3) + "*****" + phone.substring(phone.length() - 4);
    }

    /**
     * Masks Twilio account SID for safe logging.
     */
    private String maskSid(String sid) {
        if (sid == null || sid.length() < 8) return "****";
        return sid.substring(0, 4) + "..." + sid.substring(sid.length() - 4);
    }

    public static class SmsSendException extends RuntimeException {
        public SmsSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
