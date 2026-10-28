package com.cecil.edge.service;

import com.cecil.edge.edge.EdgeEngine;
import com.cecil.edge.sim.Simulator;
import com.cecil.edge.store.DataStore;
import com.cecil.edge.store.SeedData;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.function.LongSupplier;

/**
 * Puts everything together: the data store, the edge engine, the simulator and the services.
 * There is ONE lock (this object). The web server and the simulator timer both take it,
 * so two threads never change the data at the same time.
 */
public final class Hub {
    public final DataStore store;
    public final EdgeEngine engine;
    public final Simulator sim;
    public final AuthService auth;
    public final NodeService nodes;
    public final AlertService alerts;
    public final AnalyticsService analytics;
    public final SettingsService settings;
    public LongSupplier clock = System::currentTimeMillis;
    private long lastSave;

    public Hub(DataStore store, ZoneId zone, long seed) {
        this.store = store;
        this.engine = new EdgeEngine(store);
        this.engine.zone = zone;
        this.sim = new Simulator(store, engine, seed);
        this.auth = new AuthService(this);
        this.nodes = new NodeService(this);
        this.alerts = new AlertService(this);
        this.analytics = new AnalyticsService(this);
        this.settings = new SettingsService(this);
    }

    /** Loads the data folder (or creates the demo data on the first run) and warms up the live charts. */
    public static Hub open(Path dataDir, ZoneId zone, int warmUpMinutes) throws IOException {
        DataStore db = new DataStore(dataDir);
        db.load();
        long now = System.currentTimeMillis();
        boolean first = db.isEmpty();
        if (first) {
            SeedData.fill(db, now, zone);
            db.saveAll();
        }
        Hub hub = new Hub(db, zone, now);
        hub.engine.rebuildIndex();
        if (warmUpMinutes > 0) {
            synchronized (hub) {
                hub.sim.warmUp(now, warmUpMinutes);
            }
        }
        return hub;
    }

    public long now() {
        return clock.getAsLong();
    }

    /** Runs every few seconds: simulator step, health checks, and saving changed files now and then. */
    public synchronized void tick() {
        long now = now();
        if (store.settings.simulatorOn) sim.tick(now);
        else engine.checkHealth(now, store.settings.tickSec);
        if (now - lastSave >= 15_000) saveQuietly();
    }

    public synchronized void saveQuietly() {
        try {
            store.saveDirty();
            lastSave = now();
        } catch (IOException e) {
            System.err.println("Could not save data: " + e.getMessage());
        }
    }

    /** On shutdown: keep the hour that is still in memory, then save everything that changed. */
    public synchronized void shutdown() {
        for (var s : store.sensors.values()) engine.saveHour(s, engine.rt(s));
        saveQuietly();
    }
}
