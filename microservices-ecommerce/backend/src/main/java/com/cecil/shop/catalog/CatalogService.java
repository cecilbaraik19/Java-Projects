package com.cecil.shop.catalog;

import com.cecil.shop.common.ApiError;
import com.cecil.shop.common.Config;
import com.cecil.shop.common.Json;
import com.cecil.shop.common.Req;
import com.cecil.shop.common.Resp;
import com.cecil.shop.common.Router;
import com.cecil.shop.common.ServiceBase;
import com.cecil.shop.seed.SeedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CATALOG SERVICE - owns product information: name, price, category, description.
 * It does NOT know how many are in stock (that is the inventory service's job).
 *
 *  GET    /products               search: ?q=&category=&sort=price-asc|price-desc|rating|newest
 *  GET    /products/{id}
 *  GET    /products/batch?ids=1,2 several products in one call (used by cart and order services)
 *  GET    /categories             categories with product counts
 *  POST   /products               add (admin)    -> event "product.created"
 *  PUT    /products/{id}          edit (admin)   -> event "product.updated"
 *  DELETE /products/{id}          retire (admin) -> event "product.retired"
 */
public class CatalogService extends ServiceBase {
    private static final String PRODUCTS = "products";
    public static final List<String> CATEGORIES = List.of("Electronics", "Fashion", "Home & Kitchen", "Books", "Sports & Fitness", "Beauty");
    public static final Set<String> ICONS = Set.of("headphones", "phone", "watch", "laptop", "keyboard", "speaker", "camera", "tshirt",
            "shoe", "backpack", "sunglasses", "cap", "mug", "lamp", "plant", "bottle", "pot", "book", "yoga", "dumbbell", "ball", "bat",
            "perfume", "jar", "box");
    private static final Pattern SKU = Pattern.compile("[A-Z0-9-]{3,20}");

    public CatalogService(Config cfg) { super(Config.CATALOG, cfg); }

    @Override public String role() { return "Products, prices and categories"; }

    @Override
    protected void seed() {
        if (store.isEmpty(PRODUCTS)) for (Map<String, Object> p : SeedData.products(System.currentTimeMillis())) store.insert(PRODUCTS, p);
    }

    @Override
    protected void routes(Router r) {
        r.get("/products", this::search);
        r.get("/products/batch", this::batch);
        r.get("/products/{id}", req -> {
            Map<String, Object> p = store.get(PRODUCTS, req.paramLong("id"));
            if (p == null || (!Json.bool(p, "active", true) && !req.isAdmin())) throw ApiError.notFound("Product not found");
            return p;
        });
        r.get("/categories", req -> categories());
        r.post("/products", this::create);
        r.put("/products/{id}", this::edit);
        r.delete("/products/{id}", this::retire);
    }

    private Object search(Req r) {
        String q = r.q("q", "").toLowerCase().trim();
        String cat = r.q("category", "");
        boolean all = r.isAdmin() && "true".equals(r.q("all"));
        List<Map<String, Object>> list = store.where(PRODUCTS, p -> {
            if (!all && !Json.bool(p, "active", true)) return false;
            if (!cat.isEmpty() && !cat.equals(Json.str(p, "category"))) return false;
            if (q.isEmpty()) return true;
            String hay = (Json.str(p, "name") + " " + Json.str(p, "brand") + " " + Json.str(p, "category") + " " + Json.str(p, "sku")).toLowerCase();
            for (String word : q.split("\\s+")) if (!hay.contains(word)) return false;
            return true;
        });
        Comparator<Map<String, Object>> cmp = switch (r.q("sort", "popular")) {
            case "price-asc" -> Comparator.comparingLong(p -> Json.lng(p, "price"));
            case "price-desc" -> Comparator.comparingLong((Map<String, Object> p) -> Json.lng(p, "price")).reversed();
            case "rating" -> Comparator.comparingDouble((Map<String, Object> p) -> Json.dbl(p, "rating", 0)).reversed();
            case "newest" -> Comparator.comparingLong((Map<String, Object> p) -> Json.lng(p, "createdAt")).reversed();
            default -> Comparator.comparingLong((Map<String, Object> p) -> Json.lng(p, "reviews")).reversed();
        };
        list.sort(cmp);
        return list;
    }

    private Object batch(Req r) {
        List<Object> out = new ArrayList<>();
        for (String s : r.q("ids", "").split(",")) {
            if (s.isBlank()) continue;
            try {
                Map<String, Object> p = store.get(PRODUCTS, Long.parseLong(s.trim()));
                if (p != null) out.add(p);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private Object categories() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String c : CATEGORIES) counts.put(c, 0L);
        for (Map<String, Object> p : store.where(PRODUCTS, p -> Json.bool(p, "active", true))) counts.merge(Json.str(p, "category"), 1L, Long::sum);
        List<Object> out = new ArrayList<>();
        counts.forEach((k, v) -> out.add(Json.obj("name", k, "count", v)));
        return out;
    }

    private Object create(Req r) {
        r.requireAdmin();
        Map<String, Object> b = r.json();
        Map<String, Object> p = validate(b, null);
        long initial = Json.lng(b, "initialStock", 0);
        if (initial < 0 || initial > 100_000) throw ApiError.bad("Starting stock must be 0 to 100000");
        Map<String, Object> saved;
        synchronized (this) {
            String sku = Json.str(p, "sku");
            if (store.findFirst(PRODUCTS, x -> sku.equals(Json.str(x, "sku"))) != null) throw ApiError.conflict("SKU " + sku + " already exists");
            p.put("rating", 0.0);
            p.put("reviews", 0L);
            p.put("active", true);
            p.put("createdAt", System.currentTimeMillis());
            saved = store.insert(PRODUCTS, p);
        }
        // The inventory service listens to this and creates a stock record. We do not call it directly.
        publish("product.created", Json.obj("productId", saved.get("id"), "sku", saved.get("sku"), "name", saved.get("name"),
                "initialStock", initial), r.traceId);
        return Resp.created(saved);
    }

    private Object edit(Req r) {
        r.requireAdmin();
        Map<String, Object> old = store.get(PRODUCTS, r.paramLong("id"));
        if (old == null) throw ApiError.notFound("Product not found");
        Map<String, Object> p = validate(r.json(), old);
        p.put("sku", old.get("sku")); // SKU never changes - other services use it as the key
        old.putAll(p);
        if (r.json().containsKey("active")) old.put("active", Json.bool(r.json(), "active", true));
        Map<String, Object> saved = store.update(PRODUCTS, old);
        publish("product.updated", Json.obj("productId", saved.get("id"), "sku", saved.get("sku"), "name", saved.get("name"),
                "price", saved.get("price"), "active", saved.get("active")), r.traceId);
        return saved;
    }

    private Object retire(Req r) {
        r.requireAdmin();
        Map<String, Object> p = store.get(PRODUCTS, r.paramLong("id"));
        if (p == null) throw ApiError.notFound("Product not found");
        // Soft delete: old orders still point to this product, so we only hide it from the shop.
        p.put("active", false);
        store.update(PRODUCTS, p);
        publish("product.retired", Json.obj("productId", p.get("id"), "sku", p.get("sku"), "name", p.get("name")), r.traceId);
        return Json.obj("ok", true);
    }

    private Map<String, Object> validate(Map<String, Object> b, Map<String, Object> old) {
        Map<String, Object> p = new LinkedHashMap<>();
        String name = requireText(b, "name", "Product name", 80);
        String category = requireText(b, "category", "Category", 40);
        if (!CATEGORIES.contains(category)) throw ApiError.bad("Unknown category");
        double priceRs = Json.dbl(b, "price", -1);
        double mrpRs = Json.dbl(b, "mrp", priceRs);
        if (priceRs < 1 || priceRs > 500_000) throw ApiError.bad("Price must be between Rs 1 and Rs 5,00,000");
        if (mrpRs < priceRs) throw ApiError.bad("MRP cannot be less than the selling price");
        String icon = Json.str(b, "icon", "box");
        if (!ICONS.contains(icon)) icon = "box";
        if (old == null) {
            String sku = requireText(b, "sku", "SKU", 20).toUpperCase();
            if (!SKU.matcher(sku).matches()) throw ApiError.bad("SKU: 3 to 20 capital letters, digits or dashes");
            p.put("sku", sku);
        }
        p.put("name", name);
        p.put("category", category);
        p.put("brand", optText(b, "brand", 40));
        p.put("price", Math.round(priceRs * 100));
        p.put("mrp", Math.round(mrpRs * 100));
        p.put("icon", icon);
        p.put("description", optText(b, "description", 400));
        String image = optText(b, "image", 500);
        // A photo is a file in the shop (/static/img/...) or a web address (https://...). Anything else is refused.
        if (!image.isEmpty() && !(image.matches("/static/img/products/[a-z0-9-]+\\.(jpg|png|webp)") || image.matches("https://[^\\s\"'<>]+"))) {
            throw ApiError.bad("Photo must be a web address starting with https:// (or left empty)");
        }
        p.put("image", image);
        return p;
    }
}
