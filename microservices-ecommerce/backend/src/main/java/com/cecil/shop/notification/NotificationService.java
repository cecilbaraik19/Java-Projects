package com.cecil.shop.notification;

import com.cecil.shop.common.Config;
import com.cecil.shop.common.Json;
import com.cecil.shop.common.Req;
import com.cecil.shop.common.Router;
import com.cecil.shop.common.ServiceBase;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * NOTIFICATION SERVICE - sends messages to customers (here: an in-app inbox; in real life email / SMS).
 *
 * Nobody calls this service to "send a notification". It only LISTENS to events
 * (order.*, payment.refunded, user.registered, stock.low) and decides by itself what to tell whom.
 * You could add an SMS service tomorrow without changing the order service at all.
 */
public class NotificationService extends ServiceBase {
    private static final String NOTES = "notifications";
    /** userId 0 = message for all admins */
    private static final long ADMINS = 0;

    public NotificationService(Config cfg) {
        super(Config.NOTIFICATION, cfg);
        on("user.registered", ev -> add(Json.lng(ev, "userId"), "Welcome to Bandar Bazaar!",
                "Hi " + Json.str(ev, "name") + ", your account is ready. We added Rs 10,000 demo money to your wallet.", "welcome", ev));
        on("order.confirmed", ev -> add(Json.lng(ev, "userId"), "Order " + Json.str(ev, "orderNo") + " confirmed",
                "Payment received and your items are set aside. We will tell you when it ships.", "order", ev));
        on("order.failed", ev -> add(Json.lng(ev, "userId"), "Order " + Json.str(ev, "orderNo") + " did not go through",
                Json.str(ev, "reason"), "problem", ev));
        on("order.rejected", ev -> add(Json.lng(ev, "userId"), "Order " + Json.str(ev, "orderNo") + " could not be placed",
                Json.str(ev, "reason"), "problem", ev));
        on("order.cancelled", ev -> add(Json.lng(ev, "userId"), "Order " + Json.str(ev, "orderNo") + " cancelled",
                Json.str(ev, "reason"), "order", ev));
        on("order.status", ev -> add(Json.lng(ev, "userId"), "Order " + Json.str(ev, "orderNo") + " is " + Json.str(ev, "status").toLowerCase(),
                switch (Json.str(ev, "status")) {
                    case "PACKED" -> "Your parcel is packed and waiting for the courier.";
                    case "SHIPPED" -> "Your parcel is on the way.";
                    case "DELIVERED" -> "Your parcel was delivered. Enjoy!";
                    default -> "Status changed.";
                }, "ship", ev));
        on("payment.refunded", ev -> add(Json.lng(ev, "userId"), "Refund done",
                "Rs " + (Json.lng(ev, "amount") / 100) + " for " + Json.str(ev, "orderNo") + " is back in your wallet.", "money", ev));
        on("stock.low", ev -> add(ADMINS, "Low stock: " + Json.str(ev, "name"),
                "Only " + Json.lng(ev, "available") + " left (" + Json.str(ev, "sku") + "). Time to restock.", "stock", ev));
    }

    @Override public String role() { return "Customer messages (listens to events)"; }

    @Override
    protected void routes(Router r) {
        r.get("/notifications", this::mine);
        r.post("/notifications/read-all", req -> {
            long uid = req.requireUser();
            synchronized (this) {
                for (Map<String, Object> n : store.where(NOTES, n -> forMe(req, uid, n) && !Json.bool(n, "read", false))) {
                    n.put("read", true);
                    store.update(NOTES, n);
                }
            }
            return mine(req);
        });
    }

    private static boolean forMe(Req r, long uid, Map<String, Object> n) {
        long to = Json.lng(n, "userId");
        return to == uid || (to == ADMINS && r.isAdmin());
    }

    private synchronized void add(long userId, String title, String body, String kind, Map<String, Object> ev) {
        store.insert(NOTES, Json.obj("userId", userId, "title", title, "body", body == null ? "" : body, "kind", kind,
                "topic", ev.get("_topic"), "traceId", ev.get("_traceId"), "read", false, "at", System.currentTimeMillis()));
        // keep the inbox small
        List<Map<String, Object>> mine = store.where(NOTES, n -> Json.lng(n, "userId") == userId);
        if (mine.size() > 60) {
            mine.sort(Comparator.comparingLong(n -> Json.lng(n, "at")));
            for (int i = 0; i < mine.size() - 60; i++) store.delete(NOTES, Json.lng(mine.get(i), "id"));
        }
    }

    private Object mine(Req r) {
        long uid = r.requireUser();
        List<Map<String, Object>> l = store.where(NOTES, n -> forMe(r, uid, n));
        l.sort(Comparator.comparingLong((Map<String, Object> n) -> Json.lng(n, "at")).reversed());
        long unread = l.stream().filter(n -> !Json.bool(n, "read", false)).count();
        return Json.obj("items", l.size() > 30 ? l.subList(0, 30) : l, "unread", unread);
    }
}
