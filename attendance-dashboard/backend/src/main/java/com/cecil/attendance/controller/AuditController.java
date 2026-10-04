package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.PageView;
import com.cecil.attendance.model.AuditLog;
import com.cecil.attendance.service.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    public PageView<AuditLog> search(@RequestParam(defaultValue = "") String username,
                                     @RequestParam(defaultValue = "") String action,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        Page<AuditLog> p = audit.search(username, action, page, size);
        return new PageView<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
