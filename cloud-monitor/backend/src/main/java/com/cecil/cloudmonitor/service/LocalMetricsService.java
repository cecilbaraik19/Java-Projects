package com.cecil.cloudmonitor.service;

import com.cecil.cloudmonitor.model.CloudResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

/** Reads REAL metrics from the computer running the backend, using the OSHI library. */
@Service
public class LocalMetricsService {

    private static final Logger log = LoggerFactory.getLogger(LocalMetricsService.class);

    private SystemInfo systemInfo;
    private HardwareAbstractionLayer hardware;
    private OperatingSystem os;
    private List<NetworkIF> networkIFs;
    private long[] prevCpuTicks;
    private long prevBytesIn = -1;
    private long prevBytesOut = -1;
    private long prevNetTime;
    private boolean available;

    public LocalMetricsService() {
        try {
            systemInfo = new SystemInfo();
            hardware = systemInfo.getHardware();
            os = systemInfo.getOperatingSystem();
            networkIFs = hardware.getNetworkIFs();
            prevCpuTicks = hardware.getProcessor().getSystemCpuLoadTicks();
            available = true;
        } catch (Throwable t) {
            log.warn("OSHI could not read local hardware, local metrics disabled: {}", t.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public String hostName() {
        try {
            return os.getNetworkParams().getHostName();
        } catch (Throwable t) {
            return "localhost";
        }
    }

    public String osName() {
        return available ? os.toString() : System.getProperty("os.name");
    }

    public String ipAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public String cpuName() {
        try {
            CentralProcessor p = hardware.getProcessor();
            return p.getLogicalProcessorCount() + " vCPU / "
                    + Math.round(hardware.getMemory().getTotal() / 1024.0 / 1024 / 1024) + " GB";
        } catch (Throwable t) {
            return "This PC";
        }
    }

    /**
     * Fills the resource with this machine's current CPU, RAM, disk and network usage.
     * Disk = percentage of drive SPACE used (not disk activity like Task Manager shows).
     * Network = KB/s (kilobytes), summed over all network adapters.
     */
    public void update(CloudResource r) {
        if (!available) return;
        try {
            CentralProcessor cpu = hardware.getProcessor();
            r.setCpu(round(cpu.getSystemCpuLoadBetweenTicks(prevCpuTicks) * 100));
            prevCpuTicks = cpu.getSystemCpuLoadTicks();

            // Real Windows/Linux uptime (time since this PC booted), not the backend's uptime
            r.setStartedAt(Instant.now().minusSeconds(os.getSystemUptime()));

            GlobalMemory mem = hardware.getMemory();
            r.setMemory(round((mem.getTotal() - mem.getAvailable()) * 100.0 / mem.getTotal()));

            long total = 0, usable = 0;
            for (OSFileStore fs : os.getFileSystem().getFileStores(true)) {
                total += fs.getTotalSpace();
                usable += fs.getUsableSpace();
            }
            if (total > 0) r.setDisk(round((total - usable) * 100.0 / total));

            long in = 0, out = 0;
            for (NetworkIF nif : networkIFs) {
                nif.updateAttributes();
                in += nif.getBytesRecv();
                out += nif.getBytesSent();
            }
            long now = System.currentTimeMillis();
            if (prevBytesIn >= 0) {
                double seconds = Math.max(0.5, (now - prevNetTime) / 1000.0);
                r.setNetworkIn(round(Math.max(0, in - prevBytesIn) / 1024.0 / seconds));
                r.setNetworkOut(round(Math.max(0, out - prevBytesOut) / 1024.0 / seconds));
            }
            prevBytesIn = in;
            prevBytesOut = out;
            prevNetTime = now;
        } catch (Throwable t) {
            log.debug("Local metric read failed: {}", t.getMessage());
        }
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
