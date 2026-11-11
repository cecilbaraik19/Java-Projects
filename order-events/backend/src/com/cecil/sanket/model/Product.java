package com.cecil.sanket.model;

import com.cecil.sanket.util.Json;

import java.util.Map;

/**
 * Something the shop sells. Owned by the inventory service.
 * available = stock - reserved. "reserved" is stock held for orders that are not dispatched yet.
 */
public class Product implements Json.Writable {
    public String sku;
    public String name;
    public String category;
    public long price;          // rupees
    public int stock;           // units on the shelf
    public int reserved;        // units held for open orders
    public int reorderLevel;    // below this the product shows as "low"
    public String icon;         // a pictogram name for the website

    public int available() { return Math.max(0, stock - reserved); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("sku", sku, "name", name, "category", category, "price", price, "stock", stock,
                "reserved", reserved, "reorderLevel", reorderLevel, "icon", icon);
    }

    public Map<String, Object> view() {
        Map<String, Object> m = toJson();
        m.put("available", available());
        m.put("low", available() <= reorderLevel);
        return m;
    }

    public static Product fromJson(Map<String, Object> m) {
        Product p = new Product();
        p.sku = Json.str(m, "sku");
        p.name = Json.str(m, "name");
        p.category = Json.str(m, "category");
        p.price = Json.num(m, "price", 0);
        p.stock = (int) Json.num(m, "stock", 0);
        p.reserved = (int) Json.num(m, "reserved", 0);
        p.reorderLevel = (int) Json.num(m, "reorderLevel", 5);
        p.icon = Json.str(m, "icon", "box");
        return p;
    }
}
