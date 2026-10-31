package com.cecil.shop;

import com.cecil.shop.auth.AuthService;
import com.cecil.shop.cart.CartService;
import com.cecil.shop.catalog.CatalogService;
import com.cecil.shop.common.Config;
import com.cecil.shop.common.Passwords;
import com.cecil.shop.common.ServiceBase;
import com.cecil.shop.eventbus.EventBusService;
import com.cecil.shop.gateway.GatewayService;
import com.cecil.shop.inventory.InventoryService;
import com.cecil.shop.notification.NotificationService;
import com.cecil.shop.order.OrderService;
import com.cecil.shop.payment.PaymentService;
import com.cecil.shop.registry.RegistryService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the microservices.
 *
 *   java -jar microservices-ecommerce.jar                 start ALL 10 services in one go (easiest)
 *   java -jar microservices-ecommerce.jar order-service   start only ONE service (run-separately.bat
 *                                                         uses this to open one window per service)
 *
 * Even when all start from one command, every service is its own web server on its own port,
 * with its own data folder, and they only talk to each other over HTTP - exactly like separate machines.
 */
public final class App {
    private App() {}

    public static void main(String[] args) throws Exception {
        Path root = projectRoot();
        Path data = System.getenv("SHOP_DATA") != null ? Path.of(System.getenv("SHOP_DATA")) : root.resolve("data");
        Files.createDirectories(data);
        Config cfg = new Config(data, internalKey(data));
        String port = System.getenv("PORT");
        if (port != null && port.matches("\\d{2,5}")) cfg.ports.put(Config.GATEWAY, Integer.parseInt(port));

        List<String> wanted = new ArrayList<>();
        if (args.length == 0 || args[0].equals("all")) wanted.addAll(Config.DEFAULT_PORTS.keySet());
        else for (String a : args) {
            if (!Config.DEFAULT_PORTS.containsKey(a)) {
                System.out.println("Unknown service '" + a + "'. Choose from: " + String.join(", ", Config.DEFAULT_PORTS.keySet()));
                System.exit(1);
            }
            wanted.add(a);
        }

        System.out.println();
        System.out.println("  Bandar Bazaar - Microservices E-Commerce System");
        System.out.println("  -----------------------------------------------------------");
        List<ServiceBase> started = new ArrayList<>();
        for (String name : Config.DEFAULT_PORTS.keySet()) {
            if (!wanted.contains(name)) continue;
            ServiceBase s = create(name, cfg, root.resolve("frontend"));
            s.start();
            started.add(s);
            System.out.printf("  %-22s port %-5d %s%n", name, s.port(), s.role());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (int i = started.size() - 1; i >= 0; i--) started.get(i).stop();
        }));
        System.out.println();
        if (wanted.contains(Config.GATEWAY)) {
            System.out.println("  Open http://localhost:" + cfg.port(Config.GATEWAY) + " in your browser.");
            System.out.println("  Demo logins: admin / admin123   riya / shop123   arjun / shop123 (small wallet)");
        }
        System.out.println("  Data folder: " + data.toAbsolutePath());
        System.out.println("  Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }

    public static ServiceBase create(String name, Config cfg, Path frontend) {
        return switch (name) {
            case Config.REGISTRY -> new RegistryService(cfg);
            case Config.EVENT_BUS -> new EventBusService(cfg);
            case Config.AUTH -> new AuthService(cfg);
            case Config.CATALOG -> new CatalogService(cfg);
            case Config.INVENTORY -> new InventoryService(cfg);
            case Config.CART -> new CartService(cfg);
            case Config.ORDER -> new OrderService(cfg);
            case Config.PAYMENT -> new PaymentService(cfg);
            case Config.NOTIFICATION -> new NotificationService(cfg);
            case Config.GATEWAY -> new GatewayService(cfg, frontend);
            default -> throw new IllegalArgumentException(name);
        };
    }

    /** Works when started from the project folder or from the backend folder. */
    static Path projectRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isDirectory(cwd.resolve("frontend"))) return cwd;
        if (cwd.getParent() != null && Files.isDirectory(cwd.getParent().resolve("frontend"))) return cwd.getParent();
        return cwd;
    }

    /** The shared secret. Saved in data/internal.key so services started in separate windows use the same one. */
    static synchronized String internalKey(Path data) throws IOException {
        Path f = data.resolve("internal.key");
        if (Files.exists(f)) {
            String k = Files.readString(f, StandardCharsets.UTF_8).trim();
            if (k.length() >= 32) return k;
        }
        String k = Passwords.randomKey(32);
        Files.writeString(f, k, StandardCharsets.UTF_8);
        return k;
    }
}
