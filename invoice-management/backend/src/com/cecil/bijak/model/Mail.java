package com.cecil.bijak.model;

import com.cecil.bijak.util.Json;

import java.util.Map;

/**
 * An e-mail Bijak wrote (invoice sent, payment reminder, receipt). It is kept in the OUTBOX: this demo does not
 * really send e-mail, but the text is exactly what would go out.
 */
public class Mail implements Json.Writable {
    public String id, kind, to, customerId, invoiceId, subject, body, by;
    public int level;
    public long at;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "kind", kind, "to", to, "customerId", customerId, "invoiceId", invoiceId,
                "subject", subject, "body", body, "level", level, "at", at, "by", by);
    }

    public static Mail fromJson(Map<String, Object> m) {
        Mail x = new Mail();
        x.id = Json.str(m, "id");
        x.kind = Json.str(m, "kind");
        x.to = Json.str(m, "to");
        x.customerId = Json.str(m, "customerId");
        x.invoiceId = Json.str(m, "invoiceId");
        x.subject = Json.str(m, "subject");
        x.body = Json.str(m, "body");
        x.level = (int) Json.num(m, "level", 0);
        x.at = Json.num(m, "at", 0);
        x.by = Json.str(m, "by");
        return x;
    }
}
