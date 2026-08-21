// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.security.SecurityRuntime;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves finding W6: when {@link McpRequestDispatcher#handleFailure(RoutingContext)} finds the
 * response already ended, it cancels the whole-request timeout timer before returning, so a live
 * timer cannot later fire and record a false timeout past the finished request (T001 watch-item c).
 *
 * <p>The proof is a focused seam assertion rather than an end-to-end mount: a post-end failure is a
 * timing-dependent event to orchestrate through a real server, whereas the decisive fact — that the
 * early-return path cancels the stored timer — is directly observable at the dispatcher's timer seam.
 * Against pre-fix production the early return never touched the timer, so the {@code cancelTimer}
 * verification below fails red.
 */
class McpHandleFailureTimerTest {

    /** The context key the dispatcher stores the whole-request timer id under. */
    private static final long TIMER_ID = 4_242L;

    @Test
    @DisplayName("handleFailure cancels the live timeout timer when the response already ended")
    void shouldCancelTimerWhenResponseAlreadyEnded() {
        Vertx vertx = mock(Vertx.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        RoutingContext context = mock(RoutingContext.class);
        when(response.ended()).thenReturn(true);
        when(context.response()).thenReturn(response);
        when(context.vertx()).thenReturn(vertx);
        // The dispatcher reads the stored timer id through the single internal timer key; the early
        // return must then cancel exactly that timer.
        when(context.<Long>get(anyString())).thenReturn(TIMER_ID);

        McpRequestDispatcher dispatcher =
                new McpRequestDispatcher(McpServerConfig.defaults(), mock(SecurityRuntime.class), Set.of(), Set.of());

        dispatcher.handleFailure(context);

        // DECISIVE: the already-ended early return cancels the whole-request timer instead of leaving
        // it live. Pre-fix this was never called, so the verification failed red.
        verify(vertx).cancelTimer(TIMER_ID);
    }
}
