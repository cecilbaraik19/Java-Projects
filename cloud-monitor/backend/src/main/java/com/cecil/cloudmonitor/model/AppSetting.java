package com.cecil.cloudmonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A simple key/value setting that admins can change at runtime (e.g. the monthly budget). */
@Entity
@Table(name = "app_settings")
@Getter @Setter @NoArgsConstructor
public class AppSetting {
    /** "key" and "value" are reserved words in MySQL, so the columns get other names. */
    @Id
    @Column(name = "setting_key", length = 64)
    private String key;

    @Column(name = "setting_value", length = 255)
    private String value;

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
