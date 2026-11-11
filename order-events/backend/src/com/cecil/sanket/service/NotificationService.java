package com.cecil.sanket.service;

import com.cecil.sanket.broker.ConsumerGroup;
import com.cecil.sanket.broker.Handler;
import com.cecil.sanket.model.Event;
import com.cecil.sanket.model.Notification;
import com.cecil.sanket.model.Role;
import com.cecil.sanket.model.User;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The notification service (consumer group "notifier") sends the customer a message (shown in their inbox,
 * like an SMS) when something happens to their order.
 *
 * It keeps its own little list "order -> customer", learned from OrderPlaced, because a service should not
 * read another service's tables. If an event arrives before OrderPlaced was seen, it throws and is retried.
 *
 * The messages are filled from small templates with {{placeholders}}. The template code has a known bug:
 * a gift note that contains "{{" breaks it (the "poison message" demo). Until the fix is deployed
 * (meta "notifier.safeTemplates") such an event fails every try and ends in the dead-letter list.
 */
public class NotificationService {
    private final DataStore store;

    public NotificationService(DataStore store) { this.store = store; }

    public static final Map<String, String> TEMPLATES = new LinkedHashMap<>();
    static {
        TEMPLATES.put("OrderPlaced", "We got your order {{order}} for ₹{{total}}. We are checking stock and payment now.{{gift}}");
        TEMPLATES.put("OrderConfirmed", "Good news! Order {{order}} is confirmed. Courier: {{courier}}, tracking {{awb}}.");
        TEMPLATES.put("OrderCancelled", "Order {{order}} was cancelled. {{detail}}");
        TEMPLATES.put("PaymentRefunded", "₹{{amount}} for order {{order}} is on its way back to you (ref {{reference}}).");
        TEMPLATES.put("ShipmentDispatched", "Your parcel for order {{order}} has left our warehouse. Tracking {{awb}}.");
        TEMPLATES.put("ShipmentDelivered", "Order {{order}} was delivered. Thank you for shopping with us!");
    }

    public ConsumerGroup group() {
        return new ConsumerGroup("notifier", "notification-service", "Sends messages to customers",
                List.of("orders", "payments", "shipments"), TEMPLATES.keySet(), this::onEvent);
    }

    private void onEvent(Event e, Handler.Out out) {
        String id = e.str("orderId");
        if (e.type.equals("OrderPlaced")) {
            store.contacts.put(id, e.str("customerId"));
            store.markDirty("contacts");
        }
        String customer = store.contacts.get(id);
        if (customer == null) throw new IllegalStateException("Customer of " + id + " not known yet (OrderPlaced not seen)");
        Map<String, Object> vars = new LinkedHashMap<>(e.payload);
        vars.put("order", id);
        String gift = e.str("giftNote");
        if (gift != null && gift.contains("{{") && !store.flag("notifier.safeTemplates"))
            throw new IllegalStateException("Template error: unclosed '{{' in gift note \"" + gift + "\"");
        vars.put("gift", gift == null ? "" : " Gift note: \"" + gift.replace("{{", "{ {") + "\"");
        String text = render(TEMPLATES.get(e.type), vars);
        Notification n = new Notification();
        n.id = store.nextId("MSG", 1);
        n.customerId = customer;
        n.orderId = id;
        n.kind = e.type;
        n.text = text;
        n.at = Clock.millis();
        store.notifications.put(n.id, n);
        store.markDirty("notifications");
    }

    /** Replaces {{name}} with the value. Unknown names become empty. */
    static String render(String template, Map<String, Object> vars) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int a = template.indexOf("{{", i);
            if (a < 0) { sb.append(template, i, template.length()); break; }
            int b = template.indexOf("}}", a);
            if (b < 0) throw new IllegalStateException("Template error: unclosed '{{'");
            sb.append(template, i, a);
            Object v = vars.get(template.substring(a + 2, b).trim());
            sb.append(v == null ? "" : String.valueOf(v));
            i = b + 2;
        }
        return sb.toString();
    }

    public List<Object> inbox(User u) {
        synchronized (store) {
            List<Object> out = new ArrayList<>();
            List<Notification> all = new ArrayList<>(store.notifications.values());
            for (int i = all.size() - 1; i >= 0 && out.size() < 100; i--) {
                Notification n = all.get(i);
                if (u.role == Role.CUSTOMER && !n.customerId.equals(u.customerId)) continue;
                Map<String, Object> m = n.toJson();
                m.put("customer", store.customerName(n.customerId));
                out.add(m);
            }
            return out;
        }
    }

    public int markRead(User u) {
        synchronized (store) {
            int n = 0;
            for (Notification x : store.notifications.values())
                if (!x.read && u.customerId != null && u.customerId.equals(x.customerId)) { x.read = true; n++; }
            if (n > 0) store.markDirty("notifications");
            return n;
        }
    }
}
