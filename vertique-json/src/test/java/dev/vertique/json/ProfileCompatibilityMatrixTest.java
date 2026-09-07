// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TP-002 — pins the compatibility matrix (contracts/json-default-profile.md, "Compatibility
 * matrix") across raw Vert.x ({@link DatabindCodec#mapper()}), the reserved {@code system} profile,
 * and the reserved {@code vertique} profile, both obtained from a {@link
 * DefaultJsonMapperProfileRegistry} seeded with no application profiles.
 *
 * <p>{@code system} is byte-identical to raw for every previously-supported type ({@link
 * JsonObject}, {@link JsonArray}, {@link Buffer}, {@code byte[]}, {@link Instant}, a POJO carrying
 * nulls and numbers) except the recorded {@link Date}/{@code Calendar} delta (epoch millis under
 * raw, ISO-8601 under {@code system}); {@code Optional} and non-{@code Instant} {@code java.time}
 * types go from throwing under raw to working under both {@code system} and {@code vertique}; JSON
 * comments are accepted by raw and {@code system} (inherited leniency) and rejected by {@code
 * vertique} (fresh-seeded, no comment leniency).
 *
 * <p>Sensitivity (T010 evidence § L00): registering {@code JavaTimeModule} in {@code
 * applyOpinionated} instead of {@code applySystem} flips {@link
 * #javaTimeOtherThanInstantFailsUnderRawWorksUnderSystemAndVertique()}'s {@code system} row; seeding
 * {@code vertique} from {@code system}'s copy instead of a fresh {@code ObjectMapper} flips {@link
 * #commentsAcceptedByRawAndSystemRejectedByVertique()}.
 */
@DisplayName("Profile compatibility matrix: raw vs system vs vertique")
class ProfileCompatibilityMatrixTest {

    private ObjectMapper raw;
    private ObjectMapper system;
    private ObjectMapper vertique;

    @BeforeEach
    void setUp() {
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());
        raw = DatabindCodec.mapper();
        system = registry.mapper(JsonProfileId.SYSTEM);
        vertique = registry.mapper(JsonProfileId.of("vertique"));
    }

    // --- Fixtures ---

    record BytesCarrier(byte[] b) {}

    record BufferCarrier(Buffer b) {}

    record InstantCarrier(Instant t) {}

    /** A POJO carrying a null-valued field and a numeric field, per TP-002's Given. */
    record PojoWithNullsAndNumbers(String name, Integer count, String nullable) {}

    record OptionalCarrier(Optional<String> v) {}

    record LocalDateCarrier(LocalDate d) {}

    record LegacyDateCarrier(Date d) {}

    @Test
    @DisplayName(
            "system is byte-identical to raw for JsonObject, JsonArray, Buffer, byte[], Instant, and a POJO with nulls and numbers")
    void systemIsByteIdenticalToRawForSupportedTypes() throws Exception {
        // JsonObject (structural equality after decode; the null-valued property must survive on
        // BOTH raw and system, since neither carries an inclusion opinion).
        JsonObject jsonObjectSample =
                new JsonObject().put("string", "value").put("number", 42).putNull("nullField");
        String rawJsonObject = raw.writeValueAsString(jsonObjectSample);
        String systemJsonObject = system.writeValueAsString(jsonObjectSample);
        assertEquals(rawJsonObject, systemJsonObject, "system must serialize a JsonObject byte-identically to raw");
        assertEquals(
                jsonObjectSample,
                system.readValue(systemJsonObject, JsonObject.class),
                "system must decode a JsonObject back to the same structure");

        // JsonArray.
        JsonArray jsonArraySample = new JsonArray().add("scalar").add(7).addNull();
        String rawJsonArray = raw.writeValueAsString(jsonArraySample);
        String systemJsonArray = system.writeValueAsString(jsonArraySample);
        assertEquals(rawJsonArray, systemJsonArray, "system must serialize a JsonArray byte-identically to raw");
        assertEquals(
                jsonArraySample,
                system.readValue(systemJsonArray, JsonArray.class),
                "system must decode a JsonArray back to the same structure");

        // Buffer.
        Buffer bufferSample = Buffer.buffer("hello, system");
        String rawBuffer = raw.writeValueAsString(new BufferCarrier(bufferSample));
        String systemBuffer = system.writeValueAsString(new BufferCarrier(bufferSample));
        assertEquals(rawBuffer, systemBuffer, "system must serialize a Buffer byte-identically to raw");

        // byte[].
        byte[] bytesSample = {1, 2, 3, 4};
        String rawBytes = raw.writeValueAsString(new BytesCarrier(bytesSample));
        String systemBytes = system.writeValueAsString(new BytesCarrier(bytesSample));
        assertEquals(rawBytes, systemBytes, "system must serialize a byte[] byte-identically to raw");

        // Instant — byte-identical under system AND vertique (NFR-JSON-012's guarantee extended to
        // system).
        Instant instantSample = Instant.parse("2024-01-15T08:30:00.123456789Z");
        String rawInstant = raw.writeValueAsString(new InstantCarrier(instantSample));
        String systemInstant = system.writeValueAsString(new InstantCarrier(instantSample));
        String vertiqueInstant = vertique.writeValueAsString(new InstantCarrier(instantSample));
        assertEquals(rawInstant, systemInstant, "system must serialize an Instant byte-identically to raw");
        assertEquals(rawInstant, vertiqueInstant, "vertique must serialize an Instant byte-identically to raw");

        // POJO carrying a null-valued field and a numeric field: system must keep the null (no
        // inclusion opinion), exactly like raw.
        PojoWithNullsAndNumbers pojoSample = new PojoWithNullsAndNumbers("acme", 42, null);
        String rawPojo = raw.writeValueAsString(pojoSample);
        String systemPojo = system.writeValueAsString(pojoSample);
        assertEquals(
                rawPojo,
                systemPojo,
                "system must serialize a POJO with a null field byte-identically to raw (no NON_NULL opinion)");
        assertTrue(rawPojo.contains("\"nullable\":null"), "the raw output must retain the null field: " + rawPojo);
    }

    @Test
    @DisplayName("java.util.Date renders as epoch millis under raw and ISO-8601 under system and vertique")
    void legacyDateRendersIsoUnderSystem() throws Exception {
        Date legacyDate = new Date(0L);

        String rawJson = raw.writeValueAsString(new LegacyDateCarrier(legacyDate));
        String systemJson = system.writeValueAsString(new LegacyDateCarrier(legacyDate));
        String vertiqueJson = vertique.writeValueAsString(new LegacyDateCarrier(legacyDate));

        assertEquals("{\"d\":0}", rawJson, "raw must render java.util.Date as epoch millis: " + rawJson);
        assertEquals(
                "{\"d\":\"1970-01-01T00:00:00.000+00:00\"}",
                systemJson,
                "system must render java.util.Date as an ISO-8601 string (the recorded delta from raw)");
        assertEquals(
                "{\"d\":\"1970-01-01T00:00:00.000+00:00\"}",
                vertiqueJson,
                "vertique must render java.util.Date as an ISO-8601 string, as it does today");
    }

    @Test
    @DisplayName("JSON comments are accepted by raw and system, and rejected by vertique")
    void commentsAcceptedByRawAndSystemRejectedByVertique() throws Exception {
        String commented = "/* c */{}";

        Object rawResult = raw.readValue(commented, Object.class);
        Object systemResult = system.readValue(commented, Object.class);
        assertEquals(Map.of(), rawResult, "raw must accept and parse a JSON-with-comments payload");
        assertEquals(Map.of(), systemResult, "system must inherit raw's ALLOW_COMMENTS leniency (copy() preserves it)");

        assertThrows(
                JsonProcessingException.class,
                () -> vertique.readValue(commented, Object.class),
                "vertique must reject JSON comments (fresh-seeded ObjectMapper, no comment leniency), as today");
    }

    @Test
    @DisplayName("Optional<String> fails under raw and works under system and vertique")
    void optionalWorksUnderSystemAndVertiqueButFailsUnderRaw() throws Exception {
        OptionalCarrier present = new OptionalCarrier(Optional.of("zed"));

        assertThrows(
                InvalidDefinitionException.class,
                () -> raw.writeValueAsString(present),
                "raw must fail to serialize Optional<String> (REQUIRE_HANDLERS_FOR_JAVA8_OPTIONALS, no Jdk8Module)");

        String systemJson = system.writeValueAsString(present);
        String vertiqueJson = vertique.writeValueAsString(present);
        assertEquals("{\"v\":\"zed\"}", systemJson, "system must serialize a present Optional<String> as its value");
        OptionalCarrier systemRoundTrip = system.readValue(systemJson, OptionalCarrier.class);
        assertEquals(Optional.of("zed"), systemRoundTrip.v(), "system must deserialize Optional<String> correctly");
        assertTrue(vertiqueJson.contains("zed"), "vertique must serialize a present Optional<String>: " + vertiqueJson);
    }

    @Test
    @DisplayName("java.time other than Instant fails under raw and renders ISO-8601 under system and vertique")
    void javaTimeOtherThanInstantFailsUnderRawWorksUnderSystemAndVertique() throws Exception {
        LocalDate localDate = LocalDate.of(2024, 1, 15);

        assertThrows(
                InvalidDefinitionException.class,
                () -> raw.writeValueAsString(new LocalDateCarrier(localDate)),
                "raw must fail to serialize LocalDate (REQUIRE_HANDLERS_FOR_JAVA8_TIMES, no JavaTimeModule)");

        assertEquals(
                "{\"d\":\"2024-01-15\"}",
                system.writeValueAsString(new LocalDateCarrier(localDate)),
                "system must render LocalDate as an ISO-8601 date string");
        assertEquals(
                "{\"d\":\"2024-01-15\"}",
                vertique.writeValueAsString(new LocalDateCarrier(localDate)),
                "vertique must render LocalDate as an ISO-8601 date string");
    }
}
