package com.cecil.credchain.auth;

/** The signed-in user, as read from a verified token. */
public record AuthUser(String id, String email, String name, Role role, String institutionId) {}
