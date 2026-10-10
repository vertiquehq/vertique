// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins how {@link McpRequestDispatcher#armRequestDeadline} schedules and releases the request
 * deadline timer. The timer firing is proven against a real transport in {@code
 * McpRequestDeadlineIT}; what only a seam test can show is that every request that ends cancels its
 * timer, so a disabled deadline schedules nothing and an enabled one never leaves a timer holding
 * the routing context.
 */
class McpRequestDeadlineArmingTest {

    private static final long DEADLINE_MS = 750;

    @Test
    @DisplayName("a disabled deadline schedules no timer and registers no end handler")
    void disabledDeadlineSchedulesNothing() {
        Vertx vertx = mock(Vertx.class);
        RoutingContext context = routingContext(vertx);

        dispatcher(McpServerConfig.builder().requestDeadlineMs(0).build()).armRequestDeadline(context);

        verify(vertx, never()).setTimer(anyLong(), any());
        verify(context, never()).addEndHandler(any());
    }

    @Test
    @DisplayName("an enabled deadline schedules one timer of the configured length and cancels exactly "
            + "that timer when the response ends")
    void enabledDeadlineIsCancelledByTheEndHandler() {
        Vertx vertx = mock(Vertx.class);
        when(vertx.setTimer(eq(DEADLINE_MS), any())).thenReturn(42L);
        RoutingContext context = routingContext(vertx);

        dispatcher(McpServerConfig.builder().requestDeadlineMs(DEADLINE_MS).build())
                .armRequestDeadline(context);

        verify(vertx).setTimer(eq(DEADLINE_MS), any());
        verify(vertx, never()).cancelTimer(anyLong());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Handler<io.vertx.core.AsyncResult<Void>>> endHandler = ArgumentCaptor.forClass(Handler.class);
        verify(context).addEndHandler(endHandler.capture());

        endHandler.getValue().handle(io.vertx.core.Future.succeededFuture());

        verify(vertx).cancelTimer(42L);
    }

    private static RoutingContext routingContext(Vertx vertx) {
        RoutingContext context = mock(RoutingContext.class);
        when(context.vertx()).thenReturn(vertx);
        return context;
    }

    private static McpRequestDispatcher dispatcher(McpServerConfig config) {
        return new McpRequestDispatcher(
                config,
                mock(SecurityRuntime.class),
                Set.of(),
                Set.<McpRequestCompletedListener>of(),
                Set.of(),
                Set.of(),
                HttpConfig.builder().build(),
                McpToolRegistry.build(Set.of()),
                mock(McpPolicyEnforcer.class),
                new ContextHolder() {
                    @Override
                    public <T> Optional<T> current(Class<T> type) {
                        return Optional.empty();
                    }

                    @Override
                    public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                        return () -> {};
                    }
                },
                new CorrelationContextFactory(Optional.empty()));
    }
}
