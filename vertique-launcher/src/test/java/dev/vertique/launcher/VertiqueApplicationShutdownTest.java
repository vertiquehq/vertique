// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.launcher.application.ExitCodes;
import io.vertx.launcher.application.HookContext;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies that contributor shutdown hooks run exactly once across all stop/failure paths in
 * {@link VertiqueApplication}:
 *
 * <ul>
 *   <li>Contract-level idempotence — calling {@code afterVertxStopped} twice only fires hooks
 *       once.</li>
 *   <li>Deploy-failure path — {@code afterFailureToDeployVerticle} triggers hooks exactly once and
 *       exits with {@link ExitCodes#VERTX_DEPLOYMENT}.</li>
 *   <li>Start-failure path — a contributor failure causes hooks to run for the contributed prefix
 *       only, exiting with {@link ExitCodes#VERTX_INITIALIZATION}.</li>
 * </ul>
 *
 * <p>All tests use {@link TestVertiqueApplication} which sets {@code exitOnFailure=false} so that
 * {@link System#exit} is never called during the test run.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class VertiqueApplicationShutdownTest extends AbstractLaunchTestSupport {

    private static final String NOOP_VERTICLE = NoOpVerticle.class.getName();
    private static final String FAILING_VERTICLE = FailingVerticle.class.getName();

    // --- Tests ---

    @Test
    @DisplayName("idempotence: afterVertxStopped called twice fires shutdown hooks exactly once")
    void shutdownHooksRunExactlyOnceOnDoubleAfterVertxStopped() {
        // Beta (priority 10) contributes first, Alpha (priority 20) second.
        // Reverse shutdown order is [Alpha, Beta].
        TestVertiqueApplication app = new TestVertiqueApplication(new String[] {});
        app.afterConfigParsed(new JsonObject());
        app.createVertxBuilder(new VertxOptions());

        // Verify contribution happened: filter for :contribute events.
        List<String> contributions = TestContributorState.invocations.stream()
                .filter(s -> s.endsWith(":contribute"))
                .toList();
        assertTrue(contributions.contains("beta:contribute"), "beta must have contributed");
        assertTrue(contributions.contains("alpha:contribute"), "alpha must have contributed");

        // Clear to isolate shutdown events from contribute events.
        TestContributorState.invocations.clear();

        // Call afterVertxStopped twice — hooks must fire only once.
        HookContext mockCtx = mock(HookContext.class);
        app.afterVertxStopped(mockCtx);
        app.afterVertxStopped(mockCtx);

        List<String> shutdowns = List.copyOf(TestContributorState.invocations);
        assertEquals(2, shutdowns.size(), "exactly two shutdown entries expected (alpha and beta, once each)");
        assertEquals("alpha:shutdown", shutdowns.get(0), "alpha (last contributed) shuts down first");
        assertEquals("beta:shutdown", shutdowns.get(1), "beta (first contributed) shuts down second");
    }

    @Test
    @DisplayName("deploy-failure path: hooks run exactly once, launch returns VERTX_DEPLOYMENT (15)")
    void shutdownHooksRunOnDeployFailure() {
        int exitCode = new TestVertiqueApplication(new String[] {FAILING_VERTICLE}).launch();

        assertEquals(ExitCodes.VERTX_DEPLOYMENT, exitCode, "FailingVerticle must cause exit code 15");
        // Alpha (priority 20) contributes after Beta (priority 10); both are in the contributed
        // prefix. Reverse shutdown order is [alpha, beta].
        List<String> shutdowns = TestContributorState.invocations.stream()
                .filter(s -> s.endsWith(":shutdown"))
                .toList();
        assertEquals(2, shutdowns.size(), "alpha:shutdown and beta:shutdown must each appear exactly once");
        assertEquals("alpha:shutdown", shutdowns.get(0), "alpha (last contributed) shuts down first");
        assertEquals("beta:shutdown", shutdowns.get(1), "beta (first contributed) shuts down second");
    }

    @Test
    @DisplayName("start-failure path: only contributed-prefix hooks run, launch returns VERTX_INITIALIZATION (11)")
    void shutdownHooksRunOnlyForContributedPrefixOnStartFailure() {
        // ThrowingContributor (priority 30) runs after Beta (10) and Alpha (20) and aborts.
        // Contribution order: Beta(10) → Alpha(20) → Throwing(30, throws).
        // Contributed prefix: [Beta, Alpha]. Reverse shutdown: [Alpha, Beta].
        // ThrowingContributor never completed contribute, so it must not appear in shutdown.
        TestContributorState.armThrowing = true;

        int exitCode = new TestVertiqueApplication(new String[] {NOOP_VERTICLE}).launch();

        assertEquals(ExitCodes.VERTX_INITIALIZATION, exitCode, "contributor failure must return 11");
        List<String> shutdowns = TestContributorState.invocations.stream()
                .filter(s -> s.endsWith(":shutdown"))
                .toList();
        assertEquals(2, shutdowns.size(), "alpha:shutdown and beta:shutdown expected (both in contributed prefix)");
        assertEquals("alpha:shutdown", shutdowns.get(0), "alpha shuts down first (reverse of contribution)");
        assertEquals("beta:shutdown", shutdowns.get(1), "beta shuts down second (reverse of contribution)");
        // Verify throwing never appeared in shutdown (it never completed contribute)
        assertTrue(
                TestContributorState.invocations.stream().noneMatch(s -> s.startsWith("throwing:")),
                "ThrowingContributor must not appear in shutdown invocations");
    }
}
