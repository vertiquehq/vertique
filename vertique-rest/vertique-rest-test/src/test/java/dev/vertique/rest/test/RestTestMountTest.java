// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins the one invariant {@link RestTestMount} actually enforces — a non-empty middleware set — and
 * the graph fact that makes enforcing it safe.
 *
 * <p>The two tests are a pair on purpose. Rejecting an empty set is only a sound rule if a real
 * fixture graph can never produce one, so {@link #fixtureGraphAlwaysCarriesTheBuiltInRootTier()} is
 * the standing proof of that premise: were {@code RestCoreModule} to stop contributing its ROOT
 * middlewares, this test fails and tells the maintainer the rejection has become a trap, rather than
 * leaving a consumer to discover it as an unexplained {@link IllegalArgumentException}.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RestTestMountTest {

    /** Bound for the single Vert.x close this class awaits. */
    private static final long AWAIT_SECONDS = 5;

    private static Vertx vertx;

    @BeforeAll
    static void createVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void closeVertx() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    // --- The enforced invariant ---

    @Test
    @DisplayName("the constructor rejects an empty middleware set")
    void rejectsAnEmptyMiddlewareSet() {
        // The factory is the graph's real one, so the only thing under test is the middleware set:
        // this is precisely the hand-assembled, ROOT-less pairing the handle exists to discourage.
        RestTestMount fromGraph = graphMount();

        assertThatThrownBy(() -> new RestTestMount(fromGraph.factory(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("middlewares must not be empty");
    }

    @Test
    @DisplayName("a handle built by the fixture graph is accepted and keeps the graph's whole set")
    void acceptsTheFixtureGraphsMiddlewareSet() {
        RestTestMount fromGraph = graphMount();

        assertThat(fromGraph.middlewares())
                .as("the rejection above must not be reachable from the supported construction path")
                .isNotEmpty();
        assertThat(fromGraph.factory()).isNotNull();
    }

    // --- Why the rejection is safe ---

    @Test
    @DisplayName("the fixture graph always carries the framework's built-in ROOT middlewares")
    void fixtureGraphAlwaysCarriesTheBuiltInRootTier() {
        Set<Middleware> middlewares = graphMount().middlewares();

        assertThat(middlewares.stream()
                        .filter(m -> m.scope() == MiddlewareScope.ROOT)
                        .map(m -> m.getClass().getSimpleName()))
                .as("RestCoreModule contributes four ROOT middlewares and includes CorrelationIngressModule "
                        + "for the fifth; an empty middleware set is therefore unreachable through the graph")
                .contains(
                        "RequestContextLifecycle",
                        "ContextualLoggingMiddleware",
                        "DefaultHeadersMiddleware",
                        "RestRequestCompletionEmitter",
                        "CorrelationIngressMiddleware");
    }

    // --- Helpers ---

    /**
     * Builds a mount handle the supported way — through a Dagger graph over
     * {@link RestTestFixtureModule}.
     *
     * @return the graph-built handle
     */
    private static RestTestMount graphMount() {
        return DaggerFixtureSelfTestComponent.factory()
                .create(vertx, new JsonObject(), RestTestContributions.none())
                .testMount();
    }
}
