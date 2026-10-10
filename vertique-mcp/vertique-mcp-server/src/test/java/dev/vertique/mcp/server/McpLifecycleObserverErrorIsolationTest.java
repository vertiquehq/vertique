// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static dev.vertique.mcp.server.McpRequestDispatcher.COMPLETION_COORDINATOR_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRawEvidenceObservation;
import dev.vertique.mcp.lifecycle.McpRequestAdmissionEvidence;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpResponseEvidence;
import dev.vertique.mcp.lifecycle.McpToolInputObservation;
import dev.vertique.mcp.lifecycle.McpToolOutputObservation;
import dev.vertique.mcp.lifecycle.McpToolValueObservation;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Proves that an {@link AssertionError} or a {@link LinkageError} thrown by a lifecycle observer, by one
 * of its sessions, or by a completion listener is isolated like the exceptions they throw: the request
 * settles as it would without the failing party, and every other session and listener still receives
 * every callback.
 *
 * <p>The matrix drives one {@link McpCompletionCoordinator} through every callback the coordinator
 * makes, with the failing observer first in a {@link LinkedHashSet}, so the healthy session only sees
 * its callbacks when each dispatch loop survived. The reporting proof builds the dispatcher and runs
 * two requests through {@link McpRequestDispatcher#begin}, because how often a failure is reported is
 * decided across requests, not inside one.
 */
class McpLifecycleObserverErrorIsolationTest {

    private static final String OPEN = "open";
    private static final String REQUEST_ADMITTED = "onRequestAdmitted";
    private static final String TOOL_INPUT = "onToolInput";
    private static final String TOOL_OUTPUT = "onToolOutput";
    private static final String TERMINAL = "onTerminal";
    private static final String RESPONSE_WRITTEN = "onResponseWritten";
    private static final String COMPLETED = "onCompleted";
    private static final String LISTENER_COMPLETED = "listener.onCompleted";

    /** Every session callback, in the order {@link #driveOneRequest} makes the coordinator publish them. */
    private static final List<String> SESSION_CALLBACKS =
            List.of(REQUEST_ADMITTED, TOOL_INPUT, TOOL_OUTPUT, TERMINAL, RESPONSE_WRITTEN, COMPLETED);

    private static final Instant STARTED_AT = Instant.parse("2026-08-21T00:00:00Z");
    private static final Instant TERMINAL_AT = STARTED_AT.plusMillis(10);
    private static final Instant COMPLETED_AT = STARTED_AT.plusMillis(20);

    private static final long WAIT_SECONDS = 5;
    private static final String PROTOCOL_VERSION = "2026-07-28";

    private final Vertx vertx = Vertx.vertx();

    /** Closes the owned {@link Vertx}, waiting for its teardown to settle. */
    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static Stream<Arguments> rows() {
        return Stream.of(
                        OPEN,
                        REQUEST_ADMITTED,
                        TOOL_INPUT,
                        TOOL_OUTPUT,
                        TERMINAL,
                        RESPONSE_WRITTEN,
                        COMPLETED,
                        LISTENER_COMPLETED)
                .flatMap(callback -> Stream.of(
                        Arguments.of(callback, AssertionError.class), Arguments.of(callback, NoSuchMethodError.class)));
    }

    @ParameterizedTest(name = "{1} from {0}")
    @MethodSource("rows")
    @DisplayName("an AssertionError or LinkageError from any lifecycle callback leaves the request and the "
            + "other observers unaffected")
    void shouldIsolateAnErrorFromEveryLifecycleCallback(String callback, Class<? extends Error> errorType)
            throws Exception {
        Error thrown = errorType == AssertionError.class
                ? new AssertionError("deliberate assertion failure")
                : new NoSuchMethodError("deliberate linkage failure");
        Context context = vertx.getOrCreateContext();
        List<String> reached = new CopyOnWriteArrayList<>();
        List<String> healthyLog = new CopyOnWriteArrayList<>();
        List<String> listenerLog = new CopyOnWriteArrayList<>();

        ThrowingSession throwingSession = new ThrowingSession(callback, thrown, reached);
        McpRequestLifecycleObserver throwingObserver = startedAt -> {
            if (OPEN.equals(callback)) {
                reached.add(OPEN);
                throw thrown;
            }
            return throwingSession;
        };
        McpRequestLifecycleObserver healthyObserver = startedAt -> new RecordingSession(healthyLog);
        Set<McpRequestLifecycleObserver> observers = new LinkedHashSet<>(List.of(throwingObserver, healthyObserver));

        McpRequestCompletedListener healthyListener = event -> listenerLog.add(COMPLETED);
        McpRequestCompletedListener throwingListener = event -> {
            reached.add(LISTENER_COMPLETED);
            throw thrown;
        };
        Set<McpRequestCompletedListener> listeners = LISTENER_COMPLETED.equals(callback)
                ? Set.of(throwingListener, healthyListener)
                : Set.of(healthyListener);

        Throwable escaped = driveOneRequest(context, observers, listeners);

        assertThat(escaped)
                .as("nothing thrown by a lifecycle callback may escape the coordinator")
                .isNull();
        assertThat(reached)
                .as("the failing callback must genuinely have been reached, or this proof is vacuous")
                .containsExactly(callback);
        assertThat(healthyLog)
                .as("the session opened after the failing one must still receive every callback, in order")
                .containsExactlyElementsOf(SESSION_CALLBACKS);
        assertThat(listenerLog)
                .as("the completion must still reach the healthy listener exactly once")
                .containsExactly(COMPLETED);
    }

    @Test
    @DisplayName("a LinkageError is logged at ERROR once per observer class and callback across requests, an "
            + "AssertionError at WARN for every request")
    void shouldReportALinkageErrorOnceAcrossRequestsAndAnAssertionErrorEveryTime() throws Exception {
        Logger coordinatorLogger = (Logger) LoggerFactory.getLogger(McpCompletionCoordinator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(coordinatorLogger.getLoggerContext());
        appender.start();
        coordinatorLogger.addAppender(appender);
        try {
            Context context = vertx.getOrCreateContext();
            CountingObserver healthy = new CountingObserver();
            McpRequestDispatcher dispatcher = dispatcher(Set.of(
                    new UnusableOpenObserver(), new AssertingOpenObserver(), new UnusableTerminalObserver(), healthy));

            for (int request = 0; request < 2; request++) {
                RoutingContext routingContext = mockRoutingContext(context, toolsCallBody());
                new RequestContextLifecycle().handle(routingContext);
                assertThatCode(() -> dispatcher.begin(routingContext))
                        .as("an observer's Error must not abort the request before it has a coordinator")
                        .doesNotThrowAnyException();
                McpCompletionCoordinator coordinator = routingContext.get(COMPLETION_COORDINATOR_KEY);
                assertThat(coordinator)
                        .as("begin stored the request's coordinator")
                        .isNotNull();
                coordinator.settleDisconnected(cancelledTerminal(), false);
                flush(context);
            }

            assertThat(healthy.opens())
                    .as("the healthy observer opened one observation per request")
                    .isEqualTo(2);
            assertThat(healthy.completions())
                    .as("the healthy observer saw both requests complete")
                    .isEqualTo(2);
            List<String> errors = messagesAt(appender, Level.ERROR);
            assertThat(errors)
                    .as("each unusable callback is reported once, not once per request")
                    .hasSize(2)
                    .allSatisfy(
                            report -> assertThat(report).contains("is unusable and its notifications are being lost"));
            assertThat(errors)
                    .anySatisfy(report -> assertThat(report).contains(UnusableOpenObserver.class.getName(), OPEN))
                    .anySatisfy(
                            report -> assertThat(report).contains(UnusableTerminalObserver.class.getName(), TERMINAL));
            assertThat(messagesAt(appender, Level.WARN))
                    .as("an AssertionError is logged for every request")
                    .filteredOn(report -> report.contains(AssertingOpenObserver.class.getName()))
                    .hasSize(2);
        } finally {
            coordinatorLogger.detachAppender(appender);
            appender.stop();
        }
    }

    // --- Driving ---

    /**
     * Builds a coordinator on {@code context} and publishes every callback it can make for one request,
     * as the successful-write path does.
     *
     * @param context the request-owning context
     * @param observers the lifecycle observers, in the order they are to be opened
     * @param listeners the completion listeners
     * @return whatever escaped the coordinator, or {@code null} when nothing did
     */
    private static Throwable driveOneRequest(
            Context context, Set<McpRequestLifecycleObserver> observers, Set<McpRequestCompletedListener> listeners)
            throws Exception {
        McpToolInvocationContext toolContext = McpValueObservationLeastPrivilegeTestFixture.toolContext();
        CompletableFuture<Throwable> escaped = new CompletableFuture<>();
        context.runOnContext(ignored -> {
            try {
                McpCompletionCoordinator coordinator =
                        new McpCompletionCoordinator(context, observers, listeners, STARTED_AT);
                coordinator.publishRequestAdmitted(
                        new McpRequestAdmissionEvidence(new byte[0], Map.of(), null, null, null));
                coordinator.publishToolInput(new McpToolInputObservation(toolContext, Map.of("city", "Helsinki")));
                coordinator.publishToolOutput(new McpToolOutputObservation(toolContext, Map.of("ok", true)));
                coordinator.beginWrite(successTerminal());
                coordinator.publishResponseWritten(new McpResponseEvidence(new byte[0], Map.of()));
                coordinator.finishWrite(McpTransportOutcome.WRITTEN, true, COMPLETED_AT);
                escaped.complete(null);
            } catch (Throwable thrown) {
                escaped.complete(thrown);
            }
        });
        return escaped.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** Posts and awaits a no-op task on {@code context}, so every task queued before it has run. */
    private static void flush(Context context) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        context.runOnContext(ignored -> done.complete(null));
        done.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static List<String> messagesAt(ListAppender<ILoggingEvent> appender, Level level) {
        return List.copyOf(appender.list).stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // --- Fixture construction ---

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

    private static McpRequestTerminalEvent cancelledTerminal() {
        return McpRequestTerminalEvent.cancelled(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.OTHER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                McpErrorType.TRANSPORT,
                0,
                null,
                null,
                null,
                null,
                null,
                RequestOrigin.unknown());
    }

    /** Builds a dispatcher with {@code observers} as its lifecycle observers and nothing else contributed. */
    private static McpRequestDispatcher dispatcher(Set<McpRequestLifecycleObserver> observers) {
        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
        when(securityRuntime.current()).thenReturn(anonymous);

        return new McpRequestDispatcher(
                McpServerConfig.defaults(),
                securityRuntime,
                observers,
                Set.<McpRequestCompletedListener>of(),
                Set.of(),
                Set.of(),
                HttpConfig.builder().build(),
                McpToolRegistry.build(Set.of()),
                mock(McpPolicyEnforcer.class),
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));
    }

    /** A mocked routing context whose {@code put} and {@code get} share one map, as a request's data does. */
    private static RoutingContext mockRoutingContext(Context vertxContext, JsonObject body) {
        RoutingContext context = mock(RoutingContext.class);
        io.vertx.core.Vertx contextVertx = mock(io.vertx.core.Vertx.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        RequestBody requestBody = mock(RequestBody.class);
        Map<Object, Object> attributes = new HashMap<>();

        when(context.vertx()).thenReturn(contextVertx);
        when(contextVertx.getOrCreateContext()).thenReturn(vertxContext);
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(context.body()).thenReturn(requestBody);
        when(requestBody.buffer()).thenReturn(Buffer.buffer(body.toBuffer().getBytes()));
        when(request.headers()).thenReturn(headersFor(body));
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());
        when(response.end()).thenReturn(Future.succeededFuture());
        when(response.closeHandler(any())).thenReturn(response);
        when(response.exceptionHandler(any())).thenReturn(response);
        when(context.statusCode()).thenReturn(401);
        when(context.put(anyString(), any())).thenAnswer(invocation -> {
            attributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return context;
        });
        when(context.get(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        return context;
    }

    private static MultiMap headersFor(JsonObject body) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set("MCP-Protocol-Version", PROTOCOL_VERSION);
        headers.set("Mcp-Method", body.getString("method"));
        headers.set("Mcp-Name", body.getJsonObject("params").getString("name"));
        return headers;
    }

    private static JsonObject toolsCallBody() {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", new JsonObject().put("name", "greet").put("arguments", new JsonObject()));
    }

    /** A {@link ContextHolder} that resolves nothing and discards every binding. */
    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    // --- Sessions and observers ---

    /** A session with every capability that throws {@code thrown} from the one callback named {@code failAt}. */
    private record ThrowingSession(String failAt, Error thrown, List<String> reached)
            implements McpToolValueObservation, McpRawEvidenceObservation {

        private void hit(String callback) {
            if (callback.equals(failAt)) {
                reached.add(callback);
                throw thrown;
            }
        }

        @Override
        public void onRequestAdmitted(McpRequestAdmissionEvidence evidence) {
            hit(REQUEST_ADMITTED);
        }

        @Override
        public void onResponseWritten(McpResponseEvidence evidence) {
            hit(RESPONSE_WRITTEN);
        }

        @Override
        public void onToolInput(McpToolInputObservation observation) {
            hit(TOOL_INPUT);
        }

        @Override
        public void onToolOutput(McpToolOutputObservation observation) {
            hit(TOOL_OUTPUT);
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            hit(TERMINAL);
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            hit(COMPLETED);
        }
    }

    /** A session with every capability that records the name of each callback it receives. */
    private record RecordingSession(List<String> log) implements McpToolValueObservation, McpRawEvidenceObservation {

        @Override
        public void onRequestAdmitted(McpRequestAdmissionEvidence evidence) {
            log.add(REQUEST_ADMITTED);
        }

        @Override
        public void onResponseWritten(McpResponseEvidence evidence) {
            log.add(RESPONSE_WRITTEN);
        }

        @Override
        public void onToolInput(McpToolInputObservation observation) {
            log.add(TOOL_INPUT);
        }

        @Override
        public void onToolOutput(McpToolOutputObservation observation) {
            log.add(TOOL_OUTPUT);
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            log.add(TERMINAL);
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            log.add(COMPLETED);
        }
    }

    /** An observer whose {@code open} always fails with a {@link LinkageError}. */
    private static final class UnusableOpenObserver implements McpRequestLifecycleObserver {
        @Override
        public McpRequestObservation open(Instant startedAt) {
            throw new NoSuchMethodError("deliberate linkage failure");
        }
    }

    /** An observer whose {@code open} always fails with an {@link AssertionError}. */
    private static final class AssertingOpenObserver implements McpRequestLifecycleObserver {
        @Override
        public McpRequestObservation open(Instant startedAt) {
            throw new AssertionError("deliberate assertion failure");
        }
    }

    /** An observer that is its own session and whose {@code onTerminal} always fails with a {@link LinkageError}. */
    private static final class UnusableTerminalObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        @Override
        public McpRequestObservation open(Instant startedAt) {
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            throw new NoSuchMethodError("deliberate linkage failure");
        }
    }

    /** A healthy observer that counts the observations it opens and the completions they receive. */
    private static final class CountingObserver implements McpRequestLifecycleObserver {
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            opens.incrementAndGet();
            return new McpRequestObservation() {
                @Override
                public void onCompleted(McpRequestCompletedEvent event) {
                    completions.incrementAndGet();
                }
            };
        }

        int opens() {
            return opens.get();
        }

        int completions() {
            return completions.get();
        }
    }
}
