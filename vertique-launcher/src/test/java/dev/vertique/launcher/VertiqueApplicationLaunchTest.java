// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.bootstrap.BootstrapContext;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.launcher.application.ExitCodes;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies the end-to-end launch behaviour of {@link VertiqueApplication}:
 * <ul>
 *   <li>Successful launch — returns exit code 0 and the verticle receives the resolved config.</li>
 *   <li>Configuration propagation — the config passed via {@code --conf} reaches the
 *       {@link BootstrapContext} (and via Vert.x deployment config, the verticle itself).
 *       The deployment config is the full resolved tree (⊇ {@code --conf}), not just the
 *       raw {@code --conf} object.</li>
 *   <li>ServiceLoader ordering — contributors run in the expected priority order.</li>
 *   <li>Vert.x builder customisation — contributor-installed metrics factory is activated.</li>
 *   <li>Fail-fast exit code — a throwing contributor causes launch to return
 *       {@link ExitCodes#VERTX_INITIALIZATION}.</li>
 * </ul>
 *
 * <p>All tests use {@link TestVertiqueApplication} which sets {@code exitOnFailure=false}
 * so that {@link System#exit} is never called during the test run.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class VertiqueApplicationLaunchTest extends AbstractLaunchTestSupport {

    private static final String NOOP_VERTICLE = NoOpVerticle.class.getName();

    // --- Tests ---

    @Test
    @DisplayName("successful launch: returns 0 and verticle deployment config contains --conf keys")
    void zeroContributorParity() {
        int exitCode =
                new TestVertiqueApplication(new String[] {NOOP_VERTICLE, "--conf", "{\"hello\":\"world\"}"}).launch();

        assertEquals(0, exitCode, "launch must return 0 on success");
        JsonObject started = NoOpVerticle.startedConfig.get();
        assertNotNull(started, "NoOpVerticle must have been started");
        // Containment check: the resolved tree (⊇ --conf) must contain the --conf key.
        assertEquals("world", started.getString("hello"), "verticle config must contain the --conf value");
    }

    @Test
    @DisplayName("conf reaches BootstrapContext: --conf value is visible to contributors via resolved tree")
    void confReachesBootstrapContext() {
        int exitCode =
                new TestVertiqueApplication(new String[] {NOOP_VERTICLE, "--conf", "{\"fromConf\":\"yes\"}"}).launch();

        assertEquals(0, exitCode);
        JsonObject recorded = TestContributorState.recordedConfig.get();
        assertNotNull(recorded, "config must have been recorded by OrderedAlphaContributor");
        assertEquals(
                "yes",
                recorded.getString("fromConf"),
                "contributor context.config() must contain --conf value from resolved tree");
    }

    @Test
    @DisplayName("no --conf: context.config() is non-null resolved tree (may contain system-property keys)")
    void noConfYieldsNonNullResolvedTree() {
        int exitCode = new TestVertiqueApplication(new String[] {NOOP_VERTICLE}).launch();

        assertEquals(0, exitCode);
        JsonObject recorded = TestContributorState.recordedConfig.get();
        // The bootstrap loader now runs even without --conf, so the resolved tree may include
        // system-property keys.  The contract is: config is non-null and launch succeeds.
        assertNotNull(recorded, "recordedConfig must not be null even without --conf");
    }

    @Test
    @DisplayName("ServiceLoader ordering: Beta (priority 10) contributes before Alpha (priority 20)")
    void serviceLoaderOrdering() {
        int exitCode = new TestVertiqueApplication(new String[] {NOOP_VERTICLE}).launch();

        assertEquals(0, exitCode);
        List<String> contributions = TestContributorState.invocations.stream()
                .filter(s -> s.endsWith(":contribute"))
                .toList();
        int betaIdx = contributions.indexOf("beta:contribute");
        int alphaIdx = contributions.indexOf("alpha:contribute");
        assertTrue(betaIdx >= 0, "beta:contribute must have been recorded");
        assertTrue(alphaIdx >= 0, "alpha:contribute must have been recorded");
        assertTrue(betaIdx < alphaIdx, "beta (priority 10) must run before alpha (priority 20)");
    }

    @Test
    @DisplayName("builder customisation: FakeMetricsContributor activates metrics on the Vertx instance")
    void builderCustomisationActivatesMetrics() {
        TestContributorState.armFakeMetrics = true;

        int exitCode = new TestVertiqueApplication(new String[] {NOOP_VERTICLE}).launch();

        assertEquals(0, exitCode);
        assertTrue(FakeVertxMetrics.created.get(), "FakeVertxMetrics factory must have been invoked by Vert.x");
        Vertx vertx = TestVertiqueApplication.capturedVertx.get();
        assertNotNull(vertx, "Vertx must have been captured");
        assertTrue(vertx.isMetricsEnabled(), "metrics must be enabled on the Vertx instance");
    }

    @Test
    @DisplayName("fail-fast: ThrowingContributor causes launch to return VERTX_INITIALIZATION (11)")
    void failFastReturnsVertxInitializationExitCode() {
        TestContributorState.armThrowing = true;

        int exitCode = new TestVertiqueApplication(new String[] {NOOP_VERTICLE}).launch();

        assertEquals(
                ExitCodes.VERTX_INITIALIZATION,
                exitCode,
                "contributor failure must return ExitCodes.VERTX_INITIALIZATION (11)");
    }
}
