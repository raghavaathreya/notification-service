package com.raghav.notificationservice.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;

@Configuration
@Slf4j
public class FirebaseConfig {

    @Value("${firebase.credentials-path}")
    private String credentialsPath;

    @Value("${firebase.project-id}")
    private String projectId;

    /**
     * Initializes Firebase Admin SDK once on application startup.
     *
     * Credential resolution order:
     * 1. Classpath (src/main/resources/firebase-service-account.json) — for local dev
     * 2. Filesystem absolute path — for Docker/Kubernetes secrets mounted as files
     * 3. Application Default Credentials — for GCP-hosted environments (GKE, Cloud Run)
     *
     * In production, use option 2 or 3 — never commit the service account JSON to git.
     */
    @PostConstruct
    public void initialize() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                GoogleCredentials credentials = resolveCredentials();

                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(credentials)
                        .setProjectId(projectId)
                        .build();

                FirebaseApp.initializeApp(options);
                log.info("[FIREBASE] Firebase Admin SDK initialized for project={}", projectId);
            } else {
                log.info("[FIREBASE] Firebase already initialized, skipping");
            }
        } catch (IOException e) {
            log.error("[FIREBASE] Failed to initialize Firebase: {}", e.getMessage());
            // Don't throw — let the app start even if Firebase is misconfigured.
            // Push notifications will fail gracefully via PushSender's error handling.
        }
    }

    private GoogleCredentials resolveCredentials() throws IOException {
        // Try classpath first (local dev)
        Resource classpathResource = new ClassPathResource(credentialsPath);
        if (classpathResource.exists()) {
            log.info("[FIREBASE] Loading credentials from classpath: {}", credentialsPath);
            try (InputStream is = classpathResource.getInputStream()) {
                return GoogleCredentials.fromStream(is);
            }
        }

        // Try filesystem path (Docker secrets / env-mounted files)
        Resource fsResource = new FileSystemResource(credentialsPath);
        if (fsResource.exists()) {
            log.info("[FIREBASE] Loading credentials from filesystem: {}", credentialsPath);
            try (InputStream is = fsResource.getInputStream()) {
                return GoogleCredentials.fromStream(is);
            }
        }

        // Fall back to Application Default Credentials (GCP managed environments)
        log.info("[FIREBASE] Credentials file not found, falling back to Application Default Credentials");
        return GoogleCredentials.getApplicationDefault();
    }
}
