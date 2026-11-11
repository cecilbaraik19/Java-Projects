package com.cecil.sanket.service;

import com.cecil.sanket.broker.ConsumerGroup;
import com.cecil.sanket.broker.Handler;
import com.cecil.sanket.model.Event;
import com.cecil.sanket.model.Payment;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.util.Json;

import java.util.List;
import java.util.Set;

/**
 * The payment service (consumer group "payment") talks to a (made-up) payment gateway.
 *   StockReserved   -> take the money: PaymentAuthorized, or PaymentFailed if the bank says no
 *   OrderCancelled  -> give the money back: PaymentRefunded                 = compensation
 *
 * Two kinds of failure are handled differently:
 *   - a BUSINESS answer ("card declined") is a normal result -> a PaymentFailed event
 *   - a TECHNICAL problem ("gateway did not answer") is thrown -> the dispatcher retries 1 s, 2 s, 4 s later,
 *     and after 4 tries the event goes to the dead-letter list.
 * Before charging it checks its own record for the order, so a repeated event can never charge twice.
 */
public class PaymentService {
    public static final int DEFAULT_DECLINE_PCT = 5;

    private final DataStore store;
    /** Fault from the simulator: the gateway does not answer. */
    public volatile boolean gatewayDown;

    public PaymentService(DataStore store) { this.store = store; }

    public ConsumerGroup group() {
        return new ConsumerGroup("payment", "payment-service", "Charges and refunds through the gateway",
                List.of("orders", "inventory"), Set.of("StockReserved", "OrderCancelled"), this::onEvent);
    }

    private void onEvent(Event e, Handler.Out out) {
        String id = e.str("orderId");
        Payment p = store.payments.get(id);
        if (e.type.equals("StockReserved")) {
            if (p != null) return;                       // already charged, failed or voided: never charge twice
            String method = e.str("paymentMethod");
            long amount = e.num("total");
            if (!"COD".equals(method) && gatewayDown)
                throw new IllegalStateException("Payment gateway timeout (no answer in 3 s)");
            p = new Payment();
            p.id = store.nextId("PAY", 50001);
            p.orderId = id;
            p.amount = amount;
            p.method = method;
            p.at = Clock.millis();
            if (!"COD".equals(method) && declines(id)) {
                p.state = "FAILED";
                p.reason = switch (method) {
                    case "UPI" -> "UPI request expired - the customer did not approve it";
                    case "WALLET" -> "Wallet balance too low";
                    default -> "Card declined by the bank";
                };
                store.payments.put(id, p);
                store.markDirty("payments");
                out.emit("payments", "PaymentFailed", Json.obj("orderId", id, "reason", p.reason));
                return;
            }
            p.state = "AUTHORIZED";
            p.reference = ("COD".equals(method) ? "COD-" : "GW-") + Integer.toHexString(Math.abs((id + "#ref").hashCode())).toUpperCase();
            store.payments.put(id, p);
            store.markDirty("payments");
            out.emit("payments", "PaymentAuthorized", Json.obj("orderId", id, "paymentId", p.id, "amount", amount,
                    "method", method, "reference", p.reference, "city", e.str("city") == null ? "" : e.str("city")));
        } else if (e.type.equals("OrderCancelled")) {
            if (p == null) {
                // cancelled before we saw StockReserved (it is on another topic, so it may come later):
                // leave a "voided" record so that we will not charge when it arrives
                p = new Payment();
                p.id = store.nextId("PAY", 50001);
                p.orderId = id;
                p.state = "VOIDED";
                p.reason = "Order cancelled before payment";
                p.at = Clock.millis();
                store.payments.put(id, p);
                store.markDirty("payments");
                return;
            }
            if (!"AUTHORIZED".equals(p.state)) return;
            if ("COD".equals(p.method)) {
                p.state = "VOIDED";
                p.reason = "Cash on delivery - nothing was paid";
                store.markDirty("payments");
                return;
            }
            p.state = "REFUNDED";
            p.reason = "Refund for a cancelled order";
            store.markDirty("payments");
            out.emit("payments", "PaymentRefunded", Json.obj("orderId", id, "amount", p.amount, "reference", "RF-" + p.reference));
        }
    }

    /** The made-up bank: declines about declinePct % of payments. Same order id = same answer (repeatable demo). */
    boolean declines(String orderId) {
        long pct = Json.num(store.meta, "sim.declinePct", DEFAULT_DECLINE_PCT);
        return Math.floorMod((orderId + "#pay").hashCode(), 100) < pct;
    }
}
