package com.cecil.shop.payment;

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
import java.util.List;
import java.util.Map;

/**
 * PAYMENT SERVICE - a demo wallet ("Bandar Pay"). No real money and no real cards.
 *
 *  POST /payments/charge   take money for an order (only the order service may call this)
 *  POST /payments/refund   give the money back (saga compensation / order cancelled)
 *  GET  /wallet            my balance and history
 *  POST /wallet/topup      add demo money
 *
 * Charge and refund are IDEMPOTENT per order: if the order service retries after a timeout,
 * the customer is never charged twice.
 */
public class PaymentService extends ServiceBase {
    private static final String WALLETS = "wallets", PAYMENTS = "payments", LEDGER = "ledger";
    public static final long WELCOME_BONUS = 10_000_00L; // Rs 10,000 in paise
    public static final long MAX_TOPUP = 50_000_00L, MAX_BALANCE = 5_00_000_00L;

    public PaymentService(Config cfg) {
        super(Config.PAYMENT, cfg);
        on("user.registered", ev -> openWallet(Json.lng(ev, "userId"), Json.str(ev, "name")));
    }

    @Override public String role() { return "Wallet payments and refunds"; }

    @Override
    protected void seed() {
        if (!store.isEmpty(WALLETS)) return;
        long now = System.currentTimeMillis();
        for (Map<String, Object> w : SeedData.wallets()) {
            store.insert(WALLETS, w);
            if (Json.lng(w, "balance") > 0) {
                store.insert(LEDGER, Json.obj("userId", w.get("userId"), "kind", "TOPUP", "amount", w.get("balance"),
                        "note", "Opening balance", "at", now - 30 * 86_400_000L));
            }
        }
        for (Map<String, Object> p : SeedData.payments(now)) {
            store.insert(PAYMENTS, p);
            store.insert(LEDGER, Json.obj("userId", p.get("userId"), "kind", "PAYMENT", "amount", -Json.lng(p, "amount"),
                    "note", "Order " + Json.str(p, "orderNo"), "at", p.get("at")));
            if ("REFUNDED".equals(Json.str(p, "status"))) {
                store.insert(LEDGER, Json.obj("userId", p.get("userId"), "kind", "REFUND", "amount", p.get("amount"),
                        "note", "Refund for " + Json.str(p, "orderNo"), "at", p.get("refundedAt")));
            }
        }
    }

    @Override
    protected void routes(Router r) {
        r.post("/payments/charge", this::charge);
        r.post("/payments/refund", this::refund);
        r.get("/payments", this::all);
        r.get("/wallet", this::wallet);
        r.post("/wallet/topup", this::topup);
    }

    private synchronized Map<String, Object> walletOf(long userId) {
        Map<String, Object> w = store.get(WALLETS, userId);
        if (w == null) w = store.insert(WALLETS, Json.obj("id", userId, "userId", userId, "name", "", "balance", 0L, "welcomed", false));
        return w;
    }

    private synchronized void openWallet(long userId, String name) {
        if (userId <= 0) return;
        Map<String, Object> w = store.get(WALLETS, userId);
        // The customer may open the wallet page before this event arrives (an empty wallet is made then).
        // So we check a flag, not "does a wallet exist", to give the bonus exactly once.
        if (w != null && Json.bool(w, "welcomed", true)) return;
        if (w == null) {
            store.insert(WALLETS, Json.obj("id", userId, "userId", userId, "name", name, "balance", WELCOME_BONUS, "welcomed", true));
        } else {
            w.put("balance", Json.lng(w, "balance") + WELCOME_BONUS);
            w.put("welcomed", true);
            w.put("name", name);
            store.update(WALLETS, w);
        }
        store.insert(LEDGER, Json.obj("userId", userId, "kind", "TOPUP", "amount", WELCOME_BONUS,
                "note", "Welcome bonus (from the user.registered event)", "at", System.currentTimeMillis()));
    }

    private Object charge(Req r) {
        if (!Config.ORDER.equals(r.caller())) throw ApiError.forbidden("Only the order service can take payments");
        Map<String, Object> b = r.json();
        long orderId = Json.lng(b, "orderId"), userId = Json.lng(b, "userId"), amount = Json.lng(b, "amount");
        String orderNo = Json.str(b, "orderNo", "");
        if (orderId <= 0 || userId <= 0 || amount <= 0) throw ApiError.bad("orderId, userId and amount are required");
        Map<String, Object> pay;
        synchronized (this) {
            Map<String, Object> existing = store.findFirst(PAYMENTS, p -> Json.lng(p, "orderId") == orderId);
            if (existing != null) {
                if ("DECLINED".equals(Json.str(existing, "status"))) return new Resp(402, Json.obj("error", Json.str(existing, "reason"), "payment", existing));
                return existing; // same order charged again: return the first result, do NOT take money twice
            }
            Map<String, Object> w = walletOf(userId);
            long bal = Json.lng(w, "balance");
            if (bal < amount) {
                String why = "Not enough money in wallet (balance Rs " + rupees(bal) + ", needed Rs " + rupees(amount) + ")";
                pay = store.insert(PAYMENTS, Json.obj("orderId", orderId, "orderNo", orderNo, "userId", userId, "amount", amount,
                        "status", "DECLINED", "reason", why, "at", System.currentTimeMillis(), "refundedAt", 0L));
                publish("payment.declined", Json.obj("orderId", orderId, "orderNo", orderNo, "userId", userId, "amount", amount, "reason", why), r.traceId);
                return new Resp(402, Json.obj("error", why, "payment", pay));
            }
            w.put("balance", bal - amount);
            store.update(WALLETS, w);
            pay = store.insert(PAYMENTS, Json.obj("orderId", orderId, "orderNo", orderNo, "userId", userId, "amount", amount,
                    "status", "CAPTURED", "reason", "", "at", System.currentTimeMillis(), "refundedAt", 0L));
            store.insert(LEDGER, Json.obj("userId", userId, "kind", "PAYMENT", "amount", -amount, "note", "Order " + orderNo, "at", System.currentTimeMillis()));
        }
        publish("payment.captured", Json.obj("orderId", orderId, "orderNo", orderNo, "userId", userId, "amount", amount), r.traceId);
        return pay;
    }

    private Object refund(Req r) {
        if (!Config.ORDER.equals(r.caller())) throw ApiError.forbidden("Only the order service can give refunds");
        long orderId = Json.lng(r.json(), "orderId");
        Map<String, Object> pay;
        synchronized (this) {
            pay = store.findFirst(PAYMENTS, p -> Json.lng(p, "orderId") == orderId);
            if (pay == null) return Json.obj("ok", true, "note", "No payment was taken for this order");
            if (!"CAPTURED".equals(Json.str(pay, "status"))) return pay; // already refunded or declined: nothing to do
            Map<String, Object> w = walletOf(Json.lng(pay, "userId"));
            w.put("balance", Json.lng(w, "balance") + Json.lng(pay, "amount"));
            store.update(WALLETS, w);
            pay.put("status", "REFUNDED");
            pay.put("refundedAt", System.currentTimeMillis());
            store.update(PAYMENTS, pay);
            store.insert(LEDGER, Json.obj("userId", pay.get("userId"), "kind", "REFUND", "amount", pay.get("amount"),
                    "note", "Refund for " + Json.str(pay, "orderNo"), "at", System.currentTimeMillis()));
        }
        publish("payment.refunded", Json.obj("orderId", orderId, "orderNo", pay.get("orderNo"), "userId", pay.get("userId"),
                "amount", pay.get("amount")), r.traceId);
        return pay;
    }

    private Object wallet(Req r) {
        long uid = r.requireUser();
        Map<String, Object> w = walletOf(uid);
        List<Map<String, Object>> hist = store.where(LEDGER, l -> Json.lng(l, "userId") == uid);
        hist.sort(Comparator.comparingLong((Map<String, Object> l) -> Json.lng(l, "at")).reversed());
        return Json.obj("balance", w.get("balance"), "history", hist.size() > 40 ? hist.subList(0, 40) : hist);
    }

    private Object topup(Req r) {
        long uid = r.requireUser();
        double rs = Json.dbl(r.json(), "amount", 0);
        long amount = Math.round(rs * 100);
        if (amount < 100_00 || amount > MAX_TOPUP) throw ApiError.bad("Add between Rs 100 and Rs 50,000 at a time");
        synchronized (this) {
            Map<String, Object> w = walletOf(uid);
            if (Json.lng(w, "balance") + amount > MAX_BALANCE) throw ApiError.bad("Wallet limit is Rs 5,00,000");
            w.put("balance", Json.lng(w, "balance") + amount);
            store.update(WALLETS, w);
            store.insert(LEDGER, Json.obj("userId", uid, "kind", "TOPUP", "amount", amount, "note", "Added demo money", "at", System.currentTimeMillis()));
        }
        return wallet(r);
    }

    private Object all(Req r) {
        r.requireAdmin();
        List<Map<String, Object>> list = store.all(PAYMENTS);
        list.sort(Comparator.comparingLong((Map<String, Object> p) -> Json.lng(p, "at")).reversed());
        long captured = 0, refunded = 0, declined = 0;
        for (Map<String, Object> p : list) {
            switch (Json.str(p, "status")) {
                case "CAPTURED" -> captured += Json.lng(p, "amount");
                case "REFUNDED" -> refunded += Json.lng(p, "amount");
                default -> declined++;
            }
        }
        return Json.obj("payments", new ArrayList<>(list.subList(0, Math.min(100, list.size()))),
                "captured", captured, "refunded", refunded, "declinedCount", declined);
    }

    static String rupees(long paise) {
        long rs = paise / 100;
        String s = String.valueOf(rs);
        if (s.length() <= 3) return s + (paise % 100 == 0 ? "" : String.format(".%02d", paise % 100));
        String last3 = s.substring(s.length() - 3);
        StringBuilder rest = new StringBuilder(s.substring(0, s.length() - 3));
        for (int i = rest.length() - 2; i > 0; i -= 2) rest.insert(i, ',');
        return rest + "," + last3 + (paise % 100 == 0 ? "" : String.format(".%02d", paise % 100));
    }
}
