// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitCondition;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import dev.vertique.http.client.config.HttpClientConfig;
import dev.vertique.http.client.exception.HttpClientClosedException;
import dev.vertique.http.client.exception.HttpExchangeCancelledException;
import dev.vertique.http.client.exception.HttpStatusRejectedException;
import dev.vertique.http.client.interceptor.HttpClientAttemptCompletion;
import dev.vertique.http.client.interceptor.HttpClientInterceptor;
import dev.vertique.http.client.interceptor.HttpClientRequestContext;
import dev.vertique.http.client.interceptor.HttpClientResponseHead;
import dev.vertique.resilience.BulkheadConfig;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResilienceDefaults;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResiliencePolicyOverrides;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.RetryOverride;
import dev.vertique.resilience.TimeoutOverride;
import dev.vertique.resilience.annotation.Bulkhead;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.config.BulkheadPolicyConfig;
import dev.vertique.resilience.config.CircuitBreakerPolicyConfig;
import dev.vertique.resilience.config.ResiliencePolicyConfig;
import dev.vertique.resilience.config.RetryPolicyConfig;
import dev.vertique.resilience.exception.BulkheadRejectedException;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.client.WebClient;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests of head-first streams: the head is delivered before any body byte, the body
 * is back-pressured to the connection, the terminal future is separate from the head, and the call
 * leaves the resilience pipeline at the head.
 *
 * <p>Every test runs against real Vert.x servers on {@code 127.0.0.1} and dynamic ports. Elapsed
 * bounds carry the margins the criteria state; waits are bounded and nothing is left open.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class HttpStreamIT {

    /** Settle time after the last expected notification, to show that no further one arrives. */
    private static final long SETTLE_MS = 300;

    private static final Buffer TEN = Buffer.buffer("0123456789");

    private static HttpClientItSupport support;

    @BeforeAll
    static void startVertx() {
        support = HttpClientItSupport.start();
    }

    @AfterEach
    void closeResources() {
        support.closeResources();
        support.assertNoBlockedThreadWarning();
    }

    @AfterAll
    static void stopVertx() {
        support.stop();
    }

    // --- fixtures: clients ---

    private HttpClient client(RecordingServer server, Consumer<HttpClientBuilder> customize) {
        HttpClientBuilder builder = HttpClientBuilder.create(support.vertx);
        builder.baseUrl(server.origin());
        customize.accept(builder);
        HttpClient client = builder.build();
        support.track(client::close);
        return client;
    }

    private HttpClient client(RecordingServer server) {
        return client(server, b -> {});
    }

    private Resilience runtime() {
        Resilience resilience = Resilience.create(support.vertx);
        support.track(resilience::close);
        return resilience;
    }

    /** The pipelines of the current test by policy name, built from the policies of {@link #resilientClient}. */
    private final Map<String, ResiliencePipeline> pipelines = new HashMap<>();

    private HttpClient resilientClient(
            RecordingServer server,
            Map<String, ResiliencePolicyOverrides> policies,
            Consumer<HttpClientBuilder> customize) {
        Resilience runtime = runtime();
        policies.forEach((name, overrides) -> {
            ResolvedResiliencePolicy resolved = runtime.policyResolver()
                    .resolve(ResilienceAnnotations.NONE, withoutTimeout(name, overrides), ResilienceDefaults.none());
            ResiliencePipeline.Builder pipeline = runtime.pipeline(name);
            resolved.retry()
                    .ifPresent(config -> pipeline.retry(r -> r.maxRetries(config.maxRetries())
                            .backoff(config.backoff())
                            .retryOn(config.retryOn())
                            .abortOn(config.abortOn())
                            .fallbackPolicy(config.fallbackPolicy())
                            .retryAfterMaxMs(config.retryAfterMaxMs())));
            resolved.circuitBreaker()
                    .ifPresent(config -> pipeline.circuitBreaker(cb -> cb.maxFailures(config.maxFailures())
                            .resetTimeout(Duration.ofMillis(config.resetTimeoutMs()))));
            resolved.bulkhead()
                    .ifPresent(config -> pipeline.bulkhead(b -> {
                        switch (config) {
                            case BulkheadConfig.Reject reject ->
                                b.maxConcurrentCalls(reject.maxConcurrentCalls())
                                        .reject();
                            case BulkheadConfig.Queue queue ->
                                b.maxConcurrentCalls(queue.maxConcurrentCalls())
                                        .queue(queue.maxQueueSize(), Duration.ofMillis(queue.queueTimeoutMs()));
                        }
                    }));
            pipelines.put(name, pipeline.build());
        });
        HttpClientBuilder builder = HttpClientBuilder.create(support.vertx).baseUrl(server.origin());
        customize.accept(builder);
        HttpClient client = builder.build();
        support.track(client::close);
        return client;
    }

    /** The options of a call that runs in the pipeline built for the policy {@code name}. */
    private CallOptions tier(String name) {
        return CallOptions.defaults().resilience(pipelines.get(name));
    }

    /** The policy completed the way an adapter needs it: no timeout concern, and retry of every failure not aborted. */
    private static ResiliencePolicyOverrides withoutTimeout(String name, ResiliencePolicyOverrides tier) {
        ResiliencePolicyRegistry registry = requested -> tier;
        ResiliencePolicyOverrides policy = registry.layer(
                new ResilienceAnnotations(
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(name)),
                ResiliencePolicyOverrides.none());
        return new ResiliencePolicyOverrides(
                Optional.of(new TimeoutOverride(Optional.of(false), OptionalLong.empty())),
                policy.retry()
                        .map(r -> new RetryOverride(
                                r.enabled(),
                                r.maxRetries(),
                                r.backoff(),
                                r.retryOn(),
                                r.abortOn(),
                                Optional.of((failure, retryCount) -> true),
                                r.retryAfterMaxMs())),
                policy.circuitBreaker(),
                policy.bulkhead());
    }

    private static ResiliencePolicyOverrides retryTier(int maxRetries, long delayMs, @Nullable Long retryAfterMaxMs) {
        return new ResiliencePolicyConfig(
                        "tier",
                        null,
                        new RetryPolicyConfig(maxRetries, delayMs, 1.0d, null, retryAfterMaxMs),
                        null,
                        null)
                .toOverrides();
    }

    private static ResiliencePolicyOverrides breakerTier(int maxFailures, long resetTimeoutMs) {
        return new ResiliencePolicyConfig(
                        "tier", null, null, new CircuitBreakerPolicyConfig(maxFailures, resetTimeoutMs), null)
                .toOverrides();
    }

    private static ResiliencePolicyOverrides bulkheadTier(int maxConcurrentCalls) {
        return new ResiliencePolicyConfig(
                        "tier",
                        null,
                        null,
                        null,
                        new BulkheadPolicyConfig(maxConcurrentCalls, Bulkhead.Mode.REJECT, null, null))
                .toOverrides();
    }

    private static Map<String, ResiliencePolicyOverrides> tiers(Object... namesAndTiers) {
        Map<String, ResiliencePolicyOverrides> map = new HashMap<>();
        for (int i = 0; i < namesAndTiers.length; i += 2) {
            map.put((String) namesAndTiers[i], (ResiliencePolicyOverrides) namesAndTiers[i + 1]);
        }
        return map;
    }

    // --- fixtures: streams ---

    /** Collects every body chunk handed to the handler. */
    private static final class Collector {
        private final Buffer bytes = Buffer.buffer();
        final AtomicInteger chunks = new AtomicInteger();
        volatile long lastByteNanos;

        void attach(HttpStream stream) {
            stream.body().handler(chunk -> {
                synchronized (this) {
                    bytes.appendBuffer(chunk);
                }
                chunks.incrementAndGet();
                lastByteNanos = System.nanoTime();
            });
        }

        synchronized String text() {
            return bytes.toString();
        }

        synchronized int length() {
            return bytes.length();
        }
    }

    /** One {@code stream} call: counts deliveries and, when asked, attaches a body handler on delivery. */
    private static final class Opened {
        final Future<HttpStream> future;
        final AtomicInteger deliveries = new AtomicInteger();
        final AtomicReference<HttpStream> stream = new AtomicReference<>();
        final Collector body = new Collector();
        volatile long headNanos;

        Opened(Future<HttpStream> future, boolean consume) {
            this.future = future;
            future.onSuccess(delivered -> {
                headNanos = System.nanoTime();
                deliveries.incrementAndGet();
                stream.set(delivered);
                if (consume) {
                    body.attach(delivered);
                }
            });
        }

        HttpStream head() {
            return await(future);
        }
    }

    private static Opened open(HttpClient client, CallOptions options, String path, boolean consume) {
        return new Opened(client.stream(options, web -> web.get(path), null), consume);
    }

    private static Opened open(HttpClient client, String path) {
        return open(client, CallOptions.defaults(), path, true);
    }

    /** Records every attempt completion the interceptor saw together with the head it saw. */
    static final class Recorder implements HttpClientInterceptor {

        record Seen(@Nullable HttpClientResponseHead response, HttpClientAttemptCompletion completion) {}

        final List<Seen> seen = new CopyOnWriteArrayList<>();

        @Override
        public void onAttemptCompleted(
                HttpClientRequestContext request,
                @Nullable HttpClientResponseHead response,
                HttpClientAttemptCompletion completion) {
            seen.add(new Seen(response, completion));
        }

        /** Waits for {@code count} completions, lets a further one arrive if it would, and returns them. */
        List<Seen> settled(int count) {
            assertThat(awaitCondition(() -> seen.size() >= count, HttpClientItSupport.WAIT_MS))
                    .as("completions reached %d, saw %d", count, seen.size())
                    .isTrue();
            sleep(SETTLE_MS);
            assertThat(seen).as("exactly %d completions", count).hasSize(count);
            return List.copyOf(seen);
        }
    }

    // --- fixtures: servers ---

    /** Writes the head and ten body bytes and keeps the response open. */
    private static void feed(HttpServerRequest request) {
        request.response()
                .setChunked(true)
                .putHeader("Content-Type", "text/plain")
                .write(TEN);
    }

    private static boolean serverReceived(RecordingServer server, String path, int count) {
        return awaitCondition(() -> server.requestsTo(path).size() >= count, HttpClientItSupport.WAIT_MS);
    }

    private static boolean serverClosedConnections(RecordingServer server, int count, long timeoutMs) {
        return awaitCondition(() -> server.closedConnections.get() >= count, timeoutMs);
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    private static void assertNativeFailure(Throwable failure) {
        assertThat(failure).isNotNull();
        assertThat(failure.getClass().getName()).doesNotStartWith("dev.vertique");
    }

    // --- AC-006 ---

    /**
     * AC-006: the head is delivered before any body byte and the terminal future completes at the
     * end. A stream is exempt from the response bound: a body above {@code maxResponseBytes} that
     * declares a larger {@code Content-Length} is delivered in full.
     */
    @Test
    @DisplayName("the head arrives before the body and the terminal future completes at the end")
    void headArrivesBeforeTheBodyAndTheTerminalFutureCompletesAtTheEnd() {
        // Given a server whose GET /s writes the head, flushes, waits 300 ms and then writes abcdef
        AtomicBoolean bodyWritten = new AtomicBoolean();
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = request.response();
            if (request.path().equals("/big")) {
                response.putHeader("Content-Length", "5000").write(Buffer.buffer("x".repeat(5000)));
                response.end();
                return;
            }
            response.setChunked(true).putHeader("Content-Type", "text/plain").writeHead();
            support.vertx.setTimer(300, id -> {
                bodyWritten.set(true);
                response.end("abcdef");
            });
        });
        HttpClient client = client(server);

        // When the caller runs stream for GET /s and records whether the server had written the body
        AtomicBoolean bodyWrittenAtHead = new AtomicBoolean(true);
        AtomicBoolean completionDoneAtHead = new AtomicBoolean(true);
        Future<HttpStream> future = client.stream(CallOptions.defaults(), web -> web.get("/s"), null);
        Collector body = new Collector();
        future.onSuccess(stream -> {
            bodyWrittenAtHead.set(bodyWritten.get());
            completionDoneAtHead.set(stream.completion().isComplete());
            body.attach(stream);
        });
        HttpStream stream = await(future);

        // Then the head carried 200 and text/plain before the server wrote any body byte
        assertThat(stream.head().statusCode()).isEqualTo(200);
        assertThat(stream.head().headers().get("Content-Type")).isEqualTo("text/plain");
        assertThat(bodyWrittenAtHead)
                .as("body written when the head was delivered")
                .isFalse();
        // And completion() was not complete at that moment
        assertThat(completionDoneAtHead).isFalse();

        // And the handler receives exactly abcdef, after which completion() succeeds
        await(stream.completion());
        assertThat(body.text()).isEqualTo("abcdef");

        // And a stream above maxResponseBytes(1024) declaring Content-Length 5000 is delivered in full
        HttpClient bounded = client(server, b -> b.maxResponseBytes(1024));
        Opened big = open(bounded, "/big");
        HttpStream bigStream = big.head();
        assertThat(bigStream.head().headers().get("Content-Length")).isEqualTo("5000");
        await(bigStream.completion());
        assertThat(big.body.length()).isEqualTo(5000);
        assertThat(big.body.text()).isEqualTo("x".repeat(5000));
    }

    // --- AC-007 ---

    /** What the flooding server did: bytes written and whether its write queue was full. */
    private static final class Flood {
        final AtomicLong written = new AtomicLong();
        final AtomicInteger drains = new AtomicInteger();
        volatile boolean full;
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        final Buffer chunk = Buffer.buffer(new byte[64 * 1024]);

        /** Writes at most this many chunks per event-loop turn, so a fast reader never holds the loop long. */
        private static final int CHUNKS_PER_TURN = 16;

        void pump(HttpServerResponse response) {
            int chunks = 0;
            while (!response.closed() && System.nanoTime() < deadlineNanos) {
                if (chunks++ == CHUNKS_PER_TURN) {
                    Vertx.currentContext().runOnContext(v -> pump(response));
                    return;
                }
                if (response.writeQueueFull()) {
                    full = true;
                    response.drainHandler(v -> {
                        full = false;
                        drains.incrementAndGet();
                        pump(response);
                    });
                    return;
                }
                response.write(chunk);
                written.addAndGet(chunk.length());
            }
            if (!response.closed()) {
                response.end();
            }
        }
    }

    /**
     * AC-007: pausing the body stops reading from the connection, so the server's writes stall with
     * its write queue full, and resuming lets the writes proceed.
     */
    @Test
    @DisplayName("pausing the body stops reading from the connection")
    void pausingTheBodyStopsReadingFromTheConnection() {
        // Given a server whose GET /flood writes 64 KiB chunks for 5 seconds and notes a full write queue
        Flood flood = new Flood();
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = request.response().setChunked(true);
            response.writeHead();
            flood.pump(response);
        });
        HttpClient client = client(server);

        // When the caller sets a handler, pauses after the first chunk and waits 1000 ms
        AtomicInteger chunks = new AtomicInteger();
        AtomicReference<Context> context = new AtomicReference<>();
        AtomicReference<ReadStream<Buffer>> bodyStream = new AtomicReference<>();
        Future<HttpStream> future = client.stream(CallOptions.defaults(), web -> web.get("/flood"), null);
        future.onSuccess(stream -> {
            context.set(Vertx.currentContext());
            ReadStream<Buffer> readStream = stream.body();
            bodyStream.set(readStream);
            readStream.handler(chunk -> {
                if (chunks.incrementAndGet() == 1) {
                    readStream.pause();
                }
            });
        });
        HttpStream stream = await(future);
        assertThat(awaitCondition(() -> chunks.get() >= 1, HttpClientItSupport.WAIT_MS))
                .as("first chunk")
                .isTrue();
        sleep(1_000);

        // Then the handler received no further chunk during the pause
        assertThat(chunks.get()).as("chunks during the pause").isEqualTo(1);
        // And the server's write queue is full and its writes are stalled
        assertThat(flood.full).as("server write queue full while paused").isTrue();
        long writtenWhenStalled = flood.written.get();
        sleep(500);
        assertThat(flood.written.get())
                .as("server bytes written during a further 500 ms")
                .isEqualTo(writtenWhenStalled);
        assertThat(chunks.get()).isEqualTo(1);

        // And after resume() the handler receives chunks again and the server's writes proceed
        context.get().runOnContext(v -> bodyStream.get().resume());
        assertThat(awaitCondition(() -> chunks.get() >= 3, HttpClientItSupport.WAIT_MS))
                .as("chunks after resume")
                .isTrue();
        assertThat(awaitCondition(() -> flood.written.get() > writtenWhenStalled, HttpClientItSupport.WAIT_MS))
                .as("server writes after resume")
                .isTrue();
        assertThat(flood.drains.get()).as("server drain events").isPositive();

        stream.cancel();
        awaitFailure(stream.completion());
    }

    // --- AC-008 ---

    /**
     * AC-008: the stream idle timeout fails the terminal future with a {@code TimeoutException} and
     * resets the exchange; every body byte restarts the timer; the same timeout configured as
     * {@code httpClient.<name>.streamIdleTimeoutMs} reaches the client's idle timer.
     */
    @Test
    @DisplayName("the stream idle timeout fails the terminal future and resets the exchange")
    void streamIdleTimeoutFailsTheTerminalFutureAndResetsTheExchange() {
        // Given a client with a 300 ms stream idle timeout and a server whose GET /idle writes the head
        // and 10 bytes and then nothing, and whose GET /drip writes one byte every 150 ms for 1200 ms
        RecordingServer server = support.server((request, body) -> {
            if (request.path().equals("/drip")) {
                HttpServerResponse response = request.response().setChunked(true);
                response.writeHead();
                AtomicInteger sent = new AtomicInteger();
                support.vertx.setPeriodic(150, id -> {
                    if (response.closed() || sent.incrementAndGet() > 8) {
                        support.vertx.cancelTimer(id);
                        if (!response.closed()) {
                            response.end();
                        }
                        return;
                    }
                    response.write("d");
                });
                return;
            }
            feed(request);
        });
        HttpClient client = client(server, b -> b.streamIdleTimeout(300, TimeUnit.MILLISECONDS));

        // When the caller opens GET /idle and consumes the body
        Opened idle = open(client, "/idle");
        HttpStream stream = idle.head();
        assertThat(awaitCondition(() -> idle.body.length() == 10, HttpClientItSupport.WAIT_MS))
                .as("10 body bytes")
                .isTrue();
        long lastByte = idle.body.lastByteNanos;
        Throwable failure = awaitFailure(stream.completion());
        long sinceLastByteMs = millisSince(lastByte);

        // Then completion() fails with a TimeoutException within 300 ms plus 500 ms of the last byte
        assertThat(failure).isExactlyInstanceOf(TimeoutException.class);
        assertThat(sinceLastByteMs).isLessThan(300 + 500);
        // And the server observes the connection closed
        assertThat(serverClosedConnections(server, 1, 1_000)).isTrue();
        // And the future returned by stream had already succeeded with the head
        assertThat(idle.deliveries.get()).isEqualTo(1);
        assertThat(stream.head().statusCode()).isEqualTo(200);

        // And a body that keeps producing bytes inside the idle window is not timed out
        Opened drip = open(client, "/drip");
        await(drip.head().completion());
        assertThat(drip.body.text()).isEqualTo("dddddddd");

        // And httpClient.<name>.streamIdleTimeoutMs configures the idle timer of a client built from it
        JsonObject entry = new JsonObject().put("streamIdleTimeoutMs", 300);
        HttpClientConfig config = HttpClientConfig.indexFromConfig(
                        new JsonObject().put("httpClient", new JsonObject().put("configured", entry)),
                        new DefaultConfigParser(DefaultConfigMapper.lenient()))
                .get("configured");
        HttpClient configured = client(server, b -> b.config(config));
        Opened viaConfig = open(configured, "/idle");
        assertThat(awaitFailure(viaConfig.head().completion())).isExactlyInstanceOf(TimeoutException.class);
    }

    // --- AC-016 ---

    /** AC-016: cancel after the head fails the terminal future and closes the connection; a second cancel is a no-op. */
    @Test
    @DisplayName("cancel after the head fails the terminal future")
    void cancelAfterTheHeadFailsTheTerminalFuture() {
        // Given a server whose GET /feed writes the head and 10 bytes and keeps the response open
        RecordingServer server = support.server((request, body) -> feed(request));
        HttpClient client = client(server);

        // When the caller receives the head and calls cancel()
        Opened opened = open(client, "/feed");
        HttpStream stream = opened.head();
        assertThat(awaitCondition(() -> opened.body.length() == 10, HttpClientItSupport.WAIT_MS))
                .isTrue();
        stream.cancel();

        // Then completion() fails with HttpExchangeCancelledException
        Throwable failure = awaitFailure(stream.completion());
        assertThat(failure).isExactlyInstanceOf(HttpExchangeCancelledException.class);
        // And the server observes the connection closed
        assertThat(serverClosedConnections(server, 1, 1_500)).isTrue();
        // And a second cancel is a no-op: it does not throw and the outcome is unchanged
        stream.cancel();
        assertThat(awaitFailure(stream.completion())).isSameAs(failure);
    }

    // --- AC-034 ---

    /** AC-034: a failure after the head is delivered through the terminal future and is never retried. */
    @Test
    @DisplayName("a failure after a streamed head is not retried")
    void failureAfterAStreamedHeadIsNotRetried() {
        // Given a tier with maxRetries 3 and a 10 ms backoff and a server whose GET /feed writes the head and
        // 10 bytes and then closes the socket
        RecordingServer server = support.server((request, body) -> request.response()
                .setChunked(true)
                .write(TEN)
                .onComplete(v -> request.connection().close()));
        HttpClient client = resilientClient(server, tiers("t", retryTier(3, 10, null)), b -> {});

        // When the caller opens stream for GET /feed with the tier
        Opened opened = open(client, tier("t"), "/feed", true);

        // Then the future returned by stream succeeds with status 200
        HttpStream stream = opened.head();
        assertThat(stream.head().statusCode()).isEqualTo(200);
        // And completion() fails with a native throwable for the closed connection, not a framework type
        assertNativeFailure(awaitFailure(stream.completion()));
        // And the server received exactly 1 request and no further attempt starts
        sleep(500);
        assertThat(server.requestsTo("/feed")).hasSize(1);
        assertThat(opened.deliveries.get()).isEqualTo(1);
    }

    // --- AC-036 ---

    /** A stream call on a server that answers {@code /late} 400 ms after it receives the request. */
    private void lateHeadRun(boolean closeTheClient) {
        // Given a tier with maxRetries 2 and a 10 ms backoff, a recording interceptor and a server whose
        // GET /late waits 400 ms and then writes the head with 10 bytes
        Recorder recorder = new Recorder();
        RecordingServer server = support.server((request, body) -> support.vertx.setTimer(400, id -> {
            if (!request.response().closed()) {
                feed(request);
            }
        }));
        HttpClient client = resilientClient(server, tiers("t", retryTier(2, 10, null)), b -> b.register(recorder));
        CancellationSignal signal = CancellationSignal.create();

        // When the caller opens stream for GET /late with a signal and cancels (or closes the client) at 200 ms
        Opened opened = open(client, tier("t").cancellation(signal), "/late", true);
        assertThat(serverReceived(server, "/late", 1)).isTrue();
        sleep(200);
        long settledAt = System.nanoTime();
        if (closeTheClient) {
            await(client.close());
        } else {
            signal.cancel();
        }

        // Then the future returned by stream fails once, with the cancel or close failure
        Throwable failure = awaitFailure(opened.future);
        assertThat(failure)
                .isExactlyInstanceOf(
                        closeTheClient ? HttpClientClosedException.class : HttpExchangeCancelledException.class);
        // And the server observes the connection closed no later than 500 ms after the cancel or close
        assertThat(serverClosedConnections(server, 1, 500)).isTrue();
        assertThat(millisSince(settledAt)).isLessThan(500 + 500);

        // And no HttpStream is ever delivered, even after the server's late head, and no retry starts
        sleep(600);
        assertThat(opened.deliveries.get()).isZero();
        assertThat(server.requestsTo("/late")).hasSize(1);
        // And onAttemptCompleted ran exactly once, with an error of the same type
        List<Recorder.Seen> seen = recorder.settled(1);
        assertThat(seen.get(0).completion().error()).isExactlyInstanceOf(failure.getClass());
    }

    /** AC-036: a head that arrives after the execution settled is reset and never delivered. */
    @Test
    @DisplayName("a late streamed head after the execution settled is reset")
    void lateStreamedHeadAfterTheExecutionSettledIsReset() {
        lateHeadRun(false);
        lateHeadRun(true);
    }

    /**
     * Runs a stream call on a busy context: the head reaches the connection while the context is blocked
     * and the call is then ended, by a cancel or by the attempt timeout, before the pipeline settles.
     */
    private void gapRun(boolean viaTimeout) throws Exception {
        // Given a tier pipeline, a recording interceptor, a server that holds its head until told and a
        // stream call made on a context
        Recorder recorder = new Recorder();
        AtomicReference<HttpServerRequest> held = new AtomicReference<>();
        RecordingServer server = support.server((request, body) -> held.set(request));
        HttpClient client = viaTimeout
                ? resilientClient(server, tiers("t", breakerTier(5, 60_000)), b -> b.register(recorder)
                        .readTimeout(100, TimeUnit.MILLISECONDS))
                : resilientClient(server, tiers("t", retryTier(2, 10, null)), b -> b.register(recorder));
        CancellationSignal signal = CancellationSignal.create();
        Context context = support.vertx.getOrCreateContext();
        AtomicReference<Opened> opened = new AtomicReference<>();
        context.runOnContext(v -> opened.set(open(client, tier("t").cancellation(signal), "/gap", viaTimeout)));
        assertThat(serverReceived(server, "/gap", 1)).isTrue();
        assertThat(awaitCondition(() -> held.get() != null, HttpClientItSupport.WAIT_MS))
                .isTrue();

        // When the context is blocked, the server writes the head, and the call is cancelled from this thread
        // or left to outlast its attempt timeout
        java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        context.runOnContext(v -> {
            blocked.countDown();
            try {
                release.await(150, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(blocked.await(HttpClientItSupport.WAIT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        held.get().response().setChunked(true).write(TEN);
        if (viaTimeout) {
            sleep(120);
        } else {
            sleep(50);
            signal.cancel();
        }
        long releasedAt = System.nanoTime();
        release.countDown();

        // Then the call ends in one of the allowed ways and the caller never sees an internal failure
        Opened call = opened.get();
        assertThat(awaitCondition(() -> call.future.isComplete(), HttpClientItSupport.WAIT_MS))
                .isTrue();
        if (viaTimeout && !call.future.failed()) {
            // A head received before the expired timer ran is accepted: the stream is delivered, its body
            // flows and completion() succeeds once the server ends the response
            held.get().response().end();
            await(call.stream.get().completion());
            assertThat(call.body.text()).isEqualTo("0123456789");
            Recorder.Seen seen = recorder.settled(1).get(0);
            assertThat(seen.completion().error()).isNull();
            assertThat(seen.completion().statusCode()).isEqualTo(200);
        } else {
            // Otherwise the stream future (or, for a cancel, the delivered stream's completion) fails with the
            // failure the caller expects: the cancel failure, or the attempt timeout
            Throwable failure = call.future.failed()
                    ? call.future.cause()
                    : awaitFailure(call.stream.get().completion());
            assertThat(failure).isNotInstanceOf(IllegalStateException.class);
            if (viaTimeout) {
                assertThat(failure).isInstanceOfAny(ResilienceTimeoutException.class, TimeoutException.class);
            } else {
                assertThat(failure).isExactlyInstanceOf(HttpExchangeCancelledException.class);
            }
            // And the interceptor was told the same failure, never an IllegalStateException
            Throwable notified = recorder.settled(1).get(0).completion().error();
            assertThat(notified).isNotNull().isNotInstanceOf(IllegalStateException.class);
            assertThat(notified).isExactlyInstanceOf(failure.getClass());
            // And the server sees the connection closed no later than 500 ms after the release
            assertThat(serverClosedConnections(server, 1, 500)).isTrue();
            assertThat(millisSince(releasedAt)).isLessThan(500 + 500);
        }
        assertThat(server.requestsTo("/gap")).hasSize(1);
    }

    /**
     * AC-036: a head that arrives while the caller's context is busy, with the call cancelled in that gap,
     * never leaves an open stream without an owner, and the caller and the interceptor see the cancel failure.
     */
    @Test
    @DisplayName("a head delivered in the gap before the pipeline settles and a cancel leave no open stream")
    void headDeliveredInTheGapBeforeTheCallSettlesIsNotLeftOpenWhenCancelled() throws Exception {
        gapRun(false);
    }

    /** AC-036: the same gap with an attempt timeout ends with the timeout or an accepted head, never an internal failure. */
    @Test
    @DisplayName("a head delivered in the gap before the pipeline settles and a timeout report the timeout")
    void headDeliveredInTheGapBeforeTheCallSettlesReportsTheAttemptTimeout() throws Exception {
        gapRun(true);
    }

    // --- AC-043 ---

    private static void assertNotifiedOnce(Recorder recorder, RecordingServer server) {
        List<Recorder.Seen> seen = recorder.settled(1);
        Recorder.Seen only = seen.get(0);
        assertThat(only.response()).as("head the interceptor saw").isNotNull();
        assertThat(only.response().statusCode()).isEqualTo(200);
        assertThat(only.completion().attemptOrdinal()).isEqualTo(1);
        assertThat(only.completion().target().scheme()).isEqualTo("http");
        assertThat(only.completion().target().host()).isEqualTo("127.0.0.1");
        assertThat(only.completion().target().port()).isEqualTo(server.port());
    }

    /** AC-043: every terminal path of a stream notifies the interceptor exactly once with the head. */
    @Test
    @DisplayName("every stream terminal path notifies exactly once")
    void everyStreamTerminalPathNotifiesExactlyOnce() {
        // Given a recording interceptor per client and servers able to produce each path
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = request.response();
            switch (request.path()) {
                case "/end" -> response.putHeader("Content-Type", "text/plain").end("abcdef");
                case "/fail" ->
                    response.setChunked(true).write(TEN).onComplete(v -> request.connection()
                            .close());
                case "/idle" -> response.setChunked(true).writeHead();
                case "/late" ->
                    support.vertx.setTimer(400, id -> {
                        if (!response.closed()) {
                            feed(request);
                        }
                    });
                default -> feed(request);
            }
        });

        // When the body ends: completion carries 200 and no error
        Recorder end = new Recorder();
        HttpClient endClient = client(server, b -> b.register(end));
        Opened ended = open(endClient, "/end");
        await(ended.head().completion());
        assertThat(ended.body.text()).isEqualTo("abcdef");
        assertNotifiedOnce(end, server);
        assertThat(end.seen.get(0).completion().statusCode()).isEqualTo(200);
        assertThat(end.seen.get(0).completion().error()).isNull();

        // When the stream fails: the error is the native closed-connection throwable
        Recorder failed = new Recorder();
        HttpClient failClient = client(server, b -> b.register(failed));
        Opened failing = open(failClient, "/fail");
        awaitFailure(failing.head().completion());
        assertNotifiedOnce(failed, server);
        assertNativeFailure(failed.seen.get(0).completion().error());

        // When the stream idles out: the error is a TimeoutException
        Recorder idled = new Recorder();
        HttpClient idleClient = client(server, b -> b.register(idled).streamIdleTimeout(200, TimeUnit.MILLISECONDS));
        Opened idling = open(idleClient, "/idle");
        awaitFailure(idling.head().completion());
        assertNotifiedOnce(idled, server);
        assertThat(idled.seen.get(0).completion().error()).isExactlyInstanceOf(TimeoutException.class);

        // When the caller cancels after the head: the error is an HttpExchangeCancelledException
        Recorder cancelled = new Recorder();
        HttpClient cancelClient = client(server, b -> b.register(cancelled));
        Opened cancelling = open(cancelClient, "/feed");
        HttpStream cancelStream = cancelling.head();
        cancelStream.cancel();
        awaitFailure(cancelStream.completion());
        assertNotifiedOnce(cancelled, server);
        assertThat(cancelled.seen.get(0).completion().error())
                .isExactlyInstanceOf(HttpExchangeCancelledException.class);

        // When the client closes after the head while the server keeps the body open: HttpClientClosedException
        Recorder closed = new Recorder();
        HttpClient closeClient = client(server, b -> b.register(closed));
        Opened closing = open(closeClient, "/feed");
        HttpStream closeStream = closing.head();
        await(closeClient.close());
        assertThat(awaitFailure(closeStream.completion())).isExactlyInstanceOf(HttpClientClosedException.class);
        assertNotifiedOnce(closed, server);
        assertThat(closed.seen.get(0).completion().error()).isExactlyInstanceOf(HttpClientClosedException.class);

        // When the client closes while a head is still on its way, the head arriving later is never delivered
        Recorder closedEarly = new Recorder();
        HttpClient earlyClient = client(server, b -> b.register(closedEarly));
        Opened early = open(earlyClient, "/late");
        assertThat(serverReceived(server, "/late", 1)).isTrue();
        sleep(100);
        await(earlyClient.close());
        assertThat(awaitFailure(early.future)).isExactlyInstanceOf(HttpClientClosedException.class);
        sleep(700);
        assertThat(early.deliveries.get()).isZero();
        assertThat(closedEarly.settled(1).get(0).completion().error())
                .isExactlyInstanceOf(HttpClientClosedException.class);
    }

    // --- AC-073 ---

    /** AC-073: a refused 429 head is retried after Retry-After and the accepted head is delivered once. */
    @Test
    @DisplayName("a stream answered 429 with Retry-After is retried, then accepted")
    void aStreamAnswered429WithRetryAfterIsRetriedThenAccepted() {
        // Given a tier with maxRetries 2, a 10 ms backoff and retryAfterMaxMs 5000, a recording interceptor
        // and a server answering 429 with Retry-After 1 first and a 200 event-stream head second
        List<Long> arrivals = new CopyOnWriteArrayList<>();
        RecordingServer server = support.server((request, body) -> {
            arrivals.add(System.nanoTime());
            if (arrivals.size() == 1) {
                request.response()
                        .setStatusCode(429)
                        .putHeader("Retry-After", "1")
                        .end("slow down");
            } else {
                request.response()
                        .putHeader("Content-Type", "text/event-stream")
                        .end(TEN);
            }
        });
        Recorder recorder = new Recorder();
        HttpClient client = resilientClient(server, tiers("t", retryTier(2, 10, 5_000L)), b -> b.register(recorder));

        // When the caller opens stream for GET /feed with the tier
        Opened opened = open(client, tier("t"), "/feed", true);

        // Then the future succeeds once with status 200 and no stream was delivered for the 429 head
        HttpStream stream = opened.head();
        assertThat(stream.head().statusCode()).isEqualTo(200);
        assertThat(stream.head().headers().get("Content-Type")).isEqualTo("text/event-stream");
        await(stream.completion());
        assertThat(opened.deliveries.get()).isEqualTo(1);
        assertThat(opened.body.text()).isEqualTo("0123456789");
        // And the server received exactly 2 requests, the second at least 1000 ms after the first
        assertThat(arrivals).hasSize(2);
        assertThat(TimeUnit.NANOSECONDS.toMillis(arrivals.get(1) - arrivals.get(0)))
                .isGreaterThanOrEqualTo(1_000);
        // And the 429 exchange was reset
        assertThat(serverClosedConnections(server, 1, 1_000))
                .as("429 connection closed")
                .isTrue();
        // And the interceptor was notified twice with one callId: the 429 rejection, then the accepted attempt
        List<Recorder.Seen> seen = recorder.settled(2);
        HttpClientAttemptCompletion first = seen.get(0).completion();
        HttpClientAttemptCompletion second = seen.get(1).completion();
        assertThat(first.callId()).isEqualTo(second.callId());
        assertThat(first.attemptOrdinal()).isEqualTo(1);
        assertThat(first.error()).isExactlyInstanceOf(HttpStatusRejectedException.class);
        assertThat(((HttpStatusRejectedException) first.error()).statusCode()).isEqualTo(429);
        assertThat(second.attemptOrdinal()).isEqualTo(2);
        assertThat(second.error()).isNull();
        assertThat(second.statusCode()).isEqualTo(200);
    }

    // --- AC-074 ---

    /** AC-074: a head the expectation refuses fails the call with an excerpt, resets the exchange and is never delivered. */
    @Test
    @DisplayName("a refused stream head is reset and never delivered")
    void aRefusedStreamHeadIsResetAndNeverDelivered() {
        // Given a server whose GET /refused answers 503 with Retry-After 3 and Set-Cookie and a 5000-byte body
        // written slowly and never ended, whose GET /gone answers 404 and GET /ok answers 200
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = request.response();
            switch (request.path()) {
                case "/refused" -> {
                    response.setStatusCode(503)
                            .putHeader("Retry-After", "3")
                            .putHeader("Set-Cookie", "s=1")
                            .setChunked(true);
                    AtomicInteger sent = new AtomicInteger();
                    Buffer piece = Buffer.buffer("r".repeat(100));
                    response.write(piece);
                    sent.addAndGet(100);
                    support.vertx.setPeriodic(50, id -> {
                        if (response.closed() || sent.get() >= 5000) {
                            support.vertx.cancelTimer(id);
                            return;
                        }
                        response.write(piece);
                        sent.addAndGet(100);
                    });
                }
                case "/gone" -> response.setStatusCode(404).end("gone");
                default -> response.end("ok");
            }
        });
        HttpClient client = client(server);

        // When the caller runs stream for GET /refused with the default expectation
        Opened refused = open(client, CallOptions.defaults(), "/refused", true);
        Throwable failure = awaitFailure(refused.future);
        long failedAt = System.nanoTime();

        // Then the future fails with HttpStatusRejectedException carrying status, Retry-After and an excerpt
        assertThat(failure).isExactlyInstanceOf(HttpStatusRejectedException.class);
        HttpStatusRejectedException rejected = (HttpStatusRejectedException) failure;
        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(rejected.retryAfter()).isEqualTo(Optional.of(Duration.ofSeconds(3)));
        assertThat(rejected.headers().contains("Set-Cookie")).isFalse();
        assertThat(rejected.bodyExcerpt().map(Buffer::length).orElse(0)).isLessThanOrEqualTo(2048);
        // And no HttpStream is ever delivered for it
        // And the server observes the connection closed within 500 ms of the failure
        assertThat(serverClosedConnections(server, 1, 500)).isTrue();
        assertThat(millisSince(failedAt)).isLessThan(500 + 500);
        assertThat(refused.deliveries.get()).isZero();

        // And a 404 head accepted by a custom expectation is delivered with status 404
        CallOptions only404 = CallOptions.defaults().expecting(HttpExpectations.status(code -> code == 404));
        Opened gone = open(client, only404, "/gone", true);
        HttpStream goneStream = gone.head();
        assertThat(goneStream.head().statusCode()).isEqualTo(404);
        await(goneStream.completion());
        assertThat(gone.body.text()).isEqualTo("gone");

        // And the 200 head of /ok is refused by it with HttpStatusRejectedException of status 200
        Opened ok = open(client, only404, "/ok", true);
        Throwable okFailure = awaitFailure(ok.future);
        assertThat(okFailure).isExactlyInstanceOf(HttpStatusRejectedException.class);
        assertThat(((HttpStatusRejectedException) okFailure).statusCode()).isEqualTo(200);
        assertThat(ok.deliveries.get()).isZero();

        // And a 503 head with no body bytes on a silent connection ends with the attempt's timeout failure
        RecordingServer silent = support.server((request, body) ->
                request.response().setStatusCode(503).setChunked(true).writeHead());
        HttpClient timed = client(silent, b -> b.readTimeout(300, TimeUnit.MILLISECONDS));
        long timedStart = System.nanoTime();
        Opened silentOpened = open(timed, CallOptions.defaults(), "/silent", true);
        Throwable timeoutFailure = awaitFailure(silentOpened.future);
        assertThat(timeoutFailure).isExactlyInstanceOf(TimeoutException.class);
        assertThat(timeoutFailure).isNotInstanceOf(HttpStatusRejectedException.class);
        assertThat(millisSince(timedStart)).isLessThan(300 + 1_000);
        assertThat(serverClosedConnections(silent, 1, 1_000)).isTrue();
        assertThat(silentOpened.deliveries.get()).isZero();
    }

    // --- supporting behavior ---

    /**
     * The body is paused until a handler is set (nothing is lost meanwhile, and the end is not
     * delivered), a buffered request body is sent, and a null argument or a request not created by the
     * scoped web client fails the returned future with {@code IllegalArgumentException}.
     */
    @Test
    @DisplayName("the body is paused until a handler is set and null arguments fail the future")
    void bodyIsPausedUntilAHandlerIsSetAndNullArgumentsFailTheFuture() {
        // Given a server answering GET /s and POST /echo with the head and abcdef and the end
        RecordingServer server = support.server((request, body) -> {
            switch (request.path()) {
                case "/204" -> request.response().setStatusCode(204).end();
                case "/empty" -> request.response().end();
                default ->
                    request.response().putHeader("Content-Type", "text/plain").end("abcdef");
            }
        });
        HttpClient client = client(server);

        // When the caller receives the head and sets the handler only 1500 ms later
        Collector body = new Collector();
        Future<HttpStream> future = client.stream(CallOptions.defaults(), web -> web.get("/s"), null);
        future.onSuccess(stream -> support.vertx.setTimer(1_500, id -> body.attach(stream)));
        HttpStream stream = await(future);
        sleep(500);

        // Then nothing was delivered and the terminal future is still pending while no handler is set
        assertThat(body.chunks.get()).isZero();
        assertThat(stream.completion().isComplete())
                .as("completion without a handler")
                .isFalse();
        // And once the handler is set it receives every byte and completion() succeeds
        await(stream.completion());
        assertThat(body.text()).isEqualTo("abcdef");

        // And a buffered request body reaches the server
        Opened echoed = new Opened(
                client.stream(CallOptions.defaults(), web -> web.post("/echo"), Buffer.buffer("payload")), true);
        await(echoed.head().completion());
        assertThat(server.requestsTo("/echo")).hasSize(1);
        assertThat(server.requestsTo("/echo").get(0).body().toString()).isEqualTo("payload");

        // And null options or a null request function fail the returned future with IllegalArgumentException
        assertThat(awaitFailure(client.stream(null, web -> web.get("/s"), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(awaitFailure(client.stream(CallOptions.defaults(), null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        // And a request created by another web client fails the call with IllegalArgumentException
        WebClient foreign = WebClient.create(support.vertx);
        support.track(foreign::close);
        int before = server.requests.size();
        Throwable foreignFailure = awaitFailure(
                client.stream(CallOptions.defaults(), web -> foreign.get(server.port(), "127.0.0.1", "/s"), null));
        assertThat(foreignFailure).isInstanceOf(IllegalArgumentException.class);
        assertThat(server.requests).hasSize(before);

        // And a 204 and an empty 200 whose consumer sets the end handler 300 ms after the head still end once
        for (String path : List.of("/204", "/empty")) {
            AtomicInteger ends = new AtomicInteger();
            AtomicBoolean attached = new AtomicBoolean();
            Future<HttpStream> bodiless = client.stream(CallOptions.defaults(), web -> web.get(path), null);
            bodiless.onSuccess(delivered -> support.vertx.setTimer(300, id -> {
                delivered.body().endHandler(v -> ends.incrementAndGet());
                delivered.body().handler(chunk -> {});
                attached.set(true);
            }));
            HttpStream bodilessStream = await(bodiless);
            await(bodilessStream.completion());
            assertThat(awaitCondition(attached::get, HttpClientItSupport.WAIT_MS))
                    .isTrue();
            sleep(SETTLE_MS);
            assertThat(ends.get()).as("endHandler calls for " + path).isEqualTo(1);
        }
    }

    // --- supporting behavior ---

    /**
     * A bulkhead permit is held until the head and released at it, and the breaker records the
     * outcome of the head: a stream that fails after an accepted head does not count against the circuit,
     * a refused head does.
     */
    @Test
    @DisplayName("the bulkhead permit is released at the head and the breaker records the head outcome")
    void bulkheadPermitIsReleasedAtTheHeadAndTheBreakerRecordsTheHeadOutcome() {
        // Given a bulkhead tier of one permit and a breaker tier of two failures per circuit, and a server
        // with /feed (head, 10 bytes, open), /slowhead (head after 600 ms), /die (head, 10 bytes, socket
        // closed) and /down (503)
        RecordingServer server = support.server((request, body) -> {
            switch (request.path()) {
                case "/slowhead" ->
                    support.vertx.setTimer(600, id -> {
                        if (!request.response().closed()) {
                            feed(request);
                        }
                    });
                case "/die" ->
                    request.response().setChunked(true).write(TEN).onComplete(v -> request.connection()
                            .close());
                case "/down" -> request.response().setStatusCode(503).end();
                default -> feed(request);
            }
        });
        HttpClient client = resilientClient(
                server,
                tiers("bulk", bulkheadTier(1), "after", breakerTier(2, 60_000), "refused", breakerTier(2, 60_000)),
                b -> {});
        CallOptions bulk = tier("bulk");

        // When a stream is opened and, once its head is delivered, a second one, neither body consumed
        Opened a = open(client, bulk, "/feed", false);
        HttpStream streamA = a.head();
        Opened b = open(client, bulk, "/feed", false);

        // Then both heads are delivered although the bulkhead has one permit: it is released at the head
        HttpStream streamB = b.head();
        assertThat(streamA.head().statusCode()).isEqualTo(200);
        assertThat(streamB.head().statusCode()).isEqualTo(200);

        // And while one call waits for its head the permit is held and a concurrent call is rejected
        Opened slow = open(client, bulk, "/slowhead", false);
        assertThat(serverReceived(server, "/slowhead", 1)).isTrue();
        Opened rejected = open(client, bulk, "/feed", false);
        assertThat(awaitFailure(rejected.future)).isInstanceOf(BulkheadRejectedException.class);
        HttpStream slowStream = slow.head();
        // And once that head arrived the permit is free again
        Opened afterHead = open(client, bulk, "/feed", false);
        HttpStream streamAfter = afterHead.head();

        // And streams that fail after an accepted head do not open a circuit of two failures
        CallOptions after = tier("after");
        for (int i = 0; i < 3; i++) {
            Opened dying = open(client, after, "/die", true);
            awaitFailure(dying.head().completion());
        }
        assertThat(server.requestsTo("/die"))
                .as("requests that reached the server")
                .hasSize(3);

        // And a refused head counts: after two the circuit is open and the third call sends nothing
        CallOptions refusedTier = tier("refused");
        for (int i = 0; i < 2; i++) {
            Throwable failure = awaitFailure(open(client, refusedTier, "/down", false).future);
            assertThat(failure).isExactlyInstanceOf(HttpStatusRejectedException.class);
        }
        Throwable open = awaitFailure(open(client, refusedTier, "/down", false).future);
        assertThat(open).isExactlyInstanceOf(CircuitOpenException.class);
        assertThat(server.requestsTo("/down")).hasSize(2);

        for (HttpStream stream : List.of(streamA, streamB, slowStream, streamAfter)) {
            stream.cancel();
        }
    }

    // --- supporting behavior ---

    /** A cancel before the head fails the stream future with the cancel failure; repeating it is a no-op. */
    @Test
    @DisplayName("cancel before the head fails the stream future and a repeated cancel is a no-op")
    void cancelBeforeTheHeadFailsTheStreamFutureAndRepeatedCancelIsANoOp() {
        // Given a server whose GET /hang receives the request and never answers
        RecordingServer server = support.server((request, body) -> {});
        HttpClient client = client(server);
        CancellationSignal signal = CancellationSignal.create();

        // When the caller cancels the signal 100 ms after the server received the request
        Opened opened = open(client, CallOptions.defaults().cancellation(signal), "/hang", true);
        assertThat(serverReceived(server, "/hang", 1)).isTrue();
        sleep(100);
        long cancelledAt = System.nanoTime();
        signal.cancel();

        // Then the stream future fails with HttpExchangeCancelledException and no stream is delivered
        Throwable failure = awaitFailure(opened.future);
        assertThat(failure).isExactlyInstanceOf(HttpExchangeCancelledException.class);
        assertThat(millisSince(cancelledAt)).isLessThan(1_500);
        assertThat(opened.deliveries.get()).isZero();
        // And the server observes the connection closed
        assertThat(serverClosedConnections(server, 1, 1_500)).isTrue();
        // And cancelling again is a no-op: the outcome stays the same
        signal.cancel();
        signal.cancel();
        sleep(SETTLE_MS);
        assertThat(awaitFailure(opened.future)).isSameAs(failure);
        assertThat(opened.deliveries.get()).isZero();
        assertThat(server.requestsTo("/hang")).hasSize(1);
    }

    // --- AC-086 ---

    /** AC-086: the attempt timer stops at the accepted head, so a stream may outlive the attempt timeout. */
    @Test
    @DisplayName("a stream outlives the attempt timeout once its head is accepted")
    void aStreamOutlivesTheAttemptTimeoutOnceItsHeadIsAccepted() {
        // Given a 300 ms attempt timeout, no stream idle timeout and a server whose GET /long writes the head
        // and 10 bytes, then 10 bytes every 200 ms until 1.5 s have passed, then ends
        AtomicLong written = new AtomicLong();
        AtomicBoolean ended = new AtomicBoolean();
        AtomicBoolean closedBeforeEnd = new AtomicBoolean();
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = request.response().setChunked(true).putHeader("Content-Type", "text/plain");
            response.closeHandler(v -> {
                if (!ended.get()) {
                    closedBeforeEnd.set(true);
                }
            });
            long startedAt = System.nanoTime();
            response.write(TEN);
            written.addAndGet(10);
            support.vertx.setPeriodic(200, id -> {
                if (response.closed()) {
                    support.vertx.cancelTimer(id);
                } else if (millisSince(startedAt) >= 1_500) {
                    support.vertx.cancelTimer(id);
                    ended.set(true);
                    response.end();
                } else {
                    response.write(TEN);
                    written.addAndGet(10);
                }
            });
        });
        HttpClient client = client(
                server, b -> b.readTimeout(300, TimeUnit.MILLISECONDS).streamIdleTimeout(0, TimeUnit.MILLISECONDS));

        // When the caller opens stream for GET /long and consumes the body to the end
        long start = System.nanoTime();
        Opened opened = open(client, "/long");
        HttpStream stream = opened.head();

        // Then the future returned by stream succeeded with the head within 300 ms
        assertThat(stream.head().statusCode()).isEqualTo(200);
        assertThat(TimeUnit.NANOSECONDS.toMillis(opened.headNanos - start)).isLessThan(300);
        // And completion() succeeds after at least 1.5 s, so no TimeoutException was raised
        await(stream.completion());
        assertThat(millisSince(start)).isGreaterThanOrEqualTo(1_500);
        // And the consumer received every byte the server wrote and the server saw no close before its end
        assertThat((long) opened.body.length()).isEqualTo(written.get());
        assertThat(closedBeforeEnd)
                .as("connection closed before the server ended the response")
                .isFalse();
    }

    // --- review follow-ups ---

    /** Only the request the function returns is the stream; a side request on the same client is an ordinary exchange. */
    @Test
    @DisplayName("only the returned request is the stream")
    void onlyTheReturnedRequestIsTheStream() {
        // Given a server answering POST /subscribe 200 with a body and GET /events with a streamed head
        RecordingServer server = support.server((request, body) -> {
            if (request.path().equals("/subscribe")) {
                request.response().putHeader("X-Kind", "subscribe").end("subscribed");
            } else {
                request.response().putHeader("X-Kind", "events").end("events-body");
            }
        });
        HttpClient client = client(server);

        // When the request function sends the side request and returns the /events request
        AtomicReference<Future<io.vertx.ext.web.client.HttpResponse<Buffer>>> side = new AtomicReference<>();
        Opened opened = new Opened(
                client.stream(
                        CallOptions.defaults(),
                        web -> {
                            side.set(web.post("/subscribe").sendBuffer(Buffer.buffer()));
                            return web.get("/events");
                        },
                        null),
                true);
        HttpStream stream = opened.head();

        // Then the stream's head is the /events head and its body flows
        assertThat(stream.head().headers().get("X-Kind")).isEqualTo("events");
        await(stream.completion());
        assertThat(opened.body.text()).isEqualTo("events-body");
        // And the side request completed as an ordinary buffered exchange
        io.vertx.ext.web.client.HttpResponse<Buffer> sideResponse = await(side.get());
        assertThat(sideResponse.statusCode()).isEqualTo(200);
        assertThat(sideResponse.bodyAsString()).isEqualTo("subscribed");
        assertThat(server.requestsTo("/subscribe")).hasSize(1);
        assertThat(server.requestsTo("/events")).hasSize(1);
        // And a single delivery, with nothing left open after completion()
        assertThat(opened.deliveries.get()).isEqualTo(1);
    }

    /** The expectation receives a snapshot of the head: not the live response, no request and no credential. */
    @Test
    @DisplayName("the stream expectation sees a snapshot, not the live response")
    void theStreamExpectationSeesASnapshotNotTheLiveResponse() {
        // Given a client with a credential source injecting Authorization and a server echoing a header
        RecordingServer server = support.server(
                (request, body) -> request.response().putHeader("X-Resp", "yes").end("ok"));
        HttpClient client = client(server, b -> b.credentials(
                        target -> Future.succeededFuture(Map.of("Authorization", "Bearer secret-token")))
                .allowCredentialsOverPlainHttp(true));
        AtomicReference<io.vertx.core.http.HttpResponseHead> seen = new AtomicReference<>();
        CallOptions options = CallOptions.defaults().expecting(head -> {
            seen.set(head);
            return true;
        });

        // When the caller opens a stream whose expectation records its argument
        Opened opened = open(client, options, "/snap", true);
        await(opened.head().completion());

        // Then the server received the credential, so the check below is meaningful
        assertThat(server.requestsTo("/snap").get(0).headers().get("Authorization"))
                .isEqualTo("Bearer secret-token");
        // And the argument is not the live response and carries the response headers
        io.vertx.core.http.HttpResponseHead head = seen.get();
        assertThat(head).isNotNull();
        assertThat(head).isNotInstanceOf(io.vertx.core.http.HttpClientResponse.class);
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.headers().get("X-Resp")).isEqualTo("yes");
        // And no request, connection or credential is reachable from it
        for (java.lang.reflect.Method method : head.getClass().getMethods()) {
            assertThat(method.getReturnType())
                    .as("return type of %s", method.getName())
                    .isNotIn(
                            io.vertx.core.http.HttpClientRequest.class,
                            io.vertx.core.http.HttpClientResponse.class,
                            io.vertx.core.http.HttpConnection.class);
        }
        assertThat(head.headers().contains("Authorization")).isFalse();
        assertThat(head.toString()).doesNotContain("secret-token");
    }

    /** Several chunks held back, then released to a handler that cancels on the first. */
    @Test
    @DisplayName("a body handler that cancels gets no further chunk")
    void aBodyHandlerThatCancelsGetsNoFurtherChunk() {
        // Given a server writing five chunks at once and keeping the response open
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = request.response().setChunked(true);
            for (int i = 0; i < 5; i++) {
                response.write(Buffer.buffer("chunk" + i));
            }
        });
        HttpClient client = client(server);

        // When the handler is set 500 ms after the head, so the chunks are queued, and cancels on the first
        AtomicInteger chunks = new AtomicInteger();
        Future<HttpStream> future = client.stream(CallOptions.defaults(), web -> web.get("/many"), null);
        future.onSuccess(
                stream -> support.vertx.setTimer(500, id -> stream.body().handler(chunk -> {
                    if (chunks.incrementAndGet() == 1) {
                        stream.cancel();
                    }
                })));
        HttpStream stream = await(future);

        // Then completion() fails with the cancel failure and no second chunk is delivered
        assertThat(awaitFailure(stream.completion())).isExactlyInstanceOf(HttpExchangeCancelledException.class);
        sleep(SETTLE_MS);
        assertThat(chunks.get()).isEqualTo(1);
    }

    /** A handler that throws fails the stream with that throwable, closes the connection and ends delivery. */
    @Test
    @DisplayName("a throwing body handler fails the stream")
    void aThrowingBodyHandlerFailsTheStream() {
        // Given a server writing five chunks at once and keeping the response open, a client without idle timeout
        RecordingServer server = support.server((request, body) -> {
            if (request.path().equals("/last")) {
                request.response().end("last");
                return;
            }
            HttpServerResponse response = request.response().setChunked(true);
            for (int i = 0; i < 5; i++) {
                response.write(Buffer.buffer("chunk" + i));
            }
        });
        HttpClient client = client(server, b -> b.streamIdleTimeout(0, TimeUnit.MILLISECONDS));

        // When the handler throws on the first chunk
        RuntimeException boom = new IllegalArgumentException("handler failure");
        AtomicInteger chunks = new AtomicInteger();
        Future<HttpStream> future = client.stream(CallOptions.defaults(), web -> web.get("/many"), null);
        future.onSuccess(
                stream -> support.vertx.setTimer(300, id -> stream.body().handler(chunk -> {
                    chunks.incrementAndGet();
                    throw boom;
                })));
        HttpStream stream = await(future);

        // Then completion() fails with that same instance, no further chunk arrives and the connection closes
        assertThat(awaitFailure(stream.completion())).isSameAs(boom);
        sleep(SETTLE_MS);
        assertThat(chunks.get()).isEqualTo(1);
        assertThat(serverClosedConnections(server, 1, 1_500)).isTrue();

        // And the same throw on the final chunk of an already-ended body fails completion() promptly
        RuntimeException lastBoom = new IllegalStateException("last chunk failure");
        AtomicLong attachedAt = new AtomicLong();
        Future<HttpStream> ended = client.stream(CallOptions.defaults(), web -> web.get("/last"), null);
        ended.onSuccess(delivered -> support.vertx.setTimer(500, id -> {
            attachedAt.set(System.nanoTime());
            delivered.body().handler(chunk -> {
                throw lastBoom;
            });
        }));
        HttpStream endedStream = await(ended);
        assertThat(awaitFailure(endedStream.completion())).isSameAs(lastBoom);
        assertThat(millisSince(attachedAt.get())).isLessThan(2_000);
    }
}
