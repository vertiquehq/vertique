// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

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
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.core.json.jackson.VertxModule;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Proves the Vertique process JSON codec (TP-001) and the absence of the retired customizer
 * mechanism (TP-003, structural half).
 *
 * <p>Verified here:
 *
 * <ul>
 *   <li><b>SPI selection</b> — {@code Json.CODEC} <em>is</em> {@link VertiqueJsonCodec#INSTANCE}
 *       (the {@code META-INF/services/io.vertx.core.spi.JsonFactory} registration was loaded), and
 *       {@link VertiqueJsonFactory#order()} is the frozen {@code -1000} so any competing factory
 *       registered by an application loses.</li>
 *   <li><b>Pre-install delegate</b> — {@link VertiqueJson#mapper()} is {@code DatabindCodec.mapper()}
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
 *       half of the contract is {@code VertiqueJsonCodecResetGateTest}).</li>
 *   <li><b>Retired mechanism</b> — {@code ObjectMapperCustomizer}, {@code JsonModule},
 *       {@code JacksonConfigurer} and {@code JacksonConfigureStep} are not on the classpath.</li>
 * </ul>
 *
 * <p>Log assertions attach a {@link ListAppender} to the <em>root</em> logger rather than to a
 * named one, so the proof pins the emitted text and level, not the implementation's logger name.
 */
class VertiqueJsonCodecTest {

    /** Fixture with one always-present and one null property — the inclusion probe. */
    record Fixture(String present, String absent) {}

    private static final JsonProfileId PROFILE_A = JsonProfileId.of("codec-test-a");
    private static final JsonProfileId PROFILE_B = JsonProfileId.of("codec-test-b");

    /** Restores the raw Vert.x delegate; the surefire fork is shared with every other test class. */
    @AfterEach
    void resetProcessCodec() {
        VertiqueJson.resetForTests();
    }

    // --- TP-001 ---

    @Test
    @DisplayName("TP-001: the codec delegates to the installed mapper and install is keyed by profile id")
    void delegatesToInstalledMapperAndKeysInstallByProfileId() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            // --- Given: the SPI selected the Vertique codec, at the frozen order ---
            assertSame(
                    VertiqueJsonCodec.INSTANCE,
                    Json.CODEC,
                    "Vert.x must have loaded VertiqueJsonFactory through META-INF/services; "
                            + "without it every delegation assertion below is vacuous");
            assertTrue(VertiqueJson.ownsProcessCodec(), "ownsProcessCodec() must report the SPI selection");
            assertEquals(
                    -1000,
                    new VertiqueJsonFactory().order(),
                    "the factory must keep its frozen order so it wins over any application factory");

            // --- Pre-install: the raw Vert.x mapper, byte-identical output ---
            JsonObject sample = new JsonObject().put("a", 1).put("b", "two").putNull("c");
            assertSame(
                    DatabindCodec.mapper(),
                    VertiqueJson.mapper(),
                    "before installation the delegate must be the raw Vert.x mapper");
            assertEquals(
                    DatabindCodec.mapper().writeValueAsString(sample),
                    Json.encode(sample),
                    "pre-install encoding must be byte-identical to DatabindCodec.mapper()");
            assertTrue(VertiqueJson.installedProfile().isEmpty(), "no profile is installed before the install step");
            assertEquals(
                    "{\"present\":\"x\",\"absent\":null}",
                    Json.encode(new Fixture("x", null)),
                    "the raw Vert.x mapper emits null properties");

            // --- When: install profile A (NON_NULL, strict about unknown properties) ---
            ObjectMapper a1 = nonNullMapper();
            VertiqueJson.install(PROFILE_A, a1);

            assertSame(a1, VertiqueJson.mapper(), "the delegate must be the installed instance");
            assertEquals(
                    PROFILE_A,
                    VertiqueJson.installedProfile().orElse(null),
                    "installedProfile() must report the installed id");
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
            VertiqueJson.install(PROFILE_A, a2);

            assertSame(a2, VertiqueJson.mapper(), "a same-id install must swap the delegate to the new instance");
            assertEquals(
                    PROFILE_A, VertiqueJson.installedProfile().orElse(null), "the installed id is unchanged by a swap");
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
            IllegalStateException conflict =
                    assertThrows(IllegalStateException.class, () -> VertiqueJson.install(PROFILE_B, b));
            String conflictMessage = String.valueOf(conflict.getMessage());
            assertTrue(
                    conflictMessage.contains(PROFILE_A.value()) && conflictMessage.contains(PROFILE_B.value()),
                    "the refusal must name both profile ids; got: " + conflictMessage);
            assertTrue(
                    conflictMessage.contains(getClass().getSimpleName()),
                    "the refusal must name both installing classes; got: " + conflictMessage);
            assertSame(a2, VertiqueJson.mapper(), "a refused install must not change the delegate");

            // --- When: install an unsafe mapper under the current id ---
            ObjectMapper typed = vertxAwareMapper().activateDefaultTyping(LaissezFaireSubTypeValidator.instance);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> VertiqueJson.install(PROFILE_A, typed),
                    "the install seam must reject a mapper with Jackson default typing active");
            ObjectMapper noModule = new ObjectMapper();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> VertiqueJson.install(PROFILE_A, noModule),
                    "the install seam must reject a mapper without VertxModule registered");
            assertSame(a2, VertiqueJson.mapper(), "a rejected install must leave the current delegate in place");

            // --- Then: reset restores the raw Vert.x delegate ---
            VertiqueJson.resetForTests();

            assertSame(DatabindCodec.mapper(), VertiqueJson.mapper(), "reset must restore the raw Vert.x mapper");
            assertTrue(VertiqueJson.installedProfile().isEmpty(), "reset must clear the installation");
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
            "security review (T023 L07): a same-id swap to a lenient mapper logs a WARN naming the deltas; an equally"
                    + " strict swap logs none")
    void sameIdSwapToALenientMapperLogsTheDeltas() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            JsonProfileId profile = JsonProfileId.of("codec-test-leniency");

            // --- Given: a strict mapper installed under the profile id ---
            ObjectMapper strict = vertxAwareMapper();
            VertiqueJson.install(profile, strict);

            // --- When: the same id swaps to an equally strict mapper ---
            appender.list.clear();
            ObjectMapper equallyStrict = vertxAwareMapper();
            VertiqueJson.install(profile, equallyStrict);

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
            VertiqueJson.install(profile, lenient);

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

    // --- TP-003 (structural half) ---

    @Test
    @DisplayName("TP-003: the retired customizer types are absent from the classpath")
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
