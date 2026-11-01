// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the shipped node configuration against the typed product configuration, because product configuration
 * ignores keys it does not know: a misspelled key would leave the default in place and look like a working node.
 *
 * <p>The graph is built without a database. Nothing here connects.
 */
class ShippedConfigTest {

    private static final Path SHIPPED = Path.of("config", "application.json");

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("every key of the shipped file is a setting of its typed configuration class")
    void everyShippedKeyIsKnown() throws Exception {
        JsonObject shipped = shipped();

        assertKnown(shipped.getJsonObject("db"), dev.vertique.db.DbPoolConfig.class, "db");
        assertKnown(shipped.getJsonObject("flyway"), dev.vertique.db.flyway.FlywayConfig.class, "flyway");
        JsonObject workflow = shipped.getJsonObject("workflow");
        assertKnown(
                workflow.getJsonObject("executor"),
                dev.vertique.workflow.config.WorkflowExecutorConfig.class,
                "workflow.executor");
        JsonObject recording = workflow.getJsonObject("recording");
        assertKnown(
                recording.getJsonObject("intake"), dev.vertique.workflow.config.WorkflowIntakeConfig.class, "intake");
        assertKnown(
                shipped.getJsonObject("inboxOutbox").getJsonObject("relay"),
                dev.vertique.inboxoutbox.OutboxRelayConfig.class,
                "inboxOutbox.relay");
        assertEquals(
                Set.of("workflow", "executor", "recording"),
                new TreeSet<>(Set.of("workflow", "executor", "recording")),
                "sanity");
        assertEquals(Set.of("executor", "recording"), Set.copyOf(workflow.fieldNames()));
        assertEquals(
                Set.of("db", "flyway", "workflow", "inboxOutbox", "services", "load"),
                Set.copyOf(shipped.fieldNames()));
        assertEquals(
                Set.of("load", "workflow"),
                Set.copyOf(shipped.getJsonObject("services").fieldNames()));
    }

    @Test
    @DisplayName("the typed configuration of the graph reflects the shipped values")
    void typedConfigurationReflectsTheShippedValues() throws Exception {
        JsonObject view =
                component(withApplicationName(shipped())).effectiveConfig().toJson();

        assertEquals(20, view.getJsonObject("db").getInteger("maxPoolSize"));
        assertEquals("load-node-1", view.getJsonObject("db").getString("applicationName"));
        assertEquals("DISABLED", view.getJsonObject("flyway").getString("mode"));
        assertEquals(1, view.getJsonObject("executor").getInteger("instances"));
        assertEquals(8, view.getJsonObject("executor").getInteger("concurrency"));
        assertEquals(5_000L, view.getJsonObject("executor").getLong("pollIntervalMs"));
        assertEquals("LISTEN_NOTIFY", view.getJsonObject("relay").getString("strategy"));
        assertEquals(1_000L, view.getJsonObject("relay").getLong("pollingIntervalMs"));
        assertEquals(50, view.getJsonObject("relay").getInteger("batchSize"));
        assertEquals(1, view.getJsonObject("relay").getInteger("instances"));
        assertEquals(20L, view.getJsonObject("load").getLong("handlerDelayMs"));
        assertEquals(1_000L, view.getJsonObject("load").getLong("timerDelayMs"));
    }

    @Test
    @DisplayName("the baseline graph binds no observer")
    void baselineBindsNoObserver() throws Exception {
        JsonObject observers = component(withApplicationName(shipped()))
                .effectiveConfig()
                .toJson()
                .getJsonObject("observers");

        assertEquals(0, observers.getInteger("executor"));
        assertEquals(0, observers.getInteger("outboxPublish"));
    }

    @Test
    @DisplayName("an arm overlay changes the typed configuration and nothing else")
    void armOverlayReachesTheTypedConfiguration() throws Exception {
        JsonObject config = withApplicationName(shipped());
        config.mergeIn(dev.vertique.examples.workflow.load.config.LoadArm.OFF.configOverlay(), true);

        JsonObject view = component(config).effectiveConfig().toJson();

        assertEquals(100L, view.getJsonObject("executor").getLong("pollIntervalMs"));
        assertEquals("POLLING", view.getJsonObject("relay").getString("strategy"));
        assertEquals(50L, view.getJsonObject("relay").getLong("pollingIntervalMs"));
        assertEquals(8, view.getJsonObject("executor").getInteger("concurrency"));
        assertEquals(20, view.getJsonObject("db").getInteger("maxPoolSize"));
    }

    @Test
    @DisplayName("startup validation refuses a node without an application name and accepts a named one")
    void startupValidationChecksTheApplicationName() throws Exception {
        JsonObject unnamed = shipped();
        Throwable failure = validate(component(unnamed));
        assertTrue(
                failure != null && String.valueOf(failure).contains("application_name"),
                "a node without db.properties.application_name must not start: " + failure);

        assertEquals(null, validate(component(withApplicationName(shipped()))));
    }

    @Test
    @DisplayName("startup validation refuses a node that would migrate")
    void startupValidationRefusesMigration() throws Exception {
        JsonObject migrating = withApplicationName(shipped());
        migrating.getJsonObject("flyway").put("mode", "MIGRATE");

        Throwable failure = validate(component(migrating));

        assertTrue(failure != null && String.valueOf(failure).contains("flyway.mode"), String.valueOf(failure));
    }

    private AppComponent component(JsonObject config) {
        return DaggerAppComponent.builder()
                .vertxModule(new VertxModule(vertx, config))
                .build();
    }

    /** Runs the validate-phase startup steps and returns the failure, or {@code null} when they pass. */
    private static Throwable validate(AppComponent component) throws Exception {
        for (ApplicationStartupStep step : component.startupSteps()) {
            if (step.phase() == LifecyclePhase.VALIDATE) {
                try {
                    step.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    return e.getCause();
                }
            }
        }
        return null;
    }

    private static JsonObject shipped() throws Exception {
        return new JsonObject(Files.readString(SHIPPED));
    }

    private static JsonObject withApplicationName(JsonObject config) {
        config.getJsonObject("db").put("properties", new JsonObject().put("application_name", "load-node-1"));
        return config;
    }

    private static void assertKnown(JsonObject section, Class<?> type, String path) {
        Set<String> known = knownSettings(type);
        Set<String> unknown = new TreeSet<>(section.fieldNames());
        unknown.removeAll(known);
        assertTrue(unknown.isEmpty(), path + " holds keys that " + type.getSimpleName() + " does not know: " + unknown);
    }

    private static Set<String> knownSettings(Class<?> type) {
        if (type.isRecord()) {
            return Arrays.stream(type.getRecordComponents())
                    .map(component -> component.getName())
                    .collect(Collectors.toSet());
        }
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                .map(Field::getName)
                .collect(Collectors.toSet());
    }
}
