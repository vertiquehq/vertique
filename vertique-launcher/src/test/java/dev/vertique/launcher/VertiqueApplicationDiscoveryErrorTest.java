// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.bootstrap.ContributorRunner;
import io.vertx.launcher.application.ExitCodes;
import java.util.ServiceConfigurationError;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies that a {@link ServiceConfigurationError} thrown from contributor discovery maps to
 * exit code {@link ExitCodes#VERTX_INITIALIZATION} (11) and that any bootstrap property sources
 * opened before the failure are closed exactly once.
 *
 * <p>Uses a test subclass of {@link VertiqueApplication} that overrides the package-private
 * {@code discoverContributors()} hook to throw a controlled {@link ServiceConfigurationError}.
 * The {@link LauncherStubSourceFactory} is used to declare a property source so we can confirm
 * it is closed despite the discovery failure occurring after the bootstrap config load.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class VertiqueApplicationDiscoveryErrorTest extends AbstractLaunchTestSupport {

    private static final String NOOP_VERTICLE = NoOpVerticle.class.getName();

    /** Deployment config that declares one launcher-stub source for close-count verification. */
    private static final String CONF_WITH_SOURCE = """
            {"config":{"propertySources":[{"type":"launcher-stub","name":"disc-error-src"}]}}
            """;

    // --- Application subclass that throws ServiceConfigurationError during discovery ---

    /**
     * {@link VertiqueApplication} subclass that overrides the discovery hook to throw a
     * controlled {@link ServiceConfigurationError}, simulating a broken provider jar.
     */
    private static final class DiscoveryErrorApplication extends VertiqueApplication {

        /**
         * Constructs the application with {@code exitOnFailure=false} so tests can inspect
         * the returned exit code without triggering {@link System#exit}.
         *
         * @param args command-line arguments
         */
        DiscoveryErrorApplication(String[] args) {
            super(args, false, false);
        }

        /**
         * Overrides the discovery hook to throw a {@link ServiceConfigurationError},
         * simulating a broken {@link java.util.ServiceLoader} provider registration.
         *
         * @return never returns; always throws
         * @throws ServiceConfigurationError unconditionally
         */
        @Override
        ContributorRunner discoverContributors() {
            throw new ServiceConfigurationError(
                    "Provider dev.broken.Contributor not found — simulated discovery error");
        }

        /**
         * {@inheritDoc}
         *
         * <p>Captures the live {@link io.vertx.core.Vertx} instance for {@code @AfterEach} teardown.
         *
         * @param ctx the hook context
         */
        @Override
        public void afterVertxStarted(io.vertx.launcher.application.HookContext ctx) {
            TestVertiqueApplication.capturedVertx.set(ctx.vertx());
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("discovery ServiceConfigurationError → exit 11, verticle never deployed")
    void discoveryErrorExits11AndVerticleNotDeployed() {
        int exitCode = new DiscoveryErrorApplication(new String[] {NOOP_VERTICLE}).launch();

        assertEquals(
                ExitCodes.VERTX_INITIALIZATION,
                exitCode,
                "ServiceConfigurationError from discovery must map to VERTX_INITIALIZATION (11)");
        assertNull(NoOpVerticle.startedConfig.get(), "verticle must NOT have been deployed on discovery error");
    }

    @Test
    @DisplayName("discovery ServiceConfigurationError with declared source → source closed exactly once")
    void discoveryErrorClosesBootstrapSources() {
        // Declare a launcher-stub property source so that the bootstrap load creates one source
        // that must be closed even though discovery fails after the load succeeds.
        int exitCode = new DiscoveryErrorApplication(new String[] {NOOP_VERTICLE, "--conf", CONF_WITH_SOURCE}).launch();

        assertEquals(ExitCodes.VERTX_INITIALIZATION, exitCode, "exit code must be 11 on discovery error");
        assertEquals(
                1,
                LauncherStubSourceFactory.State.closeCount.get(),
                "bootstrap property source must be closed exactly once after discovery error");
    }
}
