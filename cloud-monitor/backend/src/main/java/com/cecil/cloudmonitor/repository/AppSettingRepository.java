package com.cecil.cloudmonitor.repository;

import com.cecil.cloudmonitor.model.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
