// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P03 item 10: proves {@link McpRequestDispatcher#handleFailure(RoutingContext)}'s already-ended
 * early return is still load-bearing — falling through to the ordinary rejection path would attempt a
 * second write on a response Vert.x has already ended, which throws. This is the only remaining
 * coverage of that guard: {@code McpHandleFailureTimerTest}, which pinned an older version of the same
 * early return (cancelling a whole-request timer T007 removed), was deleted in this slice.
 *
 * <p>Per the T007 review caution: {@code HttpServerResponse#ended()} is set synchronously by {@code
 * end()} and is never mocked {@code false} after an {@code end()} call here — this test mocks {@code
 * ended()} as unconditionally {@code true} for the entire call, modeling a response that was already
 * ended by an earlier point on the request path, not a race with an in-flight {@code end()}.
 */
class McpHandleFailureAlreadyEndedTest {

    @Test
    @DisplayName("handleFailure on an already-ended response performs no write")
    void shouldPerformNoWriteWhenResponseAlreadyEnded() {
        HttpServerResponse response = mock(HttpServerResponse.class);
        RoutingContext context = mock(RoutingContext.class);
        when(response.ended()).thenReturn(true);
        when(context.response()).thenReturn(response);

        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                McpServerConfig.defaults(),
                mock(SecurityRuntime.class),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                HttpConfig.builder().build(),
                McpToolRegistry.build(Set.of()),
                mock(McpPolicyEnforcer.class));

        dispatcher.handleFailure(context);

        // DECISIVE: the early return must short-circuit before any response mutation. Against a
        // regressed dispatcher that fell through to the ordinary rejection write path, at least one of
        // these would be invoked (setStatusCode, then either end() or end(Buffer)).
        verify(response, never()).setStatusCode(anyInt());
        verify(response, never()).end();
        verify(response, never()).end(any(Buffer.class));
        // The status is never even read on the early-return path.
        verify(context, never()).statusCode();
    }
}
