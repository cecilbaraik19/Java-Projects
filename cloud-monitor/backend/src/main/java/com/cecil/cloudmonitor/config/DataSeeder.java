package com.cecil.cloudmonitor.config;

import com.cecil.cloudmonitor.model.*;
import com.cecil.cloudmonitor.repository.AlertRuleRepository;
import com.cecil.cloudmonitor.repository.AppUserRepository;
import com.cecil.cloudmonitor.repository.CloudResourceRepository;
import com.cecil.cloudmonitor.repository.MetricSnapshotRepository;
import com.cecil.cloudmonitor.service.LocalMetricsService;
import com.cecil.cloudmonitor.service.MonitoringService;
import com.cecil.cloudmonitor.service.SimulationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Fills an empty database with demo users, resources, alert rules and 24h of history. */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final AppUserRepository users;
    private final CloudResourceRepository resources;
    private final AlertRuleRepository rules;
    private final MetricSnapshotRepository snapshots;
    private final PasswordEncoder encoder;
    private final SimulationService simulation;
    private final LocalMetricsService local;
    private final MonitoringService monitoring;

    public DataSeeder(AppUserRepository users, CloudResourceRepository resources, AlertRuleRepository rules,
                      MetricSnapshotRepository snapshots, PasswordEncoder encoder, SimulationService simulation,
                      LocalMetricsService local, MonitoringService monitoring) {
        this.users = users;
        this.resources = resources;
        this.rules = rules;
        this.snapshots = snapshots;
        this.encoder = encoder;
        this.simulation = simulation;
        this.local = local;
        this.monitoring = monitoring;
    }

    @Override
    public void run(String... args) {
        if (users.count() == 0) {
            users.save(new AppUser("admin", encoder.encode("admin123"), "Administrator", Role.ADMIN));
            users.save(new AppUser("viewer", encoder.encode("viewer123"), "Viewer", Role.VIEWER));
            log.info("Created demo users: admin/admin123 and viewer/viewer123");
        }

        if (rules.count() == 0) {
            rules.saveAll(List.of(
                    new AlertRule("High CPU", MetricType.CPU, ">", 85, Severity.WARNING),
                    new AlertRule("CPU critical", MetricType.CPU, ">", 95, Severity.CRITICAL),
                    new AlertRule("High memory", MetricType.MEMORY, ">", 88, Severity.WARNING),
                    new AlertRule("Disk almost full", MetricType.DISK, ">", 90, Severity.CRITICAL)));
        }

        if (resources.count() == 0) {
            List<CloudResource> list = new ArrayList<>(List.of(
                    res("web-server-01", ResourceType.VM, Provider.AWS, "ap-south-1", "t3.medium", "10.0.1.12", "Ubuntu 24.04", 0.0448, 48),
                    res("web-server-02", ResourceType.VM, Provider.AWS, "ap-south-1", "t3.medium", "10.0.1.13", "Ubuntu 24.04", 0.0448, 52),
                    res("api-gateway-prod", ResourceType.CONTAINER, Provider.AWS, "us-east-1", "ECS Fargate 1vCPU", "10.0.2.5", "Amazon Linux 2023", 0.0494, 30),
                    res("postgres-main", ResourceType.DATABASE, Provider.AWS, "ap-south-1", "db.t3.large", "10.0.3.20", "PostgreSQL 16", 0.152, 67),
                    res("media-storage", ResourceType.STORAGE, Provider.AWS, "ap-south-1", "S3 Standard", "s3.ap-south-1", "Object storage", 0.031, 86),
                    res("analytics-vm", ResourceType.VM, Provider.GCP, "asia-south1", "e2-standard-4", "10.128.0.7", "Debian 12", 0.155, 41),
                    res("image-resizer", ResourceType.FUNCTION, Provider.GCP, "asia-south1", "Cloud Run fn 512MB", "-", "Node.js 22", 0.006, 12),
                    res("redis-cache", ResourceType.DATABASE, Provider.AZURE, "centralindia", "Standard C1", "10.1.0.9", "Redis 7", 0.10, 22),
                    res("ml-worker-gpu", ResourceType.VM, Provider.AZURE, "eastus", "NC6s v3", "10.1.2.4", "Ubuntu 22.04", 3.06, 58),
                    res("backup-server", ResourceType.VM, Provider.GCP, "us-central1", "e2-small", "10.128.0.30", "Debian 12", 0.017, 73)));

            CloudResource me = new CloudResource(local.hostName() + " (this PC)", ResourceType.LOCAL_HOST, Provider.LOCAL,
                    "local", local.cpuName(), local.ipAddress(), local.osName(), 0);
            list.add(me);

            list = resources.saveAll(list);

            // Fill the last 24 hours with one point per minute so charts aren't empty on first start
            Random rnd = new Random(42);
            Instant now = Instant.now();
            List<MetricSnapshot> history = new ArrayList<>();
            for (CloudResource r : list) {
                if (r.getType() == ResourceType.LOCAL_HOST) continue; // local history is real only
                double disk = r.getDisk() - 3;
                for (Instant t = now.minus(Duration.ofHours(24)); t.isBefore(now); t = t.plusSeconds(60)) {
                    disk += 3.0 / 1440;
                    history.add(simulation.historicalPoint(r, t, disk, rnd));
                }
                r.setCpu(history.get(history.size() - 1).getCpu());
                r.setMemory(history.get(history.size() - 1).getMemory());
            }
            snapshots.saveAll(history);

            // One stopped server, to show the Start action
            CloudResource backup = list.get(9);
            backup.setStatus(ResourceStatus.STOPPED);
            resources.saveAll(list);
            log.info("Seeded {} resources and {} history points", list.size(), history.size());
        }
        monitoring.markReady();
    }

    private static CloudResource res(String name, ResourceType type, Provider provider, String region,
                                     String instanceType, String ip, String os, double hourlyCost, double disk) {
        CloudResource r = new CloudResource(name, type, provider, region, instanceType, ip, os, hourlyCost);
        r.setDisk(disk);
        return r;
    }
}
