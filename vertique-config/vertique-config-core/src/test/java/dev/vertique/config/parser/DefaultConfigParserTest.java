// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the relocated config parser implementation ({@link DefaultConfigParser} over
 * {@link DefaultConfigMapper#lenient()}) preserves every behavior the static {@code ConfigParser}
 * facade had:
 *
 * <ul>
 *   <li><strong>default parity</strong> — the lenient default mapper round-trips Vert.x JSON types,
 *       keyed collections, JDK8 and JSR310 types exactly as before, and keeps an open
 *       {@link JsonObject}/{@link JsonArray} bag non-empty (the data-loss guard);
 *   <li><strong>lenient coercion</strong> — string-encoded scalars coerce and unknown properties are
 *       tolerated, isolated from any separate strict mapper in the same process;
 *   <li><strong>SEC-1 non-leakage</strong> — parse failures never embed the offending config value,
 *       while a framework {@link ConfigurationException} validation message (value-free by
 *       convention) is surfaced as-is;
 *   <li><strong>cause-chain cycle safety</strong> — {@link DefaultConfigParser#findConfigCause} does
 *       not loop on cyclic cause chains.
 * </ul>
 */
class DefaultConfigParserTest {

    /** The parser under test, built over the lenient default config mapper. */
    private final ConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());

    /**
     * A record with a single recognized field, used to probe unknown-property handling.
     *
     * @param weight the only recognized field
     */
    record Rec(int weight) {}

    /**
     * A record carrying an open {@link JsonObject} property bag, mirroring downstream config records
     * (Kafka {@code properties}, cron {@code parameters}).
     *
     * @param name an identity field
     * @param properties an open JSON object property bag
     */
    record Bag(String name, JsonObject properties) {}

    /**
     * A record carrying a {@link JsonArray} field, to confirm array containers round-trip too.
     *
     * @param name an identity field
     * @param values an open JSON array
     */
    record ArrayBag(String name, JsonArray values) {}

    /**
     * A record exercising every config module: a Vert.x {@link JsonObject} and {@link JsonArray}
     * ({@code VertxModule}), a JDK8 {@link Optional} ({@code Jdk8Module}), and JSR310
     * {@link Instant}/{@link LocalDate} ({@code JavaTimeModule}).
     *
     * @param json a Vert.x JSON object field
     * @param values a Vert.x JSON array field
     * @param maybe a JDK8 optional field
     * @param instant a JSR310 instant field
     * @param date a JSR310 local-date field
     */
    record AllModules(JsonObject json, JsonArray values, Optional<String> maybe, Instant instant, LocalDate date) {}

    /**
     * A config record whose compact constructor validates and throws a framework
     * {@link ConfigurationException} with a value-free, path-bearing message.
     *
     * @param size the validated field; must be {@code > 0}
     */
    record Bounded(int size) {
        Bounded {
            if (size <= 0) {
                throw new ConfigurationException("some.config.path.size must be > 0");
            }
        }
    }

    @Nested
    @DisplayName("default parity — lenient default mapper round-trips all module types")
    class DefaultParity {

        @Test
        @DisplayName("parse() round-trips Vert.x JSON, JDK8 and JSR310 as before")
        void parseRoundTripsAllModuleTypes() {
            JsonObject section = new JsonObject()
                    .put("json", new JsonObject().put("k", "v"))
                    .put("values", new JsonArray().add("a").add(2))
                    .put("maybe", "present")
                    .put("instant", "2026-01-02T03:04:05Z")
                    .put("date", "2026-06-25");

            AllModules parsed = parser.parse(section, AllModules.class);

            assertEquals(new JsonObject().put("k", "v"), parsed.json(), "VertxModule: JsonObject must survive");
            assertEquals(new JsonArray().add("a").add(2), parsed.values(), "VertxModule: JsonArray must survive");
            assertEquals(Optional.of("present"), parsed.maybe(), "Jdk8Module: Optional must survive");
            assertEquals(Instant.parse("2026-01-02T03:04:05Z"), parsed.instant(), "JavaTimeModule: Instant");
            assertEquals(LocalDate.parse("2026-06-25"), parsed.date(), "JavaTimeModule: LocalDate");
        }

        @Test
        @DisplayName("parse() keeps the open JsonObject bag non-empty (data-loss guard)")
        void parseKeepsJsonBag() {
            JsonObject section = new JsonObject()
                    .put("name", "x")
                    .put("properties", new JsonObject().put("k", "v").put("n", 3));

            Bag parsed = parser.parse(section, Bag.class);

            assertEquals("x", parsed.name());
            assertFalse(parsed.properties().isEmpty(), "JsonObject field must not bind to an empty object");
            assertEquals(new JsonObject().put("k", "v").put("n", 3), parsed.properties());
        }

        @Test
        @DisplayName("JsonArray field round-trips non-empty via parse()")
        void jsonArrayFieldRoundTripsViaParse() {
            JsonObject section = new JsonObject()
                    .put("name", "x")
                    .put("values", new JsonArray().add("a").add(2));

            ArrayBag parsed = parser.parse(section, ArrayBag.class);

            assertEquals("x", parsed.name());
            assertFalse(parsed.values().isEmpty(), "JsonArray field must not bind to an empty array");
            assertEquals(new JsonArray().add("a").add(2), parsed.values());
        }

        @Test
        @DisplayName("parseKeyedObject() injects the key and survives the nested JsonObject bag")
        void parseKeyedObjectInjectsKeyAndSurvivesBag() {
            JsonObject section =
                    new JsonObject().put("a", new JsonObject().put("properties", new JsonObject().put("k", "v")));

            List<Bag> parsed = parser.parseKeyedObject(section, "name", Bag.class);

            assertEquals(1, parsed.size());
            assertEquals("a", parsed.get(0).name(), "the entry key is injected into the identity field");
            assertFalse(parsed.get(0).properties().isEmpty(), "the nested JsonObject bag must survive key-injection");
            assertEquals(new JsonObject().put("k", "v"), parsed.get(0).properties());
        }
    }

    @Nested
    @DisplayName("lenient coercion — isolated from any strict mapper")
    class LenientCoercion {

        /**
         * A record with int, long, and boolean fields to exercise scalar coercion.
         *
         * @param weight an int field
         * @param size a long field
         * @param enabled a boolean field
         */
        record Scalars(int weight, long size, boolean enabled) {}

        @Test
        @DisplayName("string-encoded scalars coerce to their target numeric/boolean types")
        void stringEncodedScalarsCoerce() {
            JsonObject section =
                    new JsonObject().put("weight", "5").put("size", "5000").put("enabled", "true");
            Scalars parsed = parser.parse(section, Scalars.class);
            assertEquals(5, parsed.weight());
            assertEquals(5000L, parsed.size());
            assertTrue(parsed.enabled());
        }

        @Test
        @DisplayName("unknown JSON properties are ignored rather than failing the parse")
        void unknownPropertiesIgnored() {
            JsonObject section = new JsonObject().put("weight", 5).put("extra", "ignored");
            Rec parsed = parser.parse(section, Rec.class);
            assertEquals(5, parsed.weight());
        }

        @Test
        @DisplayName("config mapper coercion is isolated from a separate strict mapper's configuration")
        void isolatedFromStrictMapper() throws Exception {
            JsonObject section = new JsonObject().put("weight", "5").put("extra", "x");

            ObjectMapper strict = JsonMapper.builder()
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();
            assertThrows(Exception.class, () -> strict.readValue(section.encode(), Rec.class));

            Rec parsed = parser.parse(section, Rec.class);
            assertEquals(5, parsed.weight());
        }
    }

    @Nested
    @DisplayName("secret non-leakage — parse failures don't embed the offending config value (SEC-1)")
    class SecretNonLeakage {

        /** A secret-like config value that must never surface in a user-facing exception message. */
        private static final String SECRET = "s3cr3t-token";

        @Test
        @DisplayName("a type-mismatch parse failure carrying a secret value keeps the message value-free")
        void parseFailureMessageDoesNotLeakSecretValue() {
            JsonObject section = new JsonObject().put("weight", new JsonObject().put("nested", SECRET));

            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> parser.parse(section, Rec.class));

            assertNotNull(ex.getMessage());
            assertFalse(
                    ex.getMessage().contains(SECRET),
                    "user-facing message must not embed the secret config value, got: " + ex.getMessage());
            assertTrue(
                    ex.getMessage().contains(Rec.class.getSimpleName()),
                    "message should still identify the target type, got: " + ex.getMessage());
            assertNotNull(ex.getCause(), "the original Jackson exception must be preserved as the cause");
        }

        @Test
        @DisplayName("a framework validation failure surfaces its value-free path-bearing message")
        void frameworkValidationMessageIsSurfaced() {
            JsonObject section = new JsonObject().put("size", 0);

            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> parser.parse(section, Bounded.class));

            assertTrue(
                    ex.getMessage().contains("some.config.path"),
                    "framework validation message (path) must reach getMessage(), got: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("cause-chain cycle safety — findConfigCause does not loop on cyclic cause chains")
    class CauseCycleSafety {

        @Test
        @DisplayName("A→B→A two-cycle with no ConfigurationException returns null without hanging")
        void twoCycleWithNoConfigExceptionReturnsNull() {
            RuntimeException a = new RuntimeException("a");
            RuntimeException b = new RuntimeException("b");
            a.initCause(b);
            b.initCause(a);

            ConfigurationException result = DefaultConfigParser.findConfigCause(a);

            assertNull(result, "no ConfigurationException in the cycle, so null is expected");
        }

        @Test
        @DisplayName("A→B→A two-cycle where A is a ConfigurationException returns A")
        void twoCycleWhereRootIsConfigExceptionReturnsIt() {
            ConfigurationException a = new ConfigurationException("some.path.field must be > 0");
            RuntimeException b = new RuntimeException("b");
            a.initCause(b);
            b.initCause(a);

            ConfigurationException result = DefaultConfigParser.findConfigCause(a);

            assertNotNull(result, "root is a ConfigurationException — it must be returned");
            assertTrue(
                    result.getMessage().contains("some.path.field"), "returned exception carries the expected message");
        }

        @Test
        @DisplayName("null input returns null without exception")
        void nullInputReturnsNull() {
            assertNull(DefaultConfigParser.findConfigCause(null), "null input must yield null");
        }
    }
}
