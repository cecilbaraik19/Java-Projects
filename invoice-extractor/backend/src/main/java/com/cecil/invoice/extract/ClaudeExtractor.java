package com.cecil.invoice.extract;

import com.cecil.invoice.model.FieldValue;
import com.cecil.invoice.model.Invoice;
import com.cecil.invoice.model.LineItem;
import com.cecil.invoice.util.Dates;
import com.cecil.invoice.util.Json;
import com.cecil.invoice.util.Money;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Optional: reads the invoice with Claude, Anthropic's large language model.
 * Claude can read photos and scanned PDFs, which the built-in reader cannot.
 *
 * Turned on only when the ANTHROPIC_API_KEY environment variable is set AND the admin
 * switches it on in Settings. The invoice is sent to the Messages API with a prompt that
 * asks for one JSON object; the JSON is then checked by the same Validator as the
 * built-in reader, so Claude's answer is never trusted blindly.
 *
 *   POST https://api.anthropic.com/v1/messages
 *   headers: x-api-key, anthropic-version: 2023-06-01
 *   body:    { model, max_tokens, system, messages: [ { role: user, content: [document/image/text, instructions] } ] }
 */
public final class ClaudeExtractor {

    /** Sends the request body and returns the response body (replaced by a fake in the tests). */
    public interface Transport { String post(String jsonBody) throws Exception; }

    public static final String API = "https://api.anthropic.com/v1/messages";

    private final String model;
    private final Transport transport;

    public ClaudeExtractor(String model, Transport transport) {
        this.model = model;
        this.transport = transport;
    }

    public static String apiKey() {
        String k = System.getenv("ANTHROPIC_API_KEY");
        return k == null || k.isBlank() ? null : k.strip();
    }

    /** The real transport, using the JDK's HttpClient. */
    public static Transport http(String key) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        return body -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API))
                    .timeout(Duration.ofSeconds(120))
                    .header("content-type", "application/json")
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                String msg = res.body();
                try {
                    Object err = Json.parseObject(res.body()).get("error");
                    if (err instanceof Map<?, ?> m) msg = String.valueOf(m.get("message"));
                } catch (Exception ignored) { }
                throw new IllegalStateException("Claude API error " + res.statusCode() + ": " + msg);
            }
            return res.body();
        };
    }

    static final String SYSTEM = "You are an accounts-payable assistant that extracts data from Indian GST invoices. "
            + "Reply with ONE JSON object only, no other text. Use exactly these keys: "
            + "vendorName, vendorGstin, invoiceNo, invoiceDate, dueDate, poNumber, buyerName, buyerGstin, currency, "
            + "subtotal, cgst, sgst, igst, taxTotal, total, amountInWords, paymentTerms, "
            + "items (array of {description, hsn, qty, rate, amount}), confidence (object: field name -> 0..1), "
            + "text (a plain-text copy of the whole invoice, keeping the line order). "
            + "Dates as YYYY-MM-DD. Amounts as plain numbers without commas or currency signs. "
            + "The subtotal is the taxable value before GST. Use \"\" or 0 for anything not on the invoice - never guess.";

    /**
     * Read one invoice.
     * @param mediaType "text/plain", "application/pdf", "image/png" or "image/jpeg"
     * @param data      the text itself, or the file as Base64 for PDFs and images
     */
    public Reading read(String mediaType, String data) throws Exception {
        List<Object> content = new ArrayList<>();
        if (mediaType.equals("text/plain")) {
            content.add(Map.of("type", "text", "text", "INVOICE:\n" + data));
        } else if (mediaType.equals("application/pdf")) {
            content.add(Map.of("type", "document", "source", Map.of("type", "base64", "media_type", mediaType, "data", data)));
        } else {
            content.add(Map.of("type", "image", "source", Map.of("type", "base64", "media_type", mediaType, "data", data)));
        }
        content.add(Map.of("type", "text", "text", "Extract the invoice data as JSON."));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 4000);
        body.put("system", SYSTEM);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        String response = transport.post(Json.write(body));
        return parseResponse(response);
    }

    /** What Claude returned, turned into fields and items. */
    public static final class Reading {
        public final Map<String, FieldValue> fields = new LinkedHashMap<>();
        public final List<LineItem> items = new ArrayList<>();
        public String text = "";
    }

    /** Take the JSON object out of Claude's answer and turn it into fields. */
    public static Reading parseResponse(String responseBody) {
        Map<String, Object> res = Json.parseObject(responseBody);
        StringBuilder all = new StringBuilder();
        if (res.get("content") instanceof List<?> blocks) {
            for (Object b : blocks) if (b instanceof Map<?, ?> m && "text".equals(m.get("type"))) all.append(m.get("text"));
        }
        String t = all.toString();
        int a = t.indexOf('{'), z = t.lastIndexOf('}');
        if (a < 0 || z <= a) throw new IllegalStateException("Claude did not answer with JSON.");
        Map<String, Object> j = Json.parseObject(t.substring(a, z + 1));
        return fromJson(j);
    }

    static Reading fromJson(Map<String, Object> j) {
        Reading r = new Reading();
        Map<?, ?> conf = j.get("confidence") instanceof Map<?, ?> m ? m : Map.of();
        for (String f : Invoice.FIELDS) {
            Object v = j.get(f);
            String value = "";
            boolean amount = Set.of("subtotal", "cgst", "sgst", "igst", "taxTotal", "total").contains(f);
            if (amount) {
                double d = v instanceof Number n ? n.doubleValue() : parseNum(String.valueOf(v));
                value = d == 0 ? "" : Money.plain(d);
            } else if (v != null) {
                value = String.valueOf(v).strip();
                if ((f.equals("invoiceDate") || f.equals("dueDate")) && !value.isEmpty()) {
                    var d = Dates.parse(value);
                    value = d == null ? "" : d.toString();
                }
                if (f.endsWith("Gstin")) value = value.toUpperCase(Locale.ROOT).replaceAll("\\s", "");
            }
            double c = conf.get(f) instanceof Number n ? n.doubleValue() : 0.9;
            c = Math.max(0.05, Math.min(0.99, c));
            r.fields.put(f, value.isEmpty() ? new FieldValue() : new FieldValue(value, c, -1, "", "Claude"));
        }
        if (j.get("items") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                LineItem it = new LineItem();
                it.description = String.valueOf(m.get("description") == null ? "" : m.get("description"));
                it.hsn = m.get("hsn") == null ? "" : String.valueOf(m.get("hsn")).replaceAll("\\.0$", "");
                it.qty = numOf(m.get("qty"));
                it.rate = numOf(m.get("rate"));
                it.amount = numOf(m.get("amount"));
                it.mathOk = Money.near(it.qty * it.rate, it.amount, Math.max(1.0, it.amount * 0.001));
                r.items.add(it);
            }
        }
        r.text = j.get("text") == null ? "" : String.valueOf(j.get("text"));
        return r;
    }

    private static double numOf(Object o) { return o instanceof Number n ? n.doubleValue() : parseNum(String.valueOf(o)); }

    private static double parseNum(String s) {
        Double d = Money.parse(s == null ? "" : s.strip());
        return d == null ? 0 : d;
    }
}
