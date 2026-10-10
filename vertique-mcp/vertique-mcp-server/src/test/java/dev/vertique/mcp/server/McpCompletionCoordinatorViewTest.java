// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.payload.PayloadSource;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestView;
import dev.vertique.mcp.lifecycle.McpToolOutput;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The request-view state of {@link McpCompletionCoordinator}: what each completion listener is handed,
 * when a tool output becomes visible, what a bind after completion does, and what is cleared.
 *
 * <p>Every call into the coordinator runs on its owning Vert.x context, as the write path does, and
 * completions are driven through the two-phase write ({@code beginWrite}, then {@code finishWrite}),
 * so listeners run synchronously inside {@code finishWrite} and nothing here waits on wall time.
 */
class McpCompletionCoordinatorViewTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-21T00:00:00Z");
    private static final Instant TERMINAL_AT = STARTED_AT.plusMillis(10);
    private static final Instant COMPLETED_AT = STARTED_AT.plusMillis(20);
    private static final long WAIT_SECONDS = 5;

    private final Vertx vertx = Vertx.vertx();
    private final Context context = vertx.getOrCreateContext();

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("every listener receives its own view instance over the same bound facts")
    void shouldHandEachListenerItsOwnViewOverTheSameFacts() throws Exception {
        // Given: a coordinator with two listeners and a bound request, id and response.
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();
        McpCompletionCoordinator coordinator = coordinator(Set.of(first, second));

        // When: the request completes.
        complete(coordinator, () -> {
            coordinator.bindRequest(Map.of("x-a", List.of("1")), bytes("request-bytes"), "application/json");
            coordinator.bindJsonRpcId("17");
            coordinator.bindResponse(bytes("response-bytes"), Map.of("content-type", List.of("text/plain")));
        });

        // Then: each listener ran once, each was handed a distinct view, and both report the same facts.
        assertThat(first.views).hasSize(1);
        assertThat(second.views).hasSize(1);
        assertThat(first.views.get(0))
                .as("the view each listener receives is a distinct instance")
                .isNotSameAs(second.views.get(0));
        for (RecordingListener listener : List.of(first, second)) {
            Snapshot snapshot = listener.snapshots.get(0);
            assertThat(snapshot.jsonRpcRequestId()).contains("17");
            assertThat(snapshot.requestBody()).isEqualTo("request-bytes");
            assertThat(snapshot.responseBody()).isEqualTo("response-bytes");
            assertThat(snapshot.requestHeaders()).isEqualTo(Map.of("x-a", List.of("1")));
        }
    }

    @Test
    @DisplayName("a bind after the completion was emitted changes no listener's view")
    void shouldIgnoreEveryBindAfterTheCompletionWasEmitted() throws Exception {
        // Given: two symmetric listeners that, inside their callbacks (the completion is already
        // emitted), bind a different value for every fact; the listener set is unordered, so either
        // one runs second and would observe a late bind the first one made.
        McpCompletionCoordinator[] holder = new McpCompletionCoordinator[1];
        McpToolInvocationContext lateContext = toolContext("late.tool");
        Runnable lateBinds = () -> {
            McpCompletionCoordinator late = holder[0];
            late.bindRequest(Map.of("late", List.of("header")), bytes("late-request"), "text/plain");
            late.bindJsonRpcId("late-id");
            late.bindToolInput(lateContext, Map.of("late", "input"));
            late.armToolOutput(Map.of("late", "output"));
            late.promoteToolOutput(true);
            late.bindResponse(bytes("late-response"), Map.of("late", List.of("header")));
        };
        RecordingListener first = new RecordingListener(lateBinds);
        RecordingListener second = new RecordingListener(lateBinds);
        McpCompletionCoordinator coordinator = coordinator(Set.of(first, second));
        holder[0] = coordinator;
        McpToolInvocationContext earlyContext = toolContext("early.tool");

        // When: the request completes.
        complete(coordinator, () -> {
            coordinator.bindRequest(Map.of("early", List.of("header")), bytes("early-request"), "application/json");
            coordinator.bindJsonRpcId("early-id");
            coordinator.bindToolInput(earlyContext, Map.of("early", "input"));
            coordinator.bindResponse(bytes("early-response"), Map.of("early", List.of("header")));
        });

        // Then (DECISIVE): both listeners ran their late binds, and both still report the early facts.
        assertThat(first.lateBindsRun).as("first listener's late binds ran").isTrue();
        assertThat(second.lateBindsRun).as("second listener's late binds ran").isTrue();
        for (RecordingListener listener : List.of(first, second)) {
            Snapshot snapshot = listener.snapshots.get(0);
            assertThat(snapshot.jsonRpcRequestId()).contains("early-id");
            assertThat(snapshot.requestBody()).isEqualTo("early-request");
            assertThat(snapshot.requestHeaders()).isEqualTo(Map.of("early", List.of("header")));
            assertThat(snapshot.responseBody()).isEqualTo("early-response");
            assertThat(snapshot.responseHeaders()).isEqualTo(Map.of("early", List.of("header")));
            assertThat(snapshot.toolInput()).contains(Map.of("early", "input"));
            assertThat(snapshot.toolContext().orElseThrow().tool().name()).isEqualTo("early.tool");
            assertThat(snapshot.toolOutput())
                    .as("no tool output was armed before completion")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("an armed tool output is dropped when the write did not carry it")
    void shouldReportNoToolOutputWhenTheWriteDidNotCarryTheArmedResult() throws Exception {
        // Given: a result armed for a write that a bounded error then replaced.
        RecordingListener listener = new RecordingListener();
        McpCompletionCoordinator coordinator = coordinator(Set.of(listener));

        // When: the write is reported as not carrying the result, and the request completes.
        complete(coordinator, () -> {
            coordinator.armToolOutput(Map.of("v", 1));
            coordinator.promoteToolOutput(false);
        });

        // Then: the listener sees no tool output.
        assertThat(listener.snapshots.get(0).toolOutput()).isEmpty();
    }

    @Test
    @DisplayName("an armed tool output becomes visible, read-only, when the write carries it")
    void shouldReportTheArmedToolOutputWhenTheWriteCarriesIt() throws Exception {
        // Given: a structured result armed for the terminal write.
        RecordingListener listener = new RecordingListener();
        McpCompletionCoordinator coordinator = coordinator(Set.of(listener));

        // When: the write is reported as carrying the result, and the request completes.
        complete(coordinator, () -> {
            coordinator.armToolOutput(new java.util.LinkedHashMap<>(Map.of("v", 1)));
            coordinator.promoteToolOutput(true);
        });

        // Then: the listener sees the armed value, and cannot write to it.
        Optional<McpToolOutput> output = listener.snapshots.get(0).toolOutput();
        assertThat(output).isPresent();
        Object value = output.orElseThrow().structuredContent().orElseThrow();
        assertThat(value).isEqualTo(Map.of("v", 1));
        @SuppressWarnings("unchecked")
        Map<String, Object> asMap = (Map<String, Object>) value;
        assertThatThrownBy(() -> asMap.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a carried result with no structured content is a present output with nothing in it")
    void shouldReportAPresentOutputWithNoStructuredContentForANullResult() throws Exception {
        // Given: a result with no structured content armed for the terminal write.
        RecordingListener listener = new RecordingListener();
        McpCompletionCoordinator coordinator = coordinator(Set.of(listener));

        // When: the write carries it.
        complete(coordinator, () -> {
            coordinator.armToolOutput(null);
            coordinator.promoteToolOutput(true);
        });

        // Then: the output is present and its structured content is empty.
        Optional<McpToolOutput> output = listener.snapshots.get(0).toolOutput();
        assertThat(output).isPresent();
        assertThat(output.orElseThrow().structuredContent()).isEmpty();
    }

    @Test
    @DisplayName("nothing armed means no tool output, even when the write is reported as carrying one")
    void shouldReportNoToolOutputWhenNothingWasArmed() throws Exception {
        // Given: no result armed.
        RecordingListener listener = new RecordingListener();
        McpCompletionCoordinator coordinator = coordinator(Set.of(listener));

        // When: a write is reported as carrying a result anyway.
        complete(coordinator, () -> coordinator.promoteToolOutput(true));

        // Then: the listener sees no tool output.
        assertThat(listener.snapshots.get(0).toolOutput()).isEmpty();
    }

    @Test
    @DisplayName("the coordinator drops its bound request once the completion is published")
    void shouldClearTheBoundRequestOnceTheCompletionIsPublished() throws Exception {
        // Given: a coordinator that bound a request body.
        RecordingListener listener = new RecordingListener();
        McpCompletionCoordinator coordinator = coordinator(Set.of(listener));
        byte[] body = bytes("request-bytes");
        CompletableFuture<byte[]> beforeCompletion = new CompletableFuture<>();

        // When: the request completes, and a bind is attempted afterwards.
        complete(coordinator, () -> {
            coordinator.bindRequest(Map.of(), body, null);
            beforeCompletion.complete(coordinator.boundRequestBody());
        });
        CompletableFuture<byte[]> afterCompletion = new CompletableFuture<>();
        context.runOnContext(ignored -> {
            coordinator.bindRequest(Map.of(), bytes("late"), null);
            afterCompletion.complete(coordinator.boundRequestBody());
        });

        // Then: the body was held until completion, and is gone and not re-bindable afterwards.
        assertThat(beforeCompletion.get(WAIT_SECONDS, TimeUnit.SECONDS)).isSameAs(body);
        assertThat(afterCompletion.get(WAIT_SECONDS, TimeUnit.SECONDS)).isNull();
        assertThat(listener.snapshots.get(0).requestBody()).isEqualTo("request-bytes");
    }

    // --- harness ---

    private McpCompletionCoordinator coordinator(Set<McpRequestCompletedListener> listeners) {
        return new McpCompletionCoordinator(
                context,
                Set.<McpRequestLifecycleObserver>of(),
                new LinkedHashSet<>(listeners),
                STARTED_AT,
                () -> COMPLETED_AT);
    }

    /** Runs {@code setup}, then the two-phase successful write, all on the owning context. */
    private void complete(McpCompletionCoordinator coordinator, Runnable setup) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        context.runOnContext(ignored -> {
            try {
                setup.run();
                assertThat(coordinator.beginWrite(successTerminal())).isTrue();
                coordinator.finishWrite(McpTransportOutcome.WRITTEN, true, COMPLETED_AT);
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        });
        done.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static McpToolInvocationContext toolContext(String name) {
        McpToolDescriptor descriptor = new McpToolDescriptor(
                name,
                null,
                "View test tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        return new McpToolInvocationContext(
                new McpRequestContext(
                        McpMethod.TOOLS_CALL, SecurityContexts.unauthenticated(SecurityIdentity.anonymous()), null),
                descriptor);
    }

    private static McpRequestTerminalEvent successTerminal() {
        return McpRequestTerminalEvent.success(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.SERVER_DISCOVER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                200,
                null,
                null,
                null,
                null,
                RequestOrigin.unknown());
    }

    /** Everything a view reported, read inside the callback. */
    private record Snapshot(
            Optional<String> jsonRpcRequestId,
            Map<String, List<String>> requestHeaders,
            String requestBody,
            Map<String, List<String>> responseHeaders,
            String responseBody,
            Optional<McpToolInvocationContext> toolContext,
            Optional<Map<String, Object>> toolInput,
            Optional<McpToolOutput> toolOutput) {}

    /** Reads the whole view inside the callback, then optionally runs {@code afterRead}. */
    private static final class RecordingListener implements McpRequestCompletedListener {
        private final List<McpRequestView> views = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<Snapshot> snapshots = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final Runnable afterRead;
        private volatile boolean lateBindsRun;

        RecordingListener() {
            this(null);
        }

        RecordingListener(Runnable afterRead) {
            this.afterRead = afterRead;
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {}

        @Override
        public void onCompleted(McpRequestCompletedEvent event, McpRequestView view) {
            views.add(view);
            snapshots.add(new Snapshot(
                    view.jsonRpcRequestId(),
                    view.requestHeaders(),
                    textOf(view.requestBody()),
                    view.responseHeaders(),
                    textOf(view.responseBody()),
                    view.toolContext(),
                    view.toolInput(),
                    view.toolOutput()));
            if (afterRead != null) {
                afterRead.run();
                lateBindsRun = true;
            }
        }

        private static String textOf(PayloadSource source) {
            return source.bufferedView().map(Buffer::toString).orElse("");
        }
    }
}
