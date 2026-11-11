package com.cecil.sanket.broker;

import com.cecil.sanket.model.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The code a consumer group runs for each event. It may change its own service's data and emit new events.
 * Throwing an exception means "I could not handle it now": the dispatcher will try again later (retry),
 * and after the last try it moves the event to the dead-letter list.
 */
@FunctionalInterface
public interface Handler {
    void handle(Event event, Out out) throws Exception;

    /** The events a handler wants to publish. They are written only if the handler finishes without an error. */
    final class Out {
        public record Emit(String topic, String type, int version, Map<String, Object> payload) { }

        final List<Emit> emits = new ArrayList<>();

        public void emit(String topic, String type, Map<String, Object> payload) {
            Schemas.validate(topic, type, Schemas.latest(type), payload);   // fail early, before anything is changed
            emits.add(new Emit(topic, type, Schemas.latest(type), payload));
        }

        public List<Emit> emits() { return emits; }
    }
}
