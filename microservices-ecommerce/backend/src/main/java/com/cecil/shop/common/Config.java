package com.cecil.shop.common;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings shared by all services: where the registry is, the internal key, and the data folder.
 *
 * Service names and their default ports (each service is its own small web server):
 */
public class Config {
    public static final String REGISTRY = "registry";
    public static final String GATEWAY = "api-gateway";
    public static final String EVENT_BUS = "event-bus";
    public static final String AUTH = "auth-service";
    public static final String CATALOG = "catalog-service";
    public static final String INVENTORY = "inventory-service";
    public static final String CART = "cart-service";
    public static final String ORDER = "order-service";
    public static final String PAYMENT = "payment-service";
    public static final String NOTIFICATION = "notification-service";

    public static final Map<String, Integer> DEFAULT_PORTS = new LinkedHashMap<>();
    static {
        DEFAULT_PORTS.put(REGISTRY, 8761);
        DEFAULT_PORTS.put(EVENT_BUS, 8090);
        DEFAULT_PORTS.put(AUTH, 8081);
        DEFAULT_PORTS.put(CATALOG, 8082);
        DEFAULT_PORTS.put(INVENTORY, 8083);
        DEFAULT_PORTS.put(CART, 8084);
        DEFAULT_PORTS.put(ORDER, 8085);
        DEFAULT_PORTS.put(PAYMENT, 8086);
        DEFAULT_PORTS.put(NOTIFICATION, 8087);
        DEFAULT_PORTS.put(GATEWAY, 8080);
    }

    public final String host = "127.0.0.1";
    public String registryUrl;
    /** Secret shared by the services. Calls without it are refused, so the browser cannot skip the gateway. */
    public final String internalKey;
    /** Root data folder. Each service uses its own sub-folder. null = keep everything in memory (tests). */
    public final Path dataRoot;
    public final Map<String, Integer> ports = new LinkedHashMap<>(DEFAULT_PORTS);
    /** Fast settings for tests (shorter timeouts). */
    public long heartbeatMs = 3000, instanceTtlMs = 9000, discoveryCacheMs = 1500;
    public int callTimeoutMs = 4000;
    public int gatewayRatePerMinute = 600, loginRatePerMinute = 15;

    public Config(Path dataRoot, String internalKey) {
        this.dataRoot = dataRoot;
        this.internalKey = internalKey;
        this.registryUrl = "http://" + host + ":" + DEFAULT_PORTS.get(REGISTRY);
    }

    public int port(String service) { return ports.getOrDefault(service, 0); }

    public Path dataDir(String service) { return dataRoot == null ? null : dataRoot.resolve(service); }
}
