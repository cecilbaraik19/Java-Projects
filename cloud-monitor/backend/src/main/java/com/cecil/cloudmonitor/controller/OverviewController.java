package com.cecil.cloudmonitor.controller;

import com.cecil.cloudmonitor.dto.MetricPoint;
import com.cecil.cloudmonitor.dto.OverviewDto;
import com.cecil.cloudmonitor.service.MonitoringService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/overview")
public class OverviewController {

    private final MonitoringService monitoring;

    public OverviewController(MonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    @GetMapping
    public OverviewDto overview() {
        return monitoring.overview();
    }

    /** Average CPU / memory / disk and total network of all resources. range = 15m, 1h, 6h or 24h */
    @GetMapping("/history")
    public List<MetricPoint> history(@RequestParam(defaultValue = "1h") String range) {
        return monitoring.overviewHistory(range);
    }
}
