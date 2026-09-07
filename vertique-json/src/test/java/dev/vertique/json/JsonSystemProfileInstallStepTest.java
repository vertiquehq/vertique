// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit proof of the {@code CONFIGURE} step that installs the process JSON codec's mapper
 * (TP-004, TP-005).
 *
 * <p>TP-004 pins the ownership guard: constructed through its package-private constructor with a
 * {@link BooleanSupplier} that reports a foreign process codec, the step must fail the boot naming
 * the actual {@code Json.CODEC} class and the {@code META-INF/services} remedy, and must install
 * nothing. Its second case builds the step through the {@code @Inject} constructor and proves the
 * production supplier really is {@code VertiqueJson::ownsCodec} — in this fork Vertique owns
 * the codec, so the same step succeeds where the {@code () -> false} seam failed (ruling A7).
 *
 * <p>TP-005 pins the mapper-shape guards. Two of the three profiles it needs ({@code nomodule},
 * {@code typed}) are ones {@link DefaultJsonMapperProfileRegistry} refuses to register at all — its
 * own construction-time guards are a different proof — so these cases resolve through a
 * {@link FakeRegistry}: the seam under test is the step's re-check, and it must fire for <em>any</em>
 * registry implementation an application binds. The retired-{@code vertx} case, by contrast, uses the
 * real registry because the rename rejection is the real registry's message.
 *
 * <p>Log assertions attach a {@link ListAppender} to the <em>root</em> logger, so they pin the
 * emitted text and level rather than the implementation's logger name.
 */
class JsonSystemProfileInstallStepTest {

    /** Restores the raw Vert.x delegate; the surefire fork is shared with every other test class. */
    @AfterEach
    void resetProcessCodec() {
        VertiqueJson.resetForTests();
    }

    // --- TP-004 ---

    @Test
    @DisplayName("TP-004: the step refuses to install when Vertique does not own the process codec")
    void failsWhenVertiqueDoesNotOwnTheProcessCodec() {
        JsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());

        // --- Case 1: the ownership seam reports a foreign codec ---
        JsonSystemProfileInstallStep foreign =
                new JsonSystemProfileInstallStep(JsonConfig.defaults(), registry, () -> false);

        assertEquals(LifecyclePhase.CONFIGURE, foreign.phase(), "the install step must run in CONFIGURE");

        Throwable failure = failureOf(foreign);
        JsonProfileConfigurationException rejected = assertInstanceOf(
                JsonProfileConfigurationException.class,
                failure,
                "a foreign process codec must fail the boot with a JSON profile configuration error");
        String message = String.valueOf(rejected.getMessage());
        assertTrue(
                message.contains(Json.CODEC.getClass().getName()),
                "the failure must name the actual process codec class; got: " + message);
        assertTrue(
                message.contains("META-INF/services"),
                "the failure must name the META-INF/services remedy; got: " + message);
        assertTrue(
                VertiqueJson.profile().isEmpty(),
                "a step that refused the install must leave the process codec uninstalled");

        // --- Case 2 (ruling A7): the @Inject constructor's supplier is the real ownership check ---
        JsonSystemProfileInstallStep injected = new JsonSystemProfileInstallStep(JsonConfig.defaults(), registry);

        assertTrue(VertiqueJson.ownsCodec(), "this fork must own the process codec, otherwise case 2 proves nothing");
        succeed(injected);
        assertEquals(
                JsonProfileId.SYSTEM,
                VertiqueJson.profile().orElse(null),
                "the injected supplier must consult VertiqueJson.ownsCodec(), which is true here, "
                        + "so the very same step installs the system profile");
        assertSame(
                registry.mapper(JsonProfileId.SYSTEM),
                VertiqueJson.mapper(),
                "the step installs the registry's mapper instance for the effective system profile");
    }

    // --- TP-005 ---

    @Test
    @DisplayName("TP-005: the step refuses a system profile without VertxModule, with default typing, or named vertx")
    void refusesUnsafeSystemProfiles() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            ObjectMapper noModule = new ObjectMapper().registerModule(new Jdk8Module());
            ObjectMapper typed = JacksonDefaults.applySystem(new ObjectMapper())
                    .activateDefaultTyping(LaissezFaireSubTypeValidator.instance);
            ObjectMapper lenient = JacksonDefaults.applySystem(new ObjectMapper())
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            FakeRegistry registry = new FakeRegistry()
                    .with("nomodule", noModule)
                    .with("typed", typed)
                    .with("lenient", lenient);

            // --- nomodule: VertxModule is not registered ---
            Throwable noModuleFailure = failureOf(stepFor("nomodule", registry));
            String noModuleMessage = messageOf(noModuleFailure, "nomodule");
            assertTrue(
                    noModuleMessage.contains("nomodule"),
                    "the failure must name the offending profile id; got: " + noModuleMessage);
            assertTrue(
                    noModuleMessage.contains("VertxJsonSupport.module()"),
                    "the failure must name the remedy (VertxJsonSupport.module()); got: " + noModuleMessage);
            assertTrue(VertiqueJson.profile().isEmpty(), "nothing may be installed after a refusal");

            // --- typed: Jackson default typing is active ---
            Throwable typedFailure = failureOf(stepFor("typed", registry));
            String typedMessage = messageOf(typedFailure, "typed");
            assertTrue(
                    typedMessage.contains("typed"),
                    "the failure must name the offending profile id; got: " + typedMessage);
            assertTrue(
                    typedMessage.contains("default typing"),
                    "the failure must name the default-typing rule; got: " + typedMessage);
            assertTrue(VertiqueJson.profile().isEmpty(), "nothing may be installed after a refusal");

            // --- vertx: the retired reserved id, rejected by the real registry ---
            Throwable renamedFailure = failureOf(stepFor("vertx", new DefaultJsonMapperProfileRegistry(Set.of())));
            String renamedMessage = messageOf(renamedFailure, "vertx");
            assertTrue(
                    renamedMessage.contains("renamed") && renamedMessage.contains("system"),
                    "a configured 'vertx' system profile must fail naming the rename; got: " + renamedMessage);
            assertTrue(VertiqueJson.profile().isEmpty(), "nothing may be installed after a refusal");

            // --- lenient: installs, and the security-relevant delta is logged at WARN ---
            appender.list.clear();
            succeed(stepFor("lenient", registry));

            assertEquals(
                    JsonProfileId.of("lenient"),
                    VertiqueJson.profile().orElse(null),
                    "a lenient but structurally safe profile installs");
            assertSame(lenient, VertiqueJson.mapper(), "the installed delegate is the profile's mapper instance");
            String warning = logContaining(appender, Level.WARN, "FAIL_ON_UNKNOWN_PROPERTIES");
            assertNotNull(
                    warning,
                    "installing a profile with FAIL_ON_UNKNOWN_PROPERTIES off must be logged at WARN naming the"
                            + " feature; captured: " + appender.list);

            // --- the core seam is the backstop for a direct, post-install caller ---
            VertiqueJson.resetForTests();
            JsonMapperProfileRegistry real = new DefaultJsonMapperProfileRegistry(Set.of());
            succeed(new JsonSystemProfileInstallStep(JsonConfig.defaults(), real));
            ObjectMapper installed = VertiqueJson.mapper();

            assertThrows(
                    IllegalArgumentException.class,
                    () -> VertiqueJson.install(JsonProfileId.SYSTEM, typed),
                    "VertiqueJson.install enforces the no-default-typing invariant on every call, "
                            + "including a same-id swap by in-process code");
            assertEquals(
                    JsonProfileId.SYSTEM,
                    VertiqueJson.profile().orElse(null),
                    "a rejected direct install must not change the installed id");
            assertSame(installed, VertiqueJson.mapper(), "a rejected direct install must not change the delegate");
        } finally {
            root.detachAppender(appender);
        }
    }

    // --- Helpers ---

    /**
     * Builds the step for a configured {@code json.systemProfile} id over the given registry, with the
     * production ownership seam (this fork owns the process codec).
     *
     * @param systemProfile the configured system-profile id
     * @param registry the registry the step resolves the mapper from
     * @return the constructed step
     */
    private static JsonSystemProfileInstallStep stepFor(String systemProfile, JsonMapperProfileRegistry registry) {
        return new JsonSystemProfileInstallStep(new JsonConfig(null, systemProfile), registry, () -> true);
    }

    /**
     * Runs the step and returns its failure, accepting either a failed future or a synchronous throw
     * (the lifecycle runner converts the latter into the former, so both are the same boot failure).
     *
     * @param step the step to run
     * @return the failure cause; never {@code null}
     */
    private static Throwable failureOf(JsonSystemProfileInstallStep step) {
        Future<Void> future;
        try {
            future = step.start();
        } catch (RuntimeException synchronousThrow) {
            return synchronousThrow;
        }
        assertTrue(future.isComplete(), "the install step must complete synchronously");
        assertTrue(future.failed(), "the install step must fail");
        return future.cause();
    }

    /**
     * Runs the step and asserts it succeeded.
     *
     * @param step the step to run
     */
    private static void succeed(JsonSystemProfileInstallStep step) {
        Future<Void> future = step.start();
        assertTrue(future.isComplete(), "the install step must complete synchronously");
        assertTrue(future.succeeded(), () -> "the install step must succeed; failed with: " + future.cause());
    }

    /**
     * Returns the message of a boot failure, asserting it is a {@link JsonProfileConfigurationException}.
     *
     * @param failure the captured failure
     * @param caseName the case under test, for the assertion message
     * @return the failure message, never {@code null}
     */
    private static String messageOf(Throwable failure, String caseName) {
        JsonProfileConfigurationException rejected = assertInstanceOf(
                JsonProfileConfigurationException.class,
                failure,
                "case '" + caseName + "' must fail the boot with a JSON profile configuration error");
        return String.valueOf(rejected.getMessage());
    }

    /**
     * Returns the first captured message at {@code level} containing {@code needle}, or {@code null}.
     *
     * @param appender the attached capture appender
     * @param level the required level
     * @param needle the required substring
     * @return the matching formatted message, or {@code null} when none matched
     */
    private static String logContaining(ListAppender<ILoggingEvent> appender, Level level, String needle) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(needle))
                .findFirst()
                .orElse(null);
    }

    // --- Fixtures ---

    /**
     * Minimal {@link JsonMapperProfileRegistry} that hands back exactly the mappers it was seeded
     * with. It exists so TP-005 can present the step with profile shapes
     * {@link DefaultJsonMapperProfileRegistry} would refuse to register — the step's guards must hold
     * for any application-bound registry implementation, which is the whole point of re-checking them.
     */
    private static final class FakeRegistry implements JsonMapperProfileRegistry {

        private final Map<JsonProfileId, ObjectMapper> mappers = new LinkedHashMap<>();

        /**
         * Seeds a profile.
         *
         * @param id the profile id
         * @param mapper the mapper to hand back for that id
         * @return this registry, for chaining
         */
        FakeRegistry with(String id, ObjectMapper mapper) {
            mappers.put(JsonProfileId.of(id), mapper);
            return this;
        }

        @Override
        public ObjectMapper mapper(JsonProfileId id) {
            ObjectMapper mapper = mappers.get(id);
            if (mapper == null) {
                throw new JsonProfileConfigurationException("Unknown JSON profile id '" + id.value() + "'");
            }
            return mapper;
        }

        @Override
        public JsonMapperProfile profile(JsonProfileId id) {
            return JsonMapperProfiles.of(id, mapper(id));
        }

        @Override
        public Set<JsonProfileId> profileIds() {
            return mappers.keySet();
        }
    }
}
