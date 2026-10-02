package com.cecil.cloudmonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_users")
@Getter @Setter @NoArgsConstructor
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    private String displayName;

    @Enumerated(EnumType.STRING)
    private Role role;

    public AppUser(String username, String password, String displayName, Role role) {
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.role = role;
    }
}
