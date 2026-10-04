package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.CreateUserRequest;
import com.cecil.attendance.dto.Dtos.ResetPasswordRequest;
import com.cecil.attendance.dto.Dtos.UpdateUserRequest;
import com.cecil.attendance.dto.Dtos.UserView;
import com.cecil.attendance.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserView> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUserRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest req) {
        service.resetPassword(id, req.newPassword());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
