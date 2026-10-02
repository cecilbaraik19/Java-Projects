package com.cecil.cloudmonitor.controller;

import com.cecil.cloudmonitor.dto.BudgetRequest;
import com.cecil.cloudmonitor.dto.CostReport;
import com.cecil.cloudmonitor.service.CostService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/costs")
public class CostController {

    private final CostService costs;

    public CostController(CostService costs) {
        this.costs = costs;
    }

    /** Full cost report: totals, breakdowns, last 24h, per-resource costs and savings tips. */
    @GetMapping
    public CostReport report() {
        return costs.report();
    }

    /** Change the monthly budget. Admins only. */
    @PutMapping("/budget")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Double> setBudget(@Valid @RequestBody BudgetRequest req) {
        return Map.of("monthlyBudget", costs.setBudget(req.monthlyBudget()));
    }
}
