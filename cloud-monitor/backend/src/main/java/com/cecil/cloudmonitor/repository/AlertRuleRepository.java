package com.cecil.cloudmonitor.repository;

import com.cecil.cloudmonitor.model.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {
    List<AlertRule> findByEnabledTrue();
}
