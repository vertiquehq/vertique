// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.json.VertiqueJson;

/**
 * INTERNAL: reads persisted state as a Jackson tree that keeps every number exactly.
 *
 * <p>Floating-point numbers are read as {@link java.math.BigDecimal}, so a value that differs from another beyond the
 * precision of a {@code double} stays different, and its text is written back unrounded. An integer and a decimal with
 * the same value, such as {@code 1} and {@code 1.0}, are different nodes and so a change; decimals that differ only in
 * trailing zeros, such as {@code 1.0} and {@code 1.00}, are equal.
 *
 * <p>The reading mapper is a copy of the process codec's mapper, so the configured profile's read limits, parser
 * leniency and modules apply to what is read. The copy is derived at use time and rebuilt when the process mapper is
 * swapped; it is never captured in a static initializer.
 */
final class ExactJson {

    /** A process mapper paired with the exact-number reader derived from it. */
    private record Derived(ObjectMapper source, ObjectMapper exact) {}

    private static volatile Derived derived;

    private ExactJson() {
        // static helpers only
    }

    /** Returns the exact-number mapper for the live process mapper, deriving it again when that mapper was swapped. */
    private static ObjectMapper mapper() {
        ObjectMapper live = VertiqueJson.mapper();
        Derived current = derived;
        if (current != null && current.source() == live) {
            return current.exact();
        }
        // concurrent callers may both derive; the copies are equivalent and either may win
        ObjectMapper exact = live.copy()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        derived = new Derived(live, exact);
        return exact;
    }

    /** Reads JSON text as a tree. */
    static JsonNode read(String json) {
        try {
            return mapper().readTree(json);
        } catch (JsonProcessingException e) {
            // the message of a parse failure quotes the text, which can hold values: never carry it
            throw new IllegalArgumentException("A state could not be read as JSON");
        }
    }

    /** Reads a JSON object; {@code null} gives an empty object. */
    static ObjectNode readObject(String json) {
        if (json == null) {
            return mapper().createObjectNode();
        }
        JsonNode node = read(json);
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalArgumentException("A state is not a JSON object");
    }
}
