package com.cecil.attendance.repository;

import com.cecil.attendance.model.CheckInSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckInSettingsRepository extends JpaRepository<CheckInSettings, Long> {
}
