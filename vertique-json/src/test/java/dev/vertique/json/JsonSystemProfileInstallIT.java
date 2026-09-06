// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.deploy.VerticleDeploymentManager;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Integration proof (TP-002) that a booted application installs the process JSON codec's mapper at
 * {@code CONFIGURE}, and that {@code json.systemProfile} either switches the whole process or fails
 * the boot.
 *
 * <p>Each case boots a real, minimal Dagger application component through
 * {@link VertiqueApplicationBootstrap}: {@link JsonRuntimeModule} supplies the registry, the
 * {@code JsonConfig} and the install step; the test-local modules supply only what the lifecycle
 * runner itself needs (root config, a config parser, and an empty verticle deployment manager). What
 * the assertions then exercise is the plain Vert.x surface — {@code Json.encode} and
 * {@code new JsonObject(String)} — so a green run means the process codec really changed, not that a
 * test helper was consulted.
 *
 * <ul>
 *   <li><b>defaults</b> — {@code installedProfile()} is {@code system}; {@code java.time} and
 *       {@code Optional} encode (both throw on the raw Vert.x mapper); a null property is emitted;
 *       a JSON float parses to {@code Double}.</li>
 *   <li><b>a second defaults boot, without a reset</b> — succeeds as a same-id swap: the id is still
 *       {@code system} but the delegate is the second application's registry instance, and the swap
 *       is logged at INFO naming the installing step.</li>
 *   <li><b>{@code json.systemProfile: vertique}</b> — the opinionated recipe governs the whole
 *       process: nulls are omitted and a JSON float parses to {@code BigDecimal}.</li>
 *   <li><b>{@code json.systemProfile: vertx}</b> — the retired id fails the boot at {@code CONFIGURE}
 *       naming the rename, and nothing is installed.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class JsonSystemProfileInstallIT {

    /** Fixture proving inclusion: one present and one null property. */
    public record Fixture(String present, String absent) {}

    /** Fixture proving {@code Optional} + {@code java.time} support. */
    public record Note(Optional<String> text, LocalDate due) {}

    private static final LocalDate DUE = LocalDate.of(2026, 9, 6);

    /** Restores the raw Vert.x delegate; the failsafe fork is shared with every other IT class. */
    @AfterEach
    void resetProcessCodec() {
        VertiqueJson.resetForTests();
    }

    @Test
    @DisplayName("TP-002: defaults install system, a second boot swaps, vertique switches the process, vertx fails")
    void defaultsInstallSystemVertiqueSwitchesAndVertxFails() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        Application first = null;
        Application second = null;
        try {
            // --- Case 1: defaults ---
            first = bootWith(new JsonObject());

            assertEquals(
                    JsonProfileId.SYSTEM,
                    VertiqueJson.installedProfile().orElse(null),
                    "the defaults install the reserved system profile as the process codec");
            assertSame(
                    first.component.registry().mapper(JsonProfileId.SYSTEM),
                    VertiqueJson.mapper(),
                    "the process delegate is the booted application's registry instance");
            assertEquals("\"2026-09-06\"", Json.encode(DUE), "java.time encodes after the install (it throws before)");
            assertEquals(
                    "{\"text\":null,\"due\":\"2026-09-06\"}",
                    Json.encode(new Note(Optional.empty(), DUE)),
                    "system renders an absent Optional as null and a LocalDate as ISO-8601");
            assertEquals(
                    "{\"present\":\"x\",\"absent\":null}",
                    Json.encode(new Fixture("x", null)),
                    "system keeps Vert.x's null-emitting inclusion");
            assertInstanceOf(
                    Double.class,
                    new JsonObject("{\"x\":1.5}").getValue("x"),
                    "system keeps Vert.x's Double float binding");

            // --- Case 2: a second defaults boot in the same JVM, WITHOUT a reset ---
            ObjectMapper firstMapper = VertiqueJson.mapper();
            appender.list.clear();
            second = bootWith(new JsonObject());

            assertEquals(
                    JsonProfileId.SYSTEM,
                    VertiqueJson.installedProfile().orElse(null),
                    "a same-id second boot succeeds and keeps the installed id");
            ObjectMapper secondMapper = second.component.registry().mapper(JsonProfileId.SYSTEM);
            assertNotSame(firstMapper, secondMapper, "each application builds its own system mapper instance");
            assertSame(secondMapper, VertiqueJson.mapper(), "the same-id swap adopts the second application's mapper");
            String swapLog = logContaining(appender, Level.INFO, JsonProfileId.SYSTEM.value());
            assertNotNull(swapLog, "the same-id swap must be logged at INFO; captured: " + appender.list);
            assertTrue(
                    swapLog.contains("JsonSystemProfileInstallStep"),
                    "the swap log must attribute the installing step; got: " + swapLog);

            close(second);
            second = null;
            close(first);
            first = null;
            VertiqueJson.resetForTests();

            // --- Case 3: json.systemProfile: vertique ---
            first = bootWith(new JsonObject().put("systemProfile", "vertique"));

            assertEquals(
                    JsonProfileId.of("vertique"),
                    VertiqueJson.installedProfile().orElse(null),
                    "an explicit json.systemProfile selects the installed profile");
            assertEquals("\"2026-09-06\"", Json.encode(DUE), "vertique also supports java.time");
            assertEquals(
                    "{\"present\":\"x\"}",
                    Json.encode(new Fixture("x", null)),
                    "vertique omits nulls for every Json.encode in the process");
            assertInstanceOf(
                    BigDecimal.class,
                    new JsonObject("{\"x\":1.5}").getValue("x"),
                    "vertique parses JSON floats as BigDecimal, including inside JsonObject");

            close(first);
            first = null;
            VertiqueJson.resetForTests();

            // --- Case 4: json.systemProfile: vertx (retired id) ---
            appender.list.clear();
            Throwable cause = bootFailure(new JsonObject().put("systemProfile", "vertx"));

            JsonProfileConfigurationException rejected = assertInstanceOf(
                    JsonProfileConfigurationException.class,
                    cause,
                    "a configured 'vertx' system profile must fail the boot");
            String message = String.valueOf(rejected.getMessage());
            assertTrue(
                    message.contains("renamed") && message.contains("system"),
                    "the boot failure must name the rename; got: " + message);
            assertTrue(
                    VertiqueJson.installedProfile().isEmpty(), "a failed boot must leave the process codec untouched");
            String failureLog = logContaining(appender, Level.ERROR, "CONFIGURE");
            assertNotNull(
                    failureLog,
                    "the boot must fail in the CONFIGURE phase, where the install step runs; captured: "
                            + appender.list);
        } finally {
            close(second);
            close(first);
            root.detachAppender(appender);
        }
    }

    // --- Boot helpers ---

    /** A booted application and the resources the test must release. */
    private record Application(Vertx vertx, ItComponent component, VertiqueApplicationHandle<ItComponent> handle) {}

    /**
     * Boots a minimal application whose {@code json} config section is {@code jsonSection}.
     *
     * @param jsonSection the {@code json} configuration section
     * @return the booted application
     * @throws Exception if the boot fails or times out
     */
    private static Application bootWith(JsonObject jsonSection) throws Exception {
        Vertx vertx = Vertx.vertx();
        JsonObject config = new JsonObject().put("json", jsonSection);
        ItComponent component = component(vertx, config);
        VertiqueApplicationHandle<ItComponent> handle = VertiqueApplicationBootstrap.start(
                        VertiqueRuntime.of(vertx, config), runtime -> component)
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        return new Application(vertx, component, handle);
    }

    /**
     * Boots a minimal application expected to fail, returning the original cause and closing the
     * Vert.x instance the attempt owned.
     *
     * @param jsonSection the {@code json} configuration section
     * @return the unwrapped startup failure
     * @throws Exception if the boot unexpectedly succeeds or times out
     */
    private static Throwable bootFailure(JsonObject jsonSection) throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            JsonObject config = new JsonObject().put("json", jsonSection);
            ItComponent component = component(vertx, config);
            CompletableFuture<VertiqueApplicationHandle<ItComponent>> booted = VertiqueApplicationBootstrap.start(
                            VertiqueRuntime.of(vertx, config), runtime -> component)
                    .toCompletionStage()
                    .toCompletableFuture();
            ExecutionException failed = assertThrows(
                    ExecutionException.class, () -> booted.get(10, TimeUnit.SECONDS), "the boot must fail");
            return failed.getCause();
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Builds the application component for one boot.
     *
     * @param vertx the Vert.x instance the (empty) deployment manager is bound to
     * @param config the root configuration
     * @return the built component
     */
    private static ItComponent component(Vertx vertx, JsonObject config) {
        return DaggerJsonSystemProfileInstallIT_ItComponent.builder()
                .itRuntimeModule(new ItRuntimeModule(vertx, config))
                .build();
    }

    /**
     * Shuts the application down and closes the Vert.x instance it owned.
     *
     * @param application the application to release, may be {@code null}
     * @throws Exception if teardown fails or times out
     */
    private static void close(Application application) throws Exception {
        if (application == null) {
            return;
        }
        application
                .handle()
                .shutdown()
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        application.vertx().close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
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

    // --- Fixtures: the minimal application graph ---

    /**
     * Declares the lifecycle multibindings empty-by-default so this component composes without
     * {@code DeployerModule} — the JSON module's own {@code CONFIGURE} step is the only contribution
     * under test.
     */
    @Module
    interface ItLifecycleModule {

        /**
         * Declares the startup-step multibinding.
         *
         * @return the (possibly empty) startup-step set
         */
        @Multibinds
        Set<ApplicationStartupStep> startupSteps();

        /**
         * Declares the shutdown-step multibinding.
         *
         * @return the (possibly empty) shutdown-step set
         */
        @Multibinds
        Set<ApplicationShutdownStep> shutdownSteps();
    }

    /** Supplies the per-boot runtime inputs the lifecycle runner needs. */
    @Module
    static final class ItRuntimeModule {

        private final Vertx vertx;
        private final JsonObject config;

        /**
         * Creates the module for one boot.
         *
         * @param vertx the Vert.x instance
         * @param config the root configuration
         */
        ItRuntimeModule(Vertx vertx, JsonObject config) {
            this.vertx = vertx;
            this.config = config;
        }

        /**
         * Provides the root configuration.
         *
         * @return the root configuration
         */
        @Provides
        @Singleton
        @VertxConfig
        JsonObject config() {
            return config;
        }

        /**
         * Provides a minimal config parser over a plain Jackson mapper — enough to bind
         * {@link JsonConfig} through its {@code @JsonCreator}, and deliberately independent of the
         * process codec under test.
         *
         * @return the config parser
         */
        @Provides
        @Singleton
        ConfigParser configParser() {
            ObjectMapper mapper = new ObjectMapper();
            return new ConfigParser() {

                @Override
                public <T> T parse(JsonObject section, Class<T> type) {
                    try {
                        return mapper.readValue(section == null ? "{}" : section.encode(), type);
                    } catch (Exception e) {
                        throw new ConfigurationException(
                                "failed to parse config section into " + type.getSimpleName(), e);
                    }
                }

                @Override
                public <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType) {
                    throw new UnsupportedOperationException("not needed for JsonSystemProfileInstallIT");
                }

                @Override
                public <T> List<T> parseKeyedObject(
                        JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps) {
                    throw new UnsupportedOperationException("not needed for JsonSystemProfileInstallIT");
                }
            };
        }

        /**
         * Provides a deployment manager with no deployments: this application has no verticles, so
         * every verticle phase is a no-op and the proof stays about the CONFIGURE step.
         *
         * @return the deployment manager
         */
        @Provides
        @Singleton
        VerticleDeploymentManager verticleDeploymentManager() {
            return new VerticleDeploymentManager(new VerticleDeployer(vertx), Set.of());
        }
    }

    /**
     * The application component: {@link JsonRuntimeModule} plus the runtime inputs. Exposing the
     * registry lets the test compare the installed delegate against the very instance this
     * application's registry holds.
     */
    @Singleton
    @dagger.Component(modules = {JsonRuntimeModule.class, ItLifecycleModule.class, ItRuntimeModule.class})
    interface ItComponent extends VertiqueApplicationComponent {

        /**
         * Exposes this application's JSON profile registry.
         *
         * @return the registry
         */
        JsonMapperProfileRegistry registry();
    }
}
