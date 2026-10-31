package com.cecil.shop.registry;

import com.cecil.shop.common.ApiError;
import com.cecil.shop.common.Config;
import com.cecil.shop.common.Json;
import com.cecil.shop.common.Req;
import com.cecil.shop.common.Router;
import com.cecil.shop.common.ServiceBase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SERVICE REGISTRY (like Netflix Eureka or Consul) - the "phone book" of the system.
 *
 *  - Every service REGISTERS itself when it starts: "I am catalog-service at http://127.0.0.1:8082".
 *  - It then sends a HEARTBEAT every 3 seconds: "I am still alive".
 *  - If no heartbeat arrives for 9 seconds (3 missed beats) the instance is marked DOWN
 *    and nobody is sent to it any more. After 2 minutes DOWN it is removed.
 *  - Other services ASK the registry where a service is (service discovery).
 */
public class RegistryService extends ServiceBase {
    private final Map<String, Map<String, Object>> instances = new LinkedHashMap<>(); // key = name + url

    public RegistryService(Config cfg) { super(Config.REGISTRY, cfg); }

    @Override public String role() { return "Phone book: who is running and where"; }

    @Override protected boolean usesRegistry() { return false; }

    @Override
    protected void routes(Router r) {
        r.post("/register", this::register);
        r.post("/heartbeat", this::heartbeat);
        r.post("/deregister", this::deregister);
        r.get("/services", req -> Json.obj("instances", list(null), "ttlMs", cfg.instanceTtlMs));
        r.get("/services/{name}", req -> Json.obj("name", req.param("name"), "instances", list(req.param("name"))));
    }

    private synchronized Object register(Req r) {
        Map<String, Object> b = r.json();
        String name = requireText(b, "name", "Service name", 60);
        String url = requireText(b, "url", "Service URL", 200);
        if (!url.startsWith("http://127.0.0.1:") && !url.startsWith("http://localhost:")) throw ApiError.bad("Only local services can register");
        long now = System.currentTimeMillis();
        Map<String, Object> inst = Json.obj("name", name, "url", url, "role", Json.str(b, "role", ""),
                "status", "UP", "registeredAt", now, "lastBeat", now, "beats", 0L, "startedAt", Json.lng(b, "startedAt", now));
        instances.put(name + "@" + url, inst);
        return Json.obj("ok", true, "registered", name);
    }

    private synchronized Object heartbeat(Req r) {
        Map<String, Object> b = r.json();
        Map<String, Object> inst = instances.get(Json.str(b, "name") + "@" + Json.str(b, "url"));
        if (inst == null) throw ApiError.notFound("Not registered - please register first");
        inst.put("lastBeat", System.currentTimeMillis());
        inst.put("beats", Json.lng(inst, "beats") + 1);
        inst.put("status", "UP");
        return Json.obj("ok", true);
    }

    private synchronized Object deregister(Req r) {
        Map<String, Object> b = r.json();
        instances.remove(Json.str(b, "name") + "@" + Json.str(b, "url"));
        return Json.obj("ok", true);
    }

    /** Current list, after marking silent instances DOWN. name == null means all services. */
    private synchronized List<Object> list(String name) {
        long now = System.currentTimeMillis();
        instances.values().removeIf(i -> now - Json.lng(i, "lastBeat") > 120_000);
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> i : instances.values()) {
            long silent = now - Json.lng(i, "lastBeat");
            i.put("status", silent > cfg.instanceTtlMs ? "DOWN" : "UP");
            i.put("silentMs", silent);
            if (name == null || name.equals(Json.str(i, "name"))) out.add(Json.copy(i));
        }
        return out;
    }
}
