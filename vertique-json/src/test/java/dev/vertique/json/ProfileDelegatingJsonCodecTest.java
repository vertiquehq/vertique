// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import dev.vertique.core.json.JsonProfileCodec;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.core.json.jackson.VertxModule;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Proves the Vertique process JSON codec and the absence of the retired customizer
 * mechanism (structural check).
 *
 * <p>Verified here:
 *
 * <ul>
 *   <li><b>SPI selection</b> — {@code Json.CODEC} <em>is</em> {@link ProfileDelegatingJsonCodec#INSTANCE}
 *       (the {@code META-INF/services/io.vertx.core.spi.JsonFactory} registration was loaded), and
 *       {@link VertiqueJsonFactory#order()} is the frozen {@code -1000} so any competing factory
 *       registered by an application loses.</li>
 *   <li><b>Pre-install delegate</b> — {@code Jackson2JsonProfileCodec.objectMapper(VertiqueJson.codec())} is {@code DatabindCodec.mapper()}
 *       and {@code Json.encode} is byte-identical to that mapper's output, so a JVM that never runs
 *       the install step behaves exactly like stock Vert.x.</li>
 *   <li><b>Delegation</b> — {@code Json.encode}, {@code Json.decodeValue}, {@code JsonObject.mapTo}
 *       and {@code JsonObject.mapFrom} all run on the <em>installed</em> mapper (an installed
 *       {@code NON_NULL} mapper drops a null property; an installed lenient mapper accepts an
 *       unknown property that the previous installation rejected).</li>
 *   <li><b>Id-keyed install</b> — a second install under the same id swaps the delegate instance and
 *       logs the swap at INFO with the profile id and the calling class; a different id is refused
 *       with an {@link IllegalStateException} naming both ids and both callers.</li>
 *   <li><b>Invariant guards on the seam itself</b> — installing a mapper without {@code VertxModule}
 *       or with Jackson default typing active throws {@link IllegalArgumentException} and leaves the
 *       current installation untouched.</li>
 *   <li><b>Reset</b> — {@link VertiqueJson#resetForTests()} restores the raw delegate (this module's
 *       surefire {@code argLine} sets {@code -Dvertique.json.codec.allowReset=true}; the flagless
 *       half of the contract is {@code ProfileDelegatingJsonCodecResetGateTest}).</li>
 *   <li><b>Retired mechanism</b> — {@code ObjectMapperCustomizer}, {@code JsonModule},
 *       {@code JacksonConfigurer} and {@code JacksonConfigureStep} are not on the classpath.</li>
 * </ul>
 *
 * <p>Log assertions attach a {@link ListAppender} to the <em>root</em> logger rather than to a
 * named one, so the proof pins the emitted text and level, not the implementation's logger name.
 */
class ProfileDelegatingJsonCodecTest {

    /** Fixture with one always-present and one null property — the inclusion probe. */
    record Fixture(String present, String absent) {}

    private static final JsonProfileId PROFILE_A = JsonProfileId.of("codec-test-a");
    private static final JsonProfileId PROFILE_B = JsonProfileId.of("codec-test-b");

    /** Restores the raw Vert.x delegate; the surefire fork is shared with every other test class. */
    @AfterEach
    void resetProcessCodec() {
        VertiqueJson.resetForTests();
    }

    @Test
    @DisplayName("the codec delegates to the installed mapper and install is keyed by profile id")
    void delegatesToInstalledMapperAndKeysInstallByProfileId() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            // --- Given: the SPI selected the Vertique codec, at the frozen order ---
            assertSame(
                    ProfileDelegatingJsonCodec.INSTANCE,
                    Json.CODEC,
                    "Vert.x must have loaded VertiqueJsonFactory through META-INF/services; "
                            + "without it every delegation assertion below is vacuous");
            assertTrue(VertiqueJson.ownsCodec(), "ownsCodec() must report the SPI selection");
            assertEquals(
                    -1000,
                    new VertiqueJsonFactory().order(),
                    "the factory must keep its frozen order so it wins over any application factory");

            // --- Pre-install: the raw Vert.x mapper, byte-identical output ---
            JsonObject sample = new JsonObject().put("a", 1).put("b", "two").putNull("c");
            assertSame(
                    DatabindCodec.mapper(),
                    Jackson2JsonProfileCodec.objectMapper(VertiqueJson.codec()),
                    "before installation the delegate must be the raw Vert.x mapper");
            assertEquals(
                    DatabindCodec.mapper().writeValueAsString(sample),
                    Json.encode(sample),
                    "pre-install encoding must be byte-identical to DatabindCodec.mapper()");
            assertTrue(VertiqueJson.profile().isEmpty(), "no profile is installed before the install step");
            assertEquals(
                    "{\"present\":\"x\",\"absent\":null}",
                    Json.encode(new Fixture("x", null)),
                    "the raw Vert.x mapper emits null properties");

            // --- When: install profile A (NON_NULL, strict about unknown properties) ---
            ObjectMapper a1 = nonNullMapper();
            VertiqueJson.install(Jackson2JsonProfiles.of(PROFILE_A, a1));

            assertSame(
                    a1,
                    Jackson2JsonProfileCodec.objectMapper(VertiqueJson.codec()),
                    "the delegate must be the installed instance");
            assertEquals(PROFILE_A, VertiqueJson.profile().orElse(null), "profile() must report the installed id");
            assertEquals(
                    "{\"present\":\"x\"}",
                    Json.encode(new Fixture("x", null)),
                    "Json.encode must run on the installed NON_NULL mapper");
            assertEquals(
                    "{\"present\":\"x\"}",
                    JsonObject.mapFrom(new Fixture("x", null)).encode(),
                    "JsonObject.mapFrom must run on the installed mapper");
            assertEquals(
                    "{}",
                    new JsonObject().putNull("k").encode(),
                    "an explicit JsonObject null is omitted at encode() when the installed mapper is NON_NULL");
            assertThrows(
                    RuntimeException.class,
                    () -> Json.decodeValue("{\"present\":\"x\",\"extra\":1}", Fixture.class),
                    "Json.decodeValue must run on the installed (strict) mapper");
            assertThrows(
                    RuntimeException.class,
                    () -> new JsonObject().put("present", "x").put("extra", 1).mapTo(Fixture.class),
                    "JsonObject.mapTo must run on the installed (strict) mapper");

            // --- When: install the same id with a different, lenient instance (same-id swap) ---
            appender.list.clear();
            ObjectMapper a2 = nonNullMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            VertiqueJson.install(Jackson2JsonProfiles.of(PROFILE_A, a2));

            assertSame(
                    a2,
                    Jackson2JsonProfileCodec.objectMapper(VertiqueJson.codec()),
                    "a same-id install must swap the delegate to the new instance");
            assertEquals(PROFILE_A, VertiqueJson.profile().orElse(null), "the installed id is unchanged by a swap");
            assertEquals(
                    "{\"present\":\"x\"}",
                    Json.encode(new Fixture("x", null)),
                    "the swapped instance keeps the NON_NULL behaviour");
            assertEquals(
                    new Fixture("x", null),
                    Json.decodeValue("{\"present\":\"x\",\"extra\":1}", Fixture.class),
                    "decoding must now run on the swapped, lenient instance");
            assertEquals(
                    new Fixture("x", null),
                    new JsonObject().put("present", "x").put("extra", 1).mapTo(Fixture.class),
                    "mapTo must now run on the swapped, lenient instance");

            String swapLog = infoLogContaining(appender, PROFILE_A.value());
            assertNotNull(swapLog, "a same-id swap must be logged at INFO naming the profile id");
            assertTrue(
                    swapLog.contains(getClass().getSimpleName()),
                    "the swap log must attribute the installing caller; got: " + swapLog);

            // --- When: install a different id ---
            ObjectMapper b = vertxAwareMapper();
            IllegalStateException conflict = assertThrows(
                    IllegalStateException.class, () -> VertiqueJson.install(Jackson2JsonProfiles.of(PROFILE_B, b)));
            String conflictMessage = String.valueOf(conflict.getMessage());
            assertTrue(
                    conflictMessage.contains(PROFILE_A.value()) && conflictMessage.contains(PROFILE_B.value()),
                    "the refusal must name both profile ids; got: " + conflictMessage);
            assertTrue(
                    conflictMessage.contains(getClass().getSimpleName()),
                    "the refusal must name both installing classes; got: " + conflictMessage);
            assertSame(
                    a2,
                    Jackson2JsonProfileCodec.objectMapper(VertiqueJson.codec()),
                    "a refused install must not change the delegate");

            // --- When: install an unsafe mapper under the current id ---
            ObjectMapper typed = vertxAwareMapper().activateDefaultTyping(LaissezFaireSubTypeValidator.instance);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> VertiqueJson.install(Jackson2JsonProfiles.of(PROFILE_A, typed)),
                    "the install seam must reject a mapper with Jackson default typing active");
            ObjectMapper noModule = new ObjectMapper();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> VertiqueJson.install(Jackson2JsonProfiles.of(PROFILE_A, noModule)),
                    "the install seam must reject a mapper without VertxModule registered");
            assertSame(
                    a2,
                    Jackson2JsonProfileCodec.objectMapper(VertiqueJson.codec()),
                    "a rejected install must leave the current delegate in place");

            // --- Then: reset restores the raw Vert.x delegate ---
            VertiqueJson.resetForTests();

            assertSame(
                    DatabindCodec.mapper(),
                    Jackson2JsonProfileCodec.objectMapper(VertiqueJson.codec()),
                    "reset must restore the raw Vert.x mapper");
            assertTrue(VertiqueJson.profile().isEmpty(), "reset must clear the installation");
            assertEquals(
                    "{\"present\":\"x\",\"absent\":null}",
                    Json.encode(new Fixture("x", null)),
                    "after reset the raw mapper emits null properties again");
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    @DisplayName(
            "a same-id swap to a lenient mapper logs a WARN naming the deltas; an equally" + " strict swap logs none")
    void sameIdSwapToALenientMapperLogsTheDeltas() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            JsonProfileId profile = JsonProfileId.of("codec-test-leniency");

            // --- Given: a strict mapper installed under the profile id ---
            ObjectMapper strict = vertxAwareMapper();
            VertiqueJson.install(Jackson2JsonProfiles.of(profile, strict));

            // --- When: the same id swaps to an equally strict mapper ---
            appender.list.clear();
            ObjectMapper equallyStrict = vertxAwareMapper();
            VertiqueJson.install(Jackson2JsonProfiles.of(profile, equallyStrict));

            assertNotNull(
                    infoLogContaining(appender, profile.value()),
                    "a same-id swap must still log the INFO swap line even when leniency is unchanged");
            assertNull(
                    warnLogContaining(appender, profile.value()),
                    "a same-id swap to an equally strict mapper must not log a leniency WARN");

            // --- When: the same id swaps to a mapper with weaker read leniency ---
            appender.list.clear();
            ObjectMapper lenient = vertxAwareMapper()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
            VertiqueJson.install(Jackson2JsonProfiles.of(profile, lenient));

            String warnLog = warnLogContaining(appender, profile.value());
            assertNotNull(warnLog, "a same-id swap to a lenient mapper must log a WARN naming the deltas");
            assertTrue(
                    warnLog.contains(getClass().getSimpleName()),
                    "the WARN must attribute the installing caller; got: " + warnLog);
            assertTrue(
                    warnLog.contains("FAIL_ON_UNKNOWN_PROPERTIES"),
                    "the WARN must name FAIL_ON_UNKNOWN_PROPERTIES; got: " + warnLog);
            assertTrue(
                    warnLog.contains("ACCEPT_CASE_INSENSITIVE_PROPERTIES"),
                    "the WARN must name ACCEPT_CASE_INSENSITIVE_PROPERTIES; got: " + warnLog);
        } finally {
            root.detachAppender(appender);
        }
    }

    // --- install(JsonProfileCodec) ---

    @Test
    @DisplayName("installing a direct profile codec that is not a Jackson 2 codec is refused naming the profile id")
    void installingADirectProfileCodecIsRefused() {
        // Given: nothing installed, and a direct codec whose unwrap yields a valid Vert.x-aware mapper
        assertTrue(VertiqueJson.profile().isEmpty(), "precondition: nothing installed");
        DirectCodec direct = new DirectCodec(PROFILE_A, vertxAwareMapper());

        // When / Then: the install is refused with the profile id, and nothing is installed
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> VertiqueJson.install(direct));
        assertTrue(
                String.valueOf(refused.getMessage()).contains(PROFILE_A.value()),
                "the refusal must name the profile id; got: " + refused.getMessage());
        assertTrue(VertiqueJson.profile().isEmpty(), "a refused install must leave nothing installed");
    }

    @Test
    @DisplayName("a failed install leaves profile() reporting the previously installed id")
    void aFailedInstallKeepsThePreviousProfile() {
        // Given: profile A installed through the mapper seam
        VertiqueJson.install(Jackson2JsonProfiles.of(PROFILE_A, vertxAwareMapper()));

        // When: a guard failure (same id) and an id conflict (other id) are attempted
        assertThrows(
                IllegalArgumentException.class,
                () -> VertiqueJson.install(new DirectCodec(PROFILE_A, vertxAwareMapper())));
        IllegalStateException conflict = assertThrows(
                IllegalStateException.class,
                () -> VertiqueJson.install(Jackson2JsonProfiles.of(PROFILE_B, vertxAwareMapper())));

        // Then: both are refused for a contract reason, the conflict names both ids, and A stays installed
        assertTrue(
                String.valueOf(conflict.getMessage()).contains(PROFILE_A.value())
                        && String.valueOf(conflict.getMessage()).contains(PROFILE_B.value()),
                "the conflict must name both profile ids; got: " + conflict.getMessage());
        assertEquals(PROFILE_A, VertiqueJson.profile().orElse(null), "profile() must still report the earlier id");
    }

    @Test
    @DisplayName("the guards run before the id-conflict check: an unsafe mapper under another id fails its guard")
    void guardsRunBeforeTheConflictCheck() {
        VertiqueJson.install(Jackson2JsonProfiles.of(PROFILE_A, vertxAwareMapper()));
        ObjectMapper noVertxModule = new ObjectMapper();
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> VertiqueJson.install(Jackson2JsonProfiles.of(PROFILE_B, noVertxModule)),
                "a guard failure must be reported before the id conflict, so the remedy names the real problem");
        assertTrue(refused.getMessage().contains(PROFILE_B.value()), refused.getMessage());
        assertEquals(Optional.of(PROFILE_A), VertiqueJson.profile(), "a refused install must change nothing");
    }

    @Test
    @DisplayName(
            "a profile whose mapper() differs per call is served from the first instance as soon as install returns")
    void installServesTheInstanceTheGuardsSaw() {
        // Given: a Jackson 2 profile whose mapper() hands out a new instance per call; only the first is NON_NULL
        List<ObjectMapper> handedOut = new java.util.ArrayList<>();
        Jackson2JsonProfileCodec profile = new Jackson2JsonProfileCodec() {
            @Override
            public JsonProfileId id() {
                return PROFILE_A;
            }

            @Override
            protected ObjectMapper mapper() {
                ObjectMapper next = handedOut.isEmpty() ? nonNullMapper() : vertxAwareMapper();
                handedOut.add(next);
                return next;
            }
        };

        // When: it is installed
        VertiqueJson.install(profile);

        // Then: every observation is the first instance, and the codec serves the profile immediately
        ObjectMapper first = handedOut.get(0);
        assertSame(profile, VertiqueJson.codec(), "codec() must return the installed profile right after install");
        assertSame(first, profile.unwrap(ObjectMapper.class).orElseThrow(), "unwrap must return the guarded instance");
        assertSame(
                first,
                Jackson2JsonProfileCodec.objectMapper(VertiqueJson.codec()),
                "objectMapper(codec()) must return the guarded instance");
        assertEquals(
                "{\"present\":\"x\"}",
                Json.encode(new Fixture("x", null)),
                "Json.encode must run on the guarded (NON_NULL) first instance, not a later one");
    }

    /** A {@link JsonProfileCodec} implemented directly, so it is not a Jackson 2 profile codec. */
    private record DirectCodec(JsonProfileId id, ObjectMapper mapper) implements JsonProfileCodec {

        @Override
        public Buffer encode(Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String encodeToString(Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void encodeTo(Object value, OutputStream out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T decode(Buffer json, Type type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T decode(String json, Type type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T convert(Object value, Type type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Optional<T> unwrap(Class<T> type) {
            return type == ObjectMapper.class ? Optional.of(type.cast(mapper)) : Optional.empty();
        }
    }

    // --- retired customizer mechanism (structural check) ---

    @Test
    @DisplayName("the retired customizer types are absent from the classpath")
    void customizerTypesAreAbsent() {
        List<String> retired = List.of(
                "dev.vertique.core.json.ObjectMapperCustomizer",
                "dev.vertique.core.json.JsonModule",
                "dev.vertique.core.json.JacksonConfigurer",
                "dev.vertique.core.lifecycle.JacksonConfigureStep");

        for (String name : retired) {
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName(name),
                    name + " belongs to the retired customizer mechanism and must not exist");
        }
    }

    // --- Helpers ---

    /**
     * Returns a mapper that registers Vert.x's {@code VertxModule} (so it satisfies the install
     * seam's first invariant) and otherwise keeps Jackson defaults.
     *
     * @return a fresh, install-eligible mapper
     */
    private static ObjectMapper vertxAwareMapper() {
        return new ObjectMapper().registerModule(new VertxModule());
    }

    /**
     * Returns an install-eligible mapper that omits null properties — the inclusion delta the
     * delegation assertions observe through {@code Json.encode}.
     *
     * @return a fresh, install-eligible {@code NON_NULL} mapper
     */
    private static ObjectMapper nonNullMapper() {
        return vertxAwareMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Returns the first captured INFO message containing {@code needle}, or {@code null}.
     *
     * @param appender the attached capture appender
     * @param needle the required substring
     * @return the matching formatted message, or {@code null} when none matched
     */
    private static String infoLogContaining(ListAppender<ILoggingEvent> appender, String needle) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(needle))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the first captured WARN message containing {@code needle}, or {@code null}.
     *
     * @param appender the attached capture appender
     * @param needle the required substring
     * @return the matching formatted message, or {@code null} when none matched
     */
    private static String warnLogContaining(ListAppender<ILoggingEvent> appender, String needle) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(needle))
                .findFirst()
                .orElse(null);
    }
}
