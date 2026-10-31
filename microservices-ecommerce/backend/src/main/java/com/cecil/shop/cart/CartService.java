package com.cecil.shop.cart;

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
 * CART SERVICE - each customer's shopping cart.
 *
 * It stores only (productId, qty). Prices are always fetched fresh from the catalog service,
 * so a cart never shows an old price.
 *
 * Listens to events:
 *   order.confirmed  -> empty that customer's cart
 *   product.retired  -> remove that product from every cart
 */
public class CartService extends ServiceBase {
    private static final String CARTS = "carts";
    public static final int MAX_QTY = 10, MAX_LINES = 20;

    public CartService(Config cfg) {
        super(Config.CART, cfg);
        on("order.confirmed", ev -> clear(Json.lng(ev, "userId")));
        on("product.retired", ev -> removeEverywhere(Json.lng(ev, "productId")));
    }

    @Override public String role() { return "Shopping carts"; }

    @Override
    protected void routes(Router r) {
        r.get("/cart", req -> view(req, true));
        r.get("/cart/raw", req -> Json.obj("items", cart(req.requireUser()).get("items")));
        r.post("/cart/items", this::add);
        r.put("/cart/items/{productId}", this::setQty);
        r.delete("/cart/items/{productId}", req -> { setLine(req.requireUser(), req.paramLong("productId"), 0); return view(req, false); });
        r.delete("/cart", req -> { clear(req.requireUser()); return view(req, false); });
    }

    private synchronized Map<String, Object> cart(long userId) {
        Map<String, Object> c = store.findFirst(CARTS, x -> Json.lng(x, "userId") == userId);
        if (c == null) c = store.insert(CARTS, Json.obj("userId", userId, "items", new ArrayList<>(), "updatedAt", System.currentTimeMillis()));
        return c;
    }

    private Object add(Req r) {
        long uid = r.requireUser();
        Map<String, Object> b = r.json();
        long pid = Json.lng(b, "productId");
        long qty = Json.lng(b, "qty", 1);
        if (qty < 1 || qty > MAX_QTY) throw ApiError.bad("Quantity must be 1 to " + MAX_QTY);
        // Ask the catalog service: does this product exist and is it on sale?
        Map<String, Object> p = client.okJson(Config.CATALOG, "GET", "/products/" + pid, null, r);
        if (!Json.bool(p, "active", true)) throw ApiError.conflict("This product is no longer sold");
        long current = 0;
        for (Object o : Json.list(cart(uid), "items")) if (Json.lng(Json.map(o), "productId") == pid) current = Json.lng(Json.map(o), "qty");
        setLine(uid, pid, Math.min(MAX_QTY, current + qty));
        return view(r, false);
    }

    private Object setQty(Req r) {
        long uid = r.requireUser();
        long qty = Json.lng(r.json(), "qty", -1);
        if (qty < 0 || qty > MAX_QTY) throw ApiError.bad("Quantity must be 0 to " + MAX_QTY);
        setLine(uid, r.paramLong("productId"), qty);
        return view(r, false);
    }

    private synchronized void setLine(long uid, long pid, long qty) {
        Map<String, Object> c = cart(uid);
        List<Object> items = Json.list(c, "items");
        boolean found = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            Map<String, Object> it = Json.map(items.get(i));
            if (Json.lng(it, "productId") == pid) {
                found = true;
                if (qty == 0) items.remove(i);
                else it.put("qty", qty);
            }
        }
        if (!found && qty > 0) {
            if (items.size() >= MAX_LINES) throw ApiError.bad("Your cart is full (" + MAX_LINES + " different products)");
            items.add(Json.obj("productId", pid, "qty", qty, "addedAt", System.currentTimeMillis()));
        }
        c.put("updatedAt", System.currentTimeMillis());
        store.update(CARTS, c);
    }

    private synchronized void clear(long uid) {
        if (uid <= 0) return;
        Map<String, Object> c = cart(uid);
        c.put("items", new ArrayList<>());
        c.put("updatedAt", System.currentTimeMillis());
        store.update(CARTS, c);
    }

    private synchronized void removeEverywhere(long pid) {
        for (Map<String, Object> c : store.all(CARTS)) {
            if (Json.list(c, "items").removeIf(o -> Json.lng(Json.map(o), "productId") == pid)) store.update(CARTS, c);
        }
    }

    /** The cart with current names and prices from the catalog (API composition). */
    private Object view(Req r, boolean tolerateCatalogDown) {
        long uid = r.requireUser();
        List<Object> items = Json.list(cart(uid), "items");
        Map<String, Object> out = new LinkedHashMap<>();
        if (items.isEmpty()) {
            out.put("items", List.of()); out.put("subtotal", 0L); out.put("count", 0L);
            return out;
        }
        StringBuilder ids = new StringBuilder();
        for (Object o : items) ids.append(ids.length() == 0 ? "" : ",").append(Json.lng(Json.map(o), "productId"));
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        boolean pricesKnown = true;
        try {
            for (Object o : client.okList(Config.CATALOG, "GET", "/products/batch?ids=" + ids, null, r)) byId.put(Json.lng(Json.map(o), "id"), Json.map(o));
        } catch (ApiError e) {
            if (!tolerateCatalogDown || e.status != 503) throw e;
            pricesKnown = false; // catalog is down: still show what is in the cart, without prices
        }
        List<Object> lines = new ArrayList<>();
        long subtotal = 0, count = 0;
        for (Object o : items) {
            Map<String, Object> it = Json.map(o);
            long pid = Json.lng(it, "productId"), qty = Json.lng(it, "qty");
            Map<String, Object> p = byId.get(pid);
            if (pricesKnown && (p == null || !Json.bool(p, "active", true))) continue;
            long price = p == null ? 0 : Json.lng(p, "price");
            lines.add(Json.obj("productId", pid, "qty", qty, "name", p == null ? "Product #" + pid : p.get("name"),
                    "sku", p == null ? "" : p.get("sku"), "icon", p == null ? "box" : p.get("icon"), "image", p == null ? "" : Json.str(p, "image", ""),
                    "category", p == null ? "" : p.get("category"), "price", price, "mrp", p == null ? 0 : p.get("mrp"), "lineTotal", price * qty));
            subtotal += price * qty;
            count += qty;
        }
        out.put("items", lines);
        out.put("subtotal", subtotal);
        out.put("count", count);
        out.put("pricesKnown", pricesKnown);
        return out;
    }
}
