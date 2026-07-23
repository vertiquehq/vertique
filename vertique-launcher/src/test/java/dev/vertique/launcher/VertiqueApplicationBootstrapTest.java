// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.bootstrap.BootstrapContext;
import io.vertx.core.json.JsonObject;
import io.vertx.launcher.application.ExitCodes;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies bootstrap config integration in {@link VertiqueApplication}:
 * <ul>
 *   <li>Resolved tree is installed as deployment config — verticle {@code config()} contains
 *       both the {@code --conf} values and a system-property key injected via
 *       {@code bootstrap.test.marker}; contributors also see the merged tree via
 *       {@link BootstrapContext#config()}.</li>
 *   <li>Load failure aborts startup — unknown store type causes
 *       {@link ExitCodes#VERTX_INITIALIZATION} ({@code 11}), the verticle is never deployed,
 *       and no contributor shutdown invocations occur (bootstrap failed before contribution).</li>
 * </ul>
 *
 * <p>All tests use {@link TestVertiqueApplication} which sets {@code exitOnFailure=false}
 * so that {@link System#exit} is never called during the test run.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class VertiqueApplicationBootstrapTest extends AbstractLaunchTestSupport {

    private static final String NOOP_VERTICLE = NoopVerticle.class.getName();
    private static final String MARKER_PROP = "bootstrap.test.marker";
    private static final String MARKER_VALUE = "test-marker-xyz";

    /** Restore the system property after each test. */
    @AfterEach
    void restoreSystemProp() {
        System.clearProperty(MARKER_PROP);
    }

    // --- Resolved tree: deployment config + BootstrapContext see merged values ---

    @Test
    @DisplayName(
            "resolved tree: verticle config contains --conf keys and sys-prop marker; BootstrapContext sees same tree")
    void resolvedTreeInstalledAndVisibleToContributor() {
        System.setProperty(MARKER_PROP, MARKER_VALUE);

        int exitCode = new TestVertiqueApplication(
                        new String[] {NOOP_VERTICLE, "--conf", "{\"hello\":\"world\",\"fromConf\":\"yes\"}"})
                .launch();

        assertEquals(0, exitCode, "launch must succeed");

        // Deployment config assertions
        JsonObject started = NoopVerticle.startedConfig.get();
        assertNotNull(started, "NoopVerticle must have been started");
        assertEquals("world", started.getString("hello"), "verticle config must contain --conf keys");
        assertEquals(
                MARKER_VALUE,
                started.getString(MARKER_PROP),
                "sys-prop key must be present in verticle deployment config (resolved tree)");

        // BootstrapContext assertions (via contributor)
        JsonObject recorded = TestContributorState.recordedConfig.get();
        assertNotNull(recorded, "alpha contributor must have recorded context.config()");
        assertEquals("yes", recorded.getString("fromConf"), "contributor context.config() must contain --conf keys");
        assertEquals(
                MARKER_VALUE,
                recorded.getString(MARKER_PROP),
                "contributor context.config() must contain sys-prop key from resolved tree");
    }

    // --- Load failure: exit 11, verticle never deployed, no contributor shutdown invocations ---

    @Test
    @DisplayName(
            "bootstrap failure: unknown store type → exit 11, NoopVerticle never started, no contributor shutdown invocations")
    void bootstrapLoadFailureExits11AndProducesNoShutdowns() {
        // Inject a config.stores array with an unknown type to trigger BootstrapConfigException
        String conf = "{\"config\":{\"stores\":[{\"type\":\"no-such-store\"}]}}";

        int exitCode = new TestVertiqueApplication(new String[] {NOOP_VERTICLE, "--conf", conf}).launch();

        assertEquals(
                ExitCodes.VERTX_INITIALIZATION,
                exitCode,
                "bootstrap load failure must map to VERTX_INITIALIZATION (11)");
        assertNull(NoopVerticle.startedConfig.get(), "NoopVerticle must never have been started");

        // No contributor contributed → no :shutdown entries expected
        List<String> shutdowns = TestContributorState.invocations.stream()
                .filter(s -> s.endsWith(":shutdown"))
                .toList();
        assertTrue(
                shutdowns.isEmpty(),
                "no shutdown invocations expected when bootstrap fails before contribution; got: " + shutdowns);
    }
}
