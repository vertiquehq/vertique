// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.payload.PayloadSource;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.security.origin.RequestOrigin;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link McpRequestCompletedListener} stays source-compatible for listeners written against its
 * one-argument form, and its two-argument form delegates to that form unless a listener overrides it.
 */
class McpRequestCompletedListenerCompatibilityTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-21T00:00:00Z");

    private static McpRequestCompletedEvent event() {
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.success(
                STARTED_AT,
                STARTED_AT.plusMillis(10),
                McpMethod.SERVER_DISCOVER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                200,
                null,
                null,
                null,
                null,
                RequestOrigin.unknown());
        return McpRequestCompletedEvent.written(terminal, STARTED_AT.plusMillis(20));
    }

    @Test
    @DisplayName("a lambda written for the one-argument form receives the event from the two-argument call")
    void shouldDeliverTheEventToALambdaThroughTheTwoArgumentForm() {
        // Given: a lambda assigned to the listener type, as listeners written before the view were.
        List<McpRequestCompletedEvent> received = new ArrayList<>();
        McpRequestCompletedListener listener = received::add;
        McpRequestCompletedEvent event = event();

        // When: the server's form, with a view, is invoked.
        listener.onCompleted(event, new UntouchableView());

        // Then: the lambda received exactly that event.
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isSameAs(event);
    }

    @Test
    @DisplayName("the two-argument default delegates to the one-argument form exactly once and never reads the view")
    void shouldDelegateToTheOneArgumentFormExactlyOnce() {
        // Given: a listener that implements only the one-argument form and counts its calls.
        List<McpRequestCompletedEvent> received = new ArrayList<>();
        McpRequestCompletedListener listener = new McpRequestCompletedListener() {
            @Override
            public void onCompleted(McpRequestCompletedEvent event) {
                received.add(event);
            }
        };
        McpRequestCompletedEvent event = event();

        // When: the two-argument form is invoked with a view that fails on any access.
        listener.onCompleted(event, new UntouchableView());

        // Then: the one-argument form ran exactly once with the same event.
        assertThat(received).containsExactly(event);
    }

    @Test
    @DisplayName("a listener that overrides the two-argument form pairs it with an empty one-argument form")
    void shouldLetAListenerOverrideOnlyTheTwoArgumentForm() {
        // Given: a view-reading listener, written as the interface documents: the two-argument form
        // carries the work and the one-argument form is an empty method.
        List<McpRequestView> views = new ArrayList<>();
        List<McpRequestCompletedEvent> events = new ArrayList<>();
        McpRequestCompletedListener listener = new McpRequestCompletedListener() {
            @Override
            public void onCompleted(McpRequestCompletedEvent event) {}

            @Override
            public void onCompleted(McpRequestCompletedEvent event, McpRequestView request) {
                events.add(event);
                views.add(request);
            }
        };
        McpRequestCompletedEvent event = event();
        UntouchableView view = new UntouchableView();

        // When: the server's form is invoked.
        listener.onCompleted(event, view);

        // Then: the override received both, and the empty one-argument form did not run in its place.
        assertThat(events).containsExactly(event);
        assertThat(views).containsExactly(view);
    }

    /** A view whose every accessor fails, proving a caller or default never reads it. */
    private static final class UntouchableView implements McpRequestView {
        @Override
        public Optional<String> jsonRpcRequestId() {
            throw new AssertionError("the view must not be read");
        }

        @Override
        public Map<String, List<String>> requestHeaders() {
            throw new AssertionError("the view must not be read");
        }

        @Override
        public PayloadSource requestBody() {
            throw new AssertionError("the view must not be read");
        }

        @Override
        public Map<String, List<String>> responseHeaders() {
            throw new AssertionError("the view must not be read");
        }

        @Override
        public PayloadSource responseBody() {
            throw new AssertionError("the view must not be read");
        }

        @Override
        public Optional<McpToolInvocationContext> toolContext() {
            throw new AssertionError("the view must not be read");
        }

        @Override
        public Optional<Map<String, Object>> toolInput() {
            throw new AssertionError("the view must not be read");
        }

        @Override
        public Optional<McpToolOutput> toolOutput() {
            throw new AssertionError("the view must not be read");
        }
    }
}
