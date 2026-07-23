// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.bootstrap;

import static dev.vertique.config.bootstrap.VertxThreadLeakAssertions.assertNoLeakedVertxThreads;
import static dev.vertique.config.bootstrap.VertxThreadLeakAssertions.liveVertxThreadNames;
import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BootstrapConfigLoader}.
 *
 * <p>Verifies precedence semantics, null safety, thread-safety guarantees (wrong-thread rejection),
 * temporary Vertx lifecycle (thread-leak), and result invariants.
 */
class BootstrapConfigLoaderTest {

    // --- Precedence parity ---

    @Nested
    @DisplayName("Precedence semantics")
    class PrecedenceTests {

        private String savedSysPropValue;
        private static final String SYS_PROP_KEY = "vertique.bootstrap.test.sysprop";
        private static final String COLLISION_KEY = "vertique.bootstrap.test.collision";

        @BeforeEach
        void setUp() {
            savedSysPropValue = System.getProperty(SYS_PROP_KEY);
            System.setProperty(SYS_PROP_KEY, "from-sys-prop");
            System.setProperty(COLLISION_KEY, "from-sys-prop");
        }

        @AfterEach
        void tearDown() {
            if (savedSysPropValue != null) {
                System.setProperty(SYS_PROP_KEY, savedSysPropValue);
            } else {
                System.clearProperty(SYS_PROP_KEY);
            }
            System.clearProperty(COLLISION_KEY);
        }

        @Test
        @DisplayName(
                "System property appears in result; deploymentConfig overrides collision; deployment-only key survives")
        void shouldRespectPrecedenceOrder() {
            JsonObject deploymentConfig = new JsonObject()
                    .put(COLLISION_KEY, "from-deployment")
                    .put("deployment-only-key", "deployment-value");

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deploymentConfig);

            // sys prop key survives (directory stores absent in test cwd → optional → fine)
            assertEquals(
                    "from-sys-prop",
                    result.config().getString(SYS_PROP_KEY),
                    "System property value must appear in merged config");

            // deploymentConfig wins over sys prop on collision
            assertEquals(
                    "from-deployment",
                    result.config().getString(COLLISION_KEY),
                    "deploymentConfig must override system property on collision");

            // deployment-only key must survive
            assertEquals(
                    "deployment-value",
                    result.config().getString("deployment-only-key"),
                    "Key present only in deploymentConfig must appear in result");
        }
    }

    // --- Null deploymentConfig ---

    @Nested
    @DisplayName("Null deploymentConfig safety")
    class NullDeploymentConfigTests {

        @Test
        @DisplayName("null deploymentConfig is treated as empty overlay, result is non-null")
        void shouldHandleNullDeploymentConfig() {
            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(null);

            assertNotNull(result, "Result must not be null when deploymentConfig is null");
            assertNotNull(result.config(), "config() must not be null");
        }
    }

    // --- Thread-leak ---

    @Nested
    @DisplayName("Temp Vertx thread-leak prevention")
    class ThreadLeakTests {

        @Test
        @DisplayName("No vert.x- threads remain after load completes")
        void shouldNotLeakVertxThreads() throws InterruptedException {
            Set<String> before = liveVertxThreadNames();

            BootstrapConfigLoader.load(new JsonObject());

            assertNoLeakedVertxThreads(before);
        }
    }

    // --- Wrong-thread rejection ---

    @Nested
    @DisplayName("Wrong-thread rejection")
    class WrongThreadTests {

        private Vertx testVertx;

        @BeforeEach
        void setUp() {
            testVertx = Vertx.vertx();
        }

        @AfterEach
        void tearDown() throws InterruptedException, TimeoutException {
            try {
                testVertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                // ignore close errors in teardown
            }
        }

        @Test
        @DisplayName("Calling load from a Vert.x context throws IllegalStateException")
        void shouldRejectCallFromVertxContext() throws InterruptedException {
            CompletableFuture<Throwable> thrown = new CompletableFuture<>();

            testVertx.runOnContext(v -> {
                try {
                    BootstrapConfigLoader.load(new JsonObject());
                    thrown.complete(null); // no exception — unexpected
                } catch (Throwable t) {
                    thrown.complete(t);
                }
            });

            Throwable caught;
            try {
                caught = thrown.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                fail("Timed out waiting for wrong-thread check: " + e.getMessage());
                return;
            }

            assertNotNull(caught, "Expected an exception when called from a Vert.x context, got none");
            assertInstanceOf(
                    IllegalStateException.class,
                    caught,
                    "Expected IllegalStateException, got: " + caught.getClass().getName());
        }
    }

    // --- Result invariants ---

    @Nested
    @DisplayName("Result invariants")
    class ResultInvariantsTests {

        @Test
        @DisplayName("propertySources is empty and immutable")
        void shouldReturnEmptyImmutablePropertySources() {
            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(new JsonObject());

            assertNotNull(result.propertySources(), "propertySources must not be null");
            assertTrue(result.propertySources().isEmpty(), "propertySources must be empty in this slice");

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> result.propertySources().clear(),
                    "propertySources must be immutable");
        }
    }
}
