package com.cecil.attendance.config;

import com.cecil.attendance.model.Shift;
import com.cecil.attendance.repository.ShiftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;

/** Creates three common shifts the first time; employees stay on default office hours until assigned. */
@Component
@Order(4)
public class ShiftSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ShiftSeeder.class);

    private final ShiftRepository shifts;

    public ShiftSeeder(ShiftRepository shifts) {
        this.shifts = shifts;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (shifts.count() > 0) return;
        shifts.save(new Shift("Morning", LocalTime.of(6, 0), LocalTime.of(14, 0), 10, 7.5));
        shifts.save(new Shift("General", LocalTime.of(9, 30), LocalTime.of(18, 0), 15, 8));
        shifts.save(new Shift("Night", LocalTime.of(22, 0), LocalTime.of(6, 0), 15, 7.5));
        log.info("Created default shifts: Morning, General, Night");
    }
}
