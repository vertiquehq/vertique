// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.json.KeyedBy;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the composition of the default config mapper ({@link DefaultConfigMapper#lenient()}) by
 * parsing one fixture that exercises <em>every</em> mandatory capability the framework guarantees on
 * the config-parsing seam (FR-CFGI-010c / AC-7). If a future change to {@code lenient()} drops a
 * module or relaxes a policy, a capability assertion here fails.
 *
 * <p>Capabilities pinned, one per registered module / configured policy:
 *
 * <ul>
 *   <li><strong>Jdk8Module</strong> — a JDK8 {@link Optional} field binds to a present value;
 *   <li><strong>JavaTimeModule</strong> — {@code java.time} temporals ({@link Instant},
 *       {@link LocalDate}) parse from ISO strings;
 *   <li><strong>KeyedCollectionModule</strong> — a {@link KeyedBy}-annotated {@code List} field
 *       injects each entry's key into the element's identity property;
 *   <li><strong>VertxModule</strong> — a Vert.x {@link JsonObject} field round-trips non-empty
 *       (without it the field would silently bind to an empty object);
 *   <li><strong>lenient scalar coercion</strong> — a string-encoded scalar ({@code "8080"}) coerces
 *       into an {@code int};
 *   <li><strong>{@code FAIL_ON_UNKNOWN_PROPERTIES} disabled</strong> — an unknown property is
 *       ignored rather than failing the parse.
 * </ul>
 */
class DefaultConfigMapperCompositionTest {

    /** The default config parser under test, built over the lenient default config mapper. */
    private final ConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());

    /**
     * A single record whose components together exercise every mandatory mapper capability.
     *
     * @param maybe a JDK8 {@link Optional} field (Jdk8Module)
     * @param instant a JSR310 {@link Instant} field (JavaTimeModule)
     * @param date a JSR310 {@link LocalDate} field (JavaTimeModule)
     * @param json a Vert.x {@link JsonObject} field (VertxModule)
     * @param port an {@code int} populated from a string-encoded scalar (lenient coercion)
     * @param servers a {@link KeyedBy}-annotated keyed collection (KeyedCollectionModule)
     */
    record AllCapabilities(
            Optional<String> maybe,
            Instant instant,
            LocalDate date,
            JsonObject json,
            int port,
            @KeyedBy("name") List<Server> servers) {}

    /**
     * Element of the keyed collection; its {@code name} is injected from each entry's key.
     *
     * @param name the identity property the keyed-collection key is injected into
     * @param host the per-server host value
     */
    record Server(String name, String host) {}

    @Test
    @DisplayName("lenient default mapper exercises all mandatory capabilities in one parse")
    void defaultMapperExercisesAllMandatoryCapabilities() {
        JsonObject section = new JsonObject()
                .put("maybe", "present")
                .put("instant", "2026-01-02T03:04:05Z")
                .put("date", "2026-06-25")
                .put("json", new JsonObject().put("k", "v"))
                // lenient scalar coercion: a string-encoded int
                .put("port", "8080")
                // unknown property: must be ignored (FAIL_ON_UNKNOWN_PROPERTIES disabled)
                .put("unknownExtra", "ignored")
                // keyed collection: keys inject into Server.name
                .put(
                        "servers",
                        new JsonObject()
                                .put("alpha", new JsonObject().put("host", "a.example"))
                                .put("beta", new JsonObject().put("host", "b.example")));

        AllCapabilities parsed = parser.parse(section, AllCapabilities.class);

        // Jdk8Module
        assertEquals(Optional.of("present"), parsed.maybe(), "Jdk8Module: Optional must bind");
        // JavaTimeModule
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), parsed.instant(), "JavaTimeModule: Instant must parse");
        assertEquals(LocalDate.parse("2026-06-25"), parsed.date(), "JavaTimeModule: LocalDate must parse");
        // VertxModule
        assertFalse(parsed.json().isEmpty(), "VertxModule: JsonObject must not bind to an empty object");
        assertEquals(new JsonObject().put("k", "v"), parsed.json(), "VertxModule: JsonObject value must survive");
        // lenient scalar coercion
        assertEquals(8080, parsed.port(), "lenient coercion: string \"8080\" must coerce to int");
        // KeyedCollectionModule (@KeyedBy injection)
        assertEquals(2, parsed.servers().size(), "KeyedCollectionModule: both entries must parse");
        assertTrue(
                parsed.servers().stream()
                        .anyMatch(s -> s.name().equals("alpha") && s.host().equals("a.example")),
                "KeyedCollectionModule: entry key 'alpha' must inject into Server.name");
        assertTrue(
                parsed.servers().stream()
                        .anyMatch(s -> s.name().equals("beta") && s.host().equals("b.example")),
                "KeyedCollectionModule: entry key 'beta' must inject into Server.name");
        // FAIL_ON_UNKNOWN_PROPERTIES disabled — proven by the parse succeeding despite "unknownExtra".
    }

    @Test
    @DisplayName("Vert.x JsonArray field also round-trips on the default mapper")
    void defaultMapperRoundTripsJsonArray() {
        JsonObject section = new JsonObject()
                .put("maybe", "x")
                .put("instant", "2026-01-02T03:04:05Z")
                .put("date", "2026-06-25")
                .put(
                        "json",
                        new JsonObject().put("values", new JsonArray().add("a").add(2)))
                .put("port", 1)
                .put("servers", new JsonObject());

        AllCapabilities parsed = parser.parse(section, AllCapabilities.class);

        assertEquals(
                new JsonArray().add("a").add(2),
                parsed.json().getJsonArray("values"),
                "VertxModule: nested JsonArray must survive non-empty");
    }
}
