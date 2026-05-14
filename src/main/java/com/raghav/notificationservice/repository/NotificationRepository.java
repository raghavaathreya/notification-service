package com.raghav.notificationservice.repository;

import com.raghav.notificationservice.model.Notification;
import com.raghav.notificationservice.model.NotificationStatus;
import com.raghav.notificationservice.model.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByUserId(String userId);
    List<Notification> findByUserIdAndType(String userId, NotificationType type);
    List<Notification> findByStatus(NotificationStatus status);
}
