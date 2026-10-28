package com.cecil.chatbot.ai;

import com.cecil.chatbot.util.Json;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shared code for calling AI web APIs with Java's built-in HttpClient (java.net.http, no libraries).
 *
 * All three online services stream their answer as Server-Sent Events (SSE):
 *     data: {"some":"json"}
 *     (blank line)
 *     data: {"next":"piece"}
 * We read the reply line by line, so each piece reaches the person as soon as it arrives.
 */
final class HttpAi {

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpAi() { }

    /**
     * POSTs JSON and calls onData with the text after "data:" for every SSE event.
     * Stops early when stop says so (closing the stream also tells the service to stop).
     */
    static void postSse(String url, Map<String, String> headers, Object body, int timeoutSec,
                        Consumer<String> onData, BooleanSupplier stop) throws AiException {
        URI uri;
        try {
            uri = URI.create(url);
            if (uri.getHost() == null || !(url.startsWith("https://") || url.startsWith("http://"))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new AiException("The base URL is not a valid web address: " + url);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(5, timeoutSec)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        headers.forEach(b::header);

        HttpResponse<Stream<String>> res;
        try {
            res = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofLines());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw new AiException("Could not connect to " + uri.getHost() + ". Check the internet connection"
                    + (isLocal(uri) ? " and that the local AI server (for example Ollama) is running." : "."));
        } catch (HttpTimeoutException e) {
            throw new AiException("The AI service took too long to answer (more than " + timeoutSec + " seconds).");
        } catch (IOException e) {
            Throwable c = e.getCause();
            if (c instanceof UnresolvedAddressException || String.valueOf(e.getMessage()).contains("UnresolvedAddress")) {
                throw new AiException("Could not find " + uri.getHost() + ". Check the internet connection and the base URL.");
            }
            throw new AiException("Network problem talking to " + uri.getHost() + ": " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Stopped.");
        }

        try (Stream<String> lines = res.body()) {
            if (res.statusCode() != 200) {
                String text = lines.limit(200).collect(Collectors.joining("\n"));
                throw new AiException(res.statusCode(), friendly(res.statusCode(), text));
            }
            readSse(lines.iterator(), onData, stop);
        } catch (java.io.UncheckedIOException e) {
            throw new AiException("The connection to the AI service was cut while it was answering.");
        }
    }

    /** Reads SSE lines. Several "data:" lines in a row belong to one event. */
    static void readSse(Iterator<String> it, Consumer<String> onData, BooleanSupplier stop) {
        StringBuilder data = new StringBuilder();
        while (!stop.getAsBoolean() && it.hasNext()) {
            String line = it.next();
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    onData.accept(data.toString());
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring(5).stripLeading());
            }
            // "event:", "id:" and ":" comment lines are not needed
        }
        if (data.length() > 0 && !stop.getAsBoolean()) onData.accept(data.toString());
    }

    /** Turns an HTTP error into a simple sentence. Uses the service's own message when it sends one. */
    static String friendly(int status, String body) {
        String detail = errorMessage(body);
        String base = switch (status) {
            case 400 -> "The AI service did not accept the request";
            case 401, 403 -> "The API key was rejected. Check the key on the AI models page";
            case 404 -> "Model or address not found. Check the model name and base URL";
            case 408 -> "The AI service took too long";
            case 413 -> "The message is too long for this model";
            case 429 -> "Too many requests, or the free quota is used up. Wait a minute and try again";
            default -> status >= 500 ? "The AI service has a problem right now (error " + status + ")"
                                     : "The AI service answered with error " + status;
        };
        return detail.isEmpty() ? base + "." : base + ": " + detail;
    }

    /** Finds error.message (OpenAI, Gemini and Claude all use this shape). */
    @SuppressWarnings("unchecked")
    static String errorMessage(String body) {
        if (body == null || body.isBlank()) return "";
        String text = body.trim();
        if (text.startsWith("data:")) text = text.substring(5).trim();
        try {
            Object o = Json.parse(text);
            if (o instanceof java.util.List<?> l && !l.isEmpty()) o = l.get(0); // Gemini sometimes wraps in [ ]
            if (o instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) o;
                Object e = m.get("error");
                if (e instanceof Map) return cut(Json.str((Map<String, Object>) e, "message", ""));
                if (e instanceof String s) return cut(s);
                if (m.get("message") instanceof String s) return cut(s);
            }
        } catch (IllegalArgumentException ignored) {
            // not JSON
        }
        return cut(text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim());
    }

    private static String cut(String s) {
        s = s == null ? "" : s.trim();
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }

    static boolean isLocal(URI u) {
        String h = u.getHost();
        return h != null && (h.equals("localhost") || h.startsWith("127.") || h.equals("::1"));
    }

    static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
