package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.ApprovalCounts;
import com.cecil.attendance.dto.Dtos.CorrectionView;
import com.cecil.attendance.dto.Dtos.LeaveView;
import com.cecil.attendance.dto.Dtos.ReviewRequest;
import com.cecil.attendance.model.RequestStatus;
import com.cecil.attendance.service.CorrectionService;
import com.cecil.attendance.service.LeaveService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Approval queue: admins see everyone, managers their department; nobody sees or approves their own requests. */
@RestController
@RequestMapping("/api/approvals")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class ApprovalController {

    private final LeaveService leave;
    private final CorrectionService corrections;

    public ApprovalController(LeaveService leave, CorrectionService corrections) {
        this.leave = leave;
        this.corrections = corrections;
    }

    @GetMapping("/count")
    public ApprovalCounts count() {
        return new ApprovalCounts(leave.pendingCount(), corrections.pendingCount());
    }

    /** status = PENDING (default) or ALL */
    @GetMapping("/leave")
    public List<LeaveView> leave(@RequestParam(defaultValue = "PENDING") String status) {
        return leave.forReview(parse(status));
    }

    @PostMapping("/leave/{id}")
    public LeaveView reviewLeave(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
        return leave.review(id, req.approve(), req.comment());
    }

    @GetMapping("/corrections")
    public List<CorrectionView> corrections(@RequestParam(defaultValue = "PENDING") String status) {
        return corrections.forReview(parse(status));
    }

    @PostMapping("/corrections/{id}")
    public CorrectionView reviewCorrection(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
        return corrections.review(id, req.approve(), req.comment());
    }

    private static RequestStatus parse(String status) {
        return "ALL".equalsIgnoreCase(status) ? null : RequestStatus.valueOf(status.toUpperCase());
    }
}
