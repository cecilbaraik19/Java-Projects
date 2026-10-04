package com.cecil.attendance.repository;

import com.cecil.attendance.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop200ByOrderByCreatedAtDesc();

    List<Notification> findTop200ByKindOrderByCreatedAtDesc(Notification.Kind kind);

    boolean existsByKindAndRecipientIgnoreCaseAndCreatedAtAfter(Notification.Kind kind, String recipient, Instant after);

    List<Notification> findByRecipientIgnoreCaseOrderByCreatedAtDesc(String recipient);
}
