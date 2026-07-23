// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ServiceConfigurationError;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a {@link ServiceConfigurationError} thrown from application-factory discovery is
 * handled by {@link VertiqueBootstrapVerticle#start} into a <em>failed start promise</em> (a failed
 * deployment carrying the original cause) rather than propagating uncaught.
 *
 * <p>A {@link ServiceConfigurationError} is an {@link Error}, not a {@link RuntimeException}, so the
 * verticle's {@code start()} must catch {@link Throwable} for the discovery failure handling to fire
 * — mirroring {@code VertiqueApplication.createVertxBuilder}, which catches {@code Throwable} to
 * handle the same error from contributor discovery. The error is injected through the verticle's
 * package-private discovery seam rather than via a malformed real {@code META-INF/services} entry
 * (the test classpath already carries the valid {@link TestApplicationFactory} entry used by
 * {@link VertiqueBootstrapVerticleIT}).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class VertiqueBootstrapVerticleDiscoveryErrorTest {

    @Test
    @DisplayName("discovery ServiceConfigurationError → failed start promise carrying the original cause, not uncaught")
    void discoveryError_failsStartPromise_withOriginalCause(Vertx vertx, VertxTestContext ctx) {
        ServiceConfigurationError injected = new ServiceConfigurationError(
                "Provider dev.broken.Factory not found — simulated malformed META-INF/services entry");

        VertiqueBootstrapVerticle verticle = new VertiqueBootstrapVerticle(() -> {
            throw injected;
        });

        vertx.deployVerticle(verticle)
                .onComplete(ctx.failing(cause -> ctx.verify(() -> {
                    // The error was caught and converted into a failed start promise (failed deployment) —
                    // proving the catch covers Error/ServiceConfigurationError, not just RuntimeException.
                    assertInstanceOf(
                            ServiceConfigurationError.class,
                            cause,
                            "the original ServiceConfigurationError propagates as the deployment failure cause");
                    assertTrue(
                            cause.getMessage().contains("simulated malformed META-INF/services entry"),
                            "the original cause is preserved, not swallowed: " + cause.getMessage());
                    ctx.completeNow();
                })));
    }
}
