// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import dev.vertique.http.client.CallOptions;
import dev.vertique.http.client.HttpClient;
import dev.vertique.http.client.HttpClientBuilder;
import dev.vertique.resilience.BackoffStrategy;
import dev.vertique.resilience.ResiliencePolicyOverrides;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.TimeoutOverride;
import dev.vertique.resilience.annotation.Resilient;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import dev.vertique.rest.client.exception.RestClientTimeoutException;
import dev.vertique.rest.client.interceptor.RestClientAttemptCompletion;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.PoolOptions;
import io.vertx.junit5.VertxExtension;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;

/**
 * An attempt that reaches its resolved timeout resets the exchange and releases the connection,
 * notifies {@code onAttemptCompleted} once with a raw {@link TimeoutException}, and fails the call
 * with {@link RestClientTimeoutException}; a method whose timeout is disabled is never reset.
 * Servers run on dynamic ports; the hung server accepts a request and never answers.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RestClientTimeoutResetIT {

    private static final long DEADLINE_MS = 300;
    private static final long AWAIT_MS = 10_000;
    private static final long MARGIN_MS = 1_000;
    private static final String NO_TIMEOUT_POLICY = "no-timeout";

    // --- Test client interfaces ---

    @RestClient(name = "timeout-reset-plain")
    interface Plain {
        @GET
        @Path("/hang")
        Future<HttpClientResponse> hang();
    }

    @RestClient(name = "timeout-reset-retrying")
    interface Retrying {
        @GET
        @Path("/hang")
        @Retry(maxRetries = 2, backoff = NoDelayBackoff.class)
        Future<HttpClientResponse> hang();
    }

    @RestClient(name = "timeout-reset-mixed")
    interface Mixed {
        @GET
        @Path("/timed")
        Future<HttpClientResponse> timed();

        @GET
        @Path("/untimed")
        @Resilient(policy = NO_TIMEOUT_POLICY)
        Future<HttpClientResponse> untimed();
    }

    /** No delay between retries, so the test stays fast. */
    public static class NoDelayBackoff implements BackoffStrategy {
        /** Creates the strategy. */
        public NoDelayBackoff() {}

        @Override
        public long delay(int retryCount) {
            return 0L;
        }
    }

    // --- Fixtures ---

    /** Records every attempt completion. */
    static final class AttemptRecorder implements RestClientInterceptor {
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<Integer> statuses = new CopyOnWriteArrayList<>();

        @Override
        public void onAttemptCompleted(RestClientRequestContext request, RestClientAttemptCompletion completion) {
            if (completion.error() != null) {
                errors.add(completion.error());
            } else {
                statuses.add(completion.response().statusCode());
            }
        }
    }

    /** A server whose handler is supplied, counting requests and connection closes. */
    static final class Origin {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger closedConnections = new AtomicInteger();

        Origin(HttpServer server) {
            this.server = server;
        }

        String base() {
            return "http://127.0.0.1:" + server.actualPort();
        }
    }

    /** Wraps a governed client and records the read timeout each call's options carry. */
    private static HttpClient optionsRecordingClient(HttpClient delegate, List<Long> readTimeouts) {
        HttpClient client = mock(HttpClient.class, AdditionalAnswers.delegatesTo(delegate));
        doAnswer(invocation -> {
                    readTimeouts.add(readTimeoutOf(invocation.getArgument(0)));
                    return delegate.execute(invocation.getArgument(0), invocation.getArgument(1));
                })
                .when(client)
                .execute(any(CallOptions.class), any());
        return client;
    }

    /**
     * Reads the attempt timeout the options carry: milliseconds, {@code 0} for none, {@code null}
     * when the client's own default applies. The options expose no reader, so this reads the
     * package-private accessor reflectively.
     */
    private static Long readTimeoutOf(CallOptions options) {
        try {
            Method accessor = CallOptions.class.getDeclaredMethod("readTimeoutMs");
            accessor.setAccessible(true);
            return (Long) accessor.invoke(options);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read the options' read timeout", e);
        }
    }

    private final List<Origin> origins = new ArrayList<>();
    private final List<RestClientBuilder> builders = new ArrayList<>();
    private Vertx vertx;

    @BeforeEach
    void setUp(Vertx injected) {
        this.vertx = injected;
    }

    @AfterEach
    void tearDown() {
        for (RestClientBuilder builder : builders) {
            try {
                await(builder.close());
            } catch (Throwable ignored) {
                // best effort
            }
        }
        for (Origin origin : origins) {
            try {
                await(origin.server.close());
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    private Origin origin(java.util.function.BiConsumer<Origin, io.vertx.core.http.HttpServerRequest> handler) {
        HttpServer server = vertx.createHttpServer();
        Origin origin = new Origin(server);
        server.connectionHandler(connection -> {
            origin.connections.incrementAndGet();
            connection.closeHandler(v -> origin.closedConnections.incrementAndGet());
        });
        server.requestHandler(request -> {
            origin.requests.incrementAndGet();
            handler.accept(origin, request);
        });
        await(server.listen(0, "127.0.0.1"));
        origins.add(origin);
        return origin;
    }

    private Origin hungOrigin() {
        return origin((o, request) -> {
            // never answers
        });
    }

    private RestClientBuilder builder(Origin origin) {
        RestClientBuilder builder =
                RestClientBuilder.create(vertx).baseUrl(origin.base()).readTimeout(DEADLINE_MS, TimeUnit.MILLISECONDS);
        builders.add(builder);
        return builder;
    }

    private static void awaitTrue(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + what, e);
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("future failed", e.getCause());
        } catch (Exception e) {
            throw new AssertionError("future did not complete", e);
        }
    }

    private static Throwable failureOf(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new AssertionError("future did not complete", e);
        }
        throw new AssertionError("expected the call to fail");
    }

    /**
     * A governed client builder whose default read timeout is 200 ms and stays so: the REST builder's
     * own call that turns the client default off is ignored, so only the call's options can lift it.
     */
    private static HttpClientBuilder clientWithFixedDefaultTimeout(Vertx vx) {
        HttpClientBuilder governed = spy(HttpClientBuilder.create(vx));
        governed.readTimeout(200, TimeUnit.MILLISECONDS);
        doAnswer(invocation -> invocation.getMock()).when(governed).readTimeout(anyLong(), any(TimeUnit.class));
        return governed;
    }

    private static ResiliencePolicyRegistry noTimeoutRegistry() {
        ResiliencePolicyOverrides disabled = new ResiliencePolicyOverrides(
                Optional.of(new TimeoutOverride(Optional.of(false), java.util.OptionalLong.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        return name -> {
            if (NO_TIMEOUT_POLICY.equals(name)) {
                return disabled;
            }
            throw new dev.vertique.core.exception.ConfigurationException(
                    "resilience.policies." + name + " is not defined");
        };
    }

    // --- Proofs ---

    /** Proves AC-048. */
    @Test
    @DisplayName("A hung server's attempt is notified at the deadline and the connection released")
    void hungServerAttemptIsNotifiedAtTheDeadlineAndTheConnectionReleased() {
        // Given a server that accepts the request and never answers, and a method with a 300 ms timeout
        Origin origin = hungOrigin();
        AttemptRecorder recorder = new AttemptRecorder();
        RestClientBuilder builder = builder(origin).register(recorder);
        Plain client = builder.build(Plain.class);

        // When the call runs into its timeout
        Throwable failure = failureOf(client.hang());

        // Then the call fails with RestClientTimeoutException
        assertThat(failure)
                .isInstanceOf(RestClientTimeoutException.class)
                .hasCauseInstanceOf(ResilienceTimeoutException.class);

        // And the attempt is notified once with a raw TimeoutException
        awaitTrue("the attempt notification", () -> !recorder.errors.isEmpty());
        assertThat(recorder.errors).hasSize(1);
        assertThat(recorder.errors.get(0))
                .isInstanceOf(TimeoutException.class)
                .isNotInstanceOf(ResilienceTimeoutException.class);
        assertThat(recorder.statuses).isEmpty();

        // And the server sees the connection close: the exchange was reset and the connection released
        awaitTrue("the server to see the connection close", () -> origin.closedConnections.get() >= 1);
        awaitTrue("the server to see the request", () -> origin.requests.get() >= 1);
        assertThat(origin.requests.get()).isEqualTo(1);
    }

    /** Proves AC-048. */
    @Test
    @DisplayName("An attempt waiting for a pooled connection is notified with TimeoutException at the deadline")
    void attemptWaitingForAPooledConnectionIsNotifiedWithTimeoutAtTheDeadline() {
        // Given a client with one pooled connection, held by a call whose timeout is disabled
        Origin origin = hungOrigin();
        AttemptRecorder recorder = new AttemptRecorder();
        RestClientBuilder builder = new RestClientBuilder(vertx, noTimeoutRegistry())
                .baseUrl(origin.base())
                .readTimeout(DEADLINE_MS, TimeUnit.MILLISECONDS)
                .poolOptions(new PoolOptions().setHttp1MaxSize(1))
                .register(recorder);
        builders.add(builder);
        Mixed client = builder.build(Mixed.class);
        client.untimed();
        awaitTrue("the held request to reach the server", () -> origin.requests.get() >= 1);

        // When a second call with a 300 ms timeout waits for the connection
        long start = System.nanoTime();
        Future<HttpClientResponse> waiting = client.timed();

        // Then its attempt is notified once with a raw TimeoutException within the deadline plus margin
        awaitTrue("the waiting attempt notification", () -> !recorder.errors.isEmpty());
        long notifiedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(notifiedMs).isLessThan(DEADLINE_MS + MARGIN_MS);
        Throwable failure = failureOf(waiting);
        assertThat(failure)
                .isInstanceOf(RestClientTimeoutException.class)
                .hasCauseInstanceOf(ResilienceTimeoutException.class);
        assertThat(recorder.errors).hasSize(1);
        assertThat(recorder.errors.get(0))
                .isExactlyInstanceOf(TimeoutException.class)
                .isNotInstanceOf(ResilienceTimeoutException.class);
        // And no second notification arrives later, and the waiting request never reached the server
        assertThat(origin.requests.get()).isEqualTo(1);
        assertThat(recorder.errors).hasSize(1);
    }

    /** Supports FR-019. */
    @Test
    @DisplayName("A timed-out GET is retried with the same eligibility and fails with RestClientTimeoutException")
    void timedOutGetIsRetriedWithTheSameEligibilityAndFailsWithRestClientTimeoutException() {
        // Given a hung server and an idempotent GET with two retries
        Origin origin = hungOrigin();
        AttemptRecorder recorder = new AttemptRecorder();
        RestClientBuilder builder = builder(origin).register(recorder);
        Retrying client = builder.build(Retrying.class);

        // When the call runs
        Throwable failure = failureOf(client.hang());

        // Then every attempt reached the server and the call fails with RestClientTimeoutException
        assertThat(failure)
                .isInstanceOf(RestClientTimeoutException.class)
                .hasCauseInstanceOf(ResilienceTimeoutException.class);
        awaitTrue("the server to see three requests", () -> origin.requests.get() >= 3);
        assertThat(origin.requests.get()).isEqualTo(3);

        // And every attempt is notified, each with a raw TimeoutException (the notification may
        // follow the retry decision)
        awaitTrue("three attempt notifications", () -> recorder.errors.size() >= 3);
        assertThat(recorder.errors).hasSize(3).allSatisfy(error -> assertThat(error)
                .isInstanceOf(TimeoutException.class)
                .isNotInstanceOf(ResilienceTimeoutException.class));
    }

    /** Proves AC-084. */
    @Test
    @DisplayName("A method whose timeout is disabled is never reset")
    void aMethodWhoseTimeoutIsDisabledIsNeverReset() {
        // Given a governed client with a 200 ms default read timeout, a method whose timeout is
        // disabled by its named policy, and a server that answers after 500 ms
        Origin origin = origin((o, request) ->
                vertx.setTimer(500, id -> request.response().setStatusCode(200).end("late")));
        AttemptRecorder recorder = new AttemptRecorder();
        RestClientBuilder builder = new RestClientBuilder(
                        vertx, noTimeoutRegistry(), RestClientTimeoutResetIT::clientWithFixedDefaultTimeout)
                .baseUrl(origin.base())
                .register(recorder);
        builders.add(builder);
        Mixed client = builder.build(Mixed.class);

        // When the untimed method is called
        HttpClientResponse response = await(client.untimed());

        // Then the client's default never applies to it and the late answer arrives
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.bodyAsString()).isEqualTo("late");
        assertThat(recorder.errors).isEmpty();
        assertThat(recorder.statuses).containsExactly(200);
    }

    /** Supports FR-019. */
    @Test
    @DisplayName("The dispatcher passes the resolved timeout as the read timeout and no read timeout when disabled")
    void dispatcherPassesTheResolvedTimeoutAsTheReadTimeoutAndNoReadTimeoutWhenDisabled() {
        // Given a governed client observed through the transport seam, a 300 ms builder default,
        // one method on it and one whose timeout is disabled
        Origin origin =
                origin((o, request) -> request.response().setStatusCode(200).end("ok"));
        List<Long> readTimeouts = Collections.synchronizedList(new ArrayList<>());
        RestClientBuilder builder = new RestClientBuilder(vertx, noTimeoutRegistry(), vx -> {
                    HttpClientBuilder governed = spy(HttpClientBuilder.create(vx));
                    doAnswer(invocation ->
                                    optionsRecordingClient((HttpClient) invocation.callRealMethod(), readTimeouts))
                            .when(governed)
                            .build();
                    return governed;
                })
                .baseUrl(origin.base())
                .readTimeout(DEADLINE_MS, TimeUnit.MILLISECONDS);
        builders.add(builder);
        Mixed client = builder.build(Mixed.class);

        // When each method is called once
        await(client.timed());
        assertThat(readTimeouts).containsExactly(DEADLINE_MS);
        await(client.untimed());

        // Then the timed method carried its resolved timeout and the untimed one noReadTimeout (0)
        assertThat(readTimeouts).containsExactly(DEADLINE_MS, 0L);
    }
}
