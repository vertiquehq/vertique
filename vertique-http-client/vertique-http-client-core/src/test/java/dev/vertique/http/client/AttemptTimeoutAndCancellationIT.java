// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitCondition;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import dev.vertique.http.client.exception.HttpExchangeCancelledException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.PoolOptions;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests of the client's own attempt timer and of cancellation through a
 * {@link CancellationSignal}: the timer covers the body, resets every open exchange, an attempt that
 * ends while waiting for a pooled connection sends nothing, and cancel works from any thread.
 *
 * <p>Every test runs against a real Vert.x server on {@code 127.0.0.1} and a dynamic port. Elapsed
 * bounds carry generous margins over the configured timeouts; a hang fails them.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class AttemptTimeoutAndCancellationIT {

    /** Margin added to a configured timeout before a timer failure counts as late. */
    private static final long MARGIN_MS = 1_000;

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

    // --- fixtures ---

    private VertiqueHttpClient client(RecordingServer server, long readTimeoutMs, PoolOptions pool) {
        VertiqueHttpClientBuilder builder = VertiqueHttpClientBuilder.create(support.vertx);
        support.track(builder::close);
        builder.baseUrl(server.origin());
        if (readTimeoutMs >= 0) {
            builder.readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS);
        }
        if (pool != null) {
            builder.poolOptions(pool);
        }
        VertiqueHttpClient client = builder.build();
        support.track(client::close);
        return client;
    }

    private VertiqueHttpClient redirectingClient(RecordingServer server, long readTimeoutMs) {
        VertiqueHttpClientBuilder builder = VertiqueHttpClientBuilder.create(support.vertx);
        support.track(builder::close);
        builder.baseUrl(server.origin()).webClientOptions(new WebClientOptions().setFollowRedirects(true));
        if (readTimeoutMs >= 0) {
            builder.readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS);
        }
        VertiqueHttpClient client = builder.build();
        support.track(client::close);
        return client;
    }

    /** A server that records every request and answers none of them. */
    private RecordingServer silentServer() {
        return support.server((request, body) -> {});
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

    /** The timer's message is constant: it carries no request detail. */
    private static void assertConstantTimeoutMessage(Throwable failure, RecordingServer server, String path) {
        assertThat(failure).isInstanceOf(TimeoutException.class);
        assertThat(failure.getMessage()).isNotBlank();
        assertThat(failure.getMessage()).doesNotContain("127.0.0.1").doesNotContain(path);
        assertThat(failure.getMessage()).doesNotContain(String.valueOf(server.port()));
    }

    // --- AC-013 ---

    /**
     * AC-013: the client's own timer covers the body, which Vert.x's request timeout does not, and
     * resets the exchange.
     */
    @Test
    @DisplayName("an attempt timeout resets an exchange stalled mid-body")
    void attemptTimeoutResetsAnExchangeStalledMidBody() {
        // Given a client with a 300 ms attempt timeout and a server that sends the head, 10 body
        // bytes and then nothing
        RecordingServer server = support.server((request, body) ->
                request.response().putHeader("Content-Length", "1000").write(Buffer.buffer("0123456789")));
        VertiqueHttpClient client = client(server, 300, null);

        // When the caller runs the stalled request
        long start = System.nanoTime();
        Throwable failure = awaitFailure(client.execute(web -> web.get("/stall").send()));
        long elapsedMs = millisSince(start);

        // Then it fails with a TimeoutException with a constant message, in time
        assertConstantTimeoutMessage(failure, server, "/stall");
        assertThat(elapsedMs).isLessThan(300 + MARGIN_MS);
        // And the server saw the head request and then the connection closed
        assertThat(server.requestsTo("/stall")).hasSize(1);
        assertThat(serverClosedConnections(server, 1, MARGIN_MS))
                .as("server observed the connection closed")
                .isTrue();
    }

    // --- AC-014 ---

    /** AC-014: a per-call timeout replaces the client's, and the last of the two options wins. */
    @Test
    @DisplayName("a per-call attempt timeout overrides the client default")
    void perCallAttemptTimeoutOverridesTheClientDefault() {
        // Given a client with a 30 s default and a server with /never and /slow (answers after 400 ms)
        RecordingServer server = support.server((request, body) -> {
            if (request.uri().equals("/slow")) {
                support.vertx.setTimer(
                        400, id -> request.response().setStatusCode(200).end("slow"));
            }
        });
        VertiqueHttpClient client = client(server, 30_000, null);

        // When the first call sets a 200 ms timeout and the second uses the default
        long start = System.nanoTime();
        Throwable failure = awaitFailure(
                client.execute(CallOptions.defaults().readTimeout(200, TimeUnit.MILLISECONDS), web -> web.get("/never")
                        .send()));
        long elapsedMs = millisSince(start);
        HttpResponse<Buffer> slow = await(client.execute(web -> web.get("/slow").send()));

        // Then the first fails with a TimeoutException in time and the server saw its connection close
        assertConstantTimeoutMessage(failure, server, "/never");
        assertThat(elapsedMs).isLessThan(200 + MARGIN_MS);
        assertThat(serverClosedConnections(server, 1, MARGIN_MS)).isTrue();
        // And the second succeeds because the 30 s default applies
        assertThat(slow.statusCode()).isEqualTo(200);

        // And of readTimeout and noReadTimeout the last wins
        HttpResponse<Buffer> lastIsNone = await(client.execute(
                CallOptions.defaults().readTimeout(100, TimeUnit.MILLISECONDS).noReadTimeout(),
                web -> web.get("/slow").send()));
        assertThat(lastIsNone.statusCode()).isEqualTo(200);
        Throwable lastIsTimeout = awaitFailure(client.execute(
                CallOptions.defaults().noReadTimeout().readTimeout(200, TimeUnit.MILLISECONDS),
                web -> web.get("/never").send()));
        assertThat(lastIsTimeout).isInstanceOf(TimeoutException.class);

        // And noReadTimeout lifts a short client default, and a timeout must be above zero
        VertiqueHttpClient shortDefault = client(server, 200, null);
        HttpResponse<Buffer> unbounded = await(shortDefault.execute(
                CallOptions.defaults().noReadTimeout(), web -> web.get("/slow").send()));
        assertThat(unbounded.statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> CallOptions.defaults().readTimeout(0, TimeUnit.MILLISECONDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CallOptions.defaults().readTimeout(-5, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- AC-015 ---

    /**
     * AC-015: cancelling before the head resets the exchange and fails the call; a signal cancelled
     * before the call fails it without sending.
     */
    @Test
    @DisplayName("cancel before the head resets the exchange and fails the call")
    void cancelBeforeTheHeadResetsTheExchangeAndFailsTheCall() throws Exception {
        // Given a client, a signal in the call options and a server that never answers
        RecordingServer server = silentServer();
        VertiqueHttpClient client = client(server, -1, null);
        CancellationSignal signal = CancellationSignal.create();
        Future<HttpResponse<Buffer>> call =
                client.execute(CallOptions.defaults().cancellation(signal), web -> web.get("/never")
                        .send());
        assertThat(serverReceived(server, "/never", 1)).isTrue();

        // When a plain Java thread cancels 100 ms after the server received the request
        AtomicLong cancelledAt = new AtomicLong();
        AtomicBoolean onVertxThread = new AtomicBoolean(true);
        Thread canceller = new Thread(() -> {
            sleepQuietly(100);
            onVertxThread.set(Context.isOnVertxThread());
            cancelledAt.set(System.nanoTime());
            signal.cancel();
        });
        canceller.start();
        Throwable failure = awaitFailure(call);
        long afterCancelMs = millisSince(cancelledAt.get());
        canceller.join(HttpClientItSupport.WAIT_MS);

        // Then the call failed with the cancellation failure promptly, though cancel ran off Vert.x
        assertThat(onVertxThread).isFalse();
        assertThat(failure).isInstanceOf(HttpExchangeCancelledException.class);
        assertThat(afterCancelMs).isLessThan(500 + MARGIN_MS);
        // And the server observed the connection closed
        assertThat(serverClosedConnections(server, 1, MARGIN_MS)).isTrue();
        // And a second cancel is a no-op and the signal reports cancelled
        signal.cancel();
        assertThat(signal.isCancelled()).isTrue();
        assertThat(call.failed()).isTrue();
        assertThat(call.cause()).isSameAs(failure);

        // And a signal cancelled before the call fails it with nothing sent
        RecordingServer untouched = silentServer();
        VertiqueHttpClient other = client(untouched, -1, null);
        CancellationSignal early = CancellationSignal.create();
        early.cancel();
        Throwable earlyFailure =
                awaitFailure(other.execute(CallOptions.defaults().cancellation(early), web -> web.get("/never")
                        .send()));
        assertThat(earlyFailure).isInstanceOf(HttpExchangeCancelledException.class);
        assertThat(untouched.requests).isEmpty();
        assertThat(untouched.openedConnections.get()).isZero();
    }

    // --- AC-080 ---

    /**
     * AC-080: an attempt that ends while the request waits for a pooled connection writes nothing
     * once the connection frees, and the slot stays usable.
     */
    @Test
    @DisplayName("an attempt ending during the pool wait sends nothing and the slot is reusable")
    void anAttemptEndingDuringThePoolWaitSendsNothingAndTheSlotIsReusable() {
        // Given a one-connection pool, /hold that never answers and /second that answers 200
        RecordingServer server = support.server((request, body) -> {
            if (request.uri().equals("/second")) {
                request.response().setStatusCode(200).end("second");
            }
        });
        VertiqueHttpClient client = client(server, -1, new PoolOptions().setHttp1MaxSize(1));
        CancellationSignal signal = CancellationSignal.create();
        // And a first call that holds the only connection
        Future<HttpResponse<Buffer>> hold =
                client.execute(CallOptions.defaults().noReadTimeout().cancellation(signal), web -> web.get("/hold")
                        .send());
        assertThat(serverReceived(server, "/hold", 1)).isTrue();

        // When a second call waits for a pooled connection and its 300 ms timer fires during the wait
        long start = System.nanoTime();
        Throwable timedOut = awaitFailure(
                client.execute(CallOptions.defaults().readTimeout(300, TimeUnit.MILLISECONDS), web -> web.get("/second")
                        .send()));
        long elapsedMs = millisSince(start);
        // And the caller then frees the connection
        signal.cancel();
        Throwable held = awaitFailure(hold);

        // Then the second call failed with a TimeoutException in time
        assertConstantTimeoutMessage(timedOut, server, "/second");
        assertThat(elapsedMs).isLessThan(300 + MARGIN_MS);
        assertThat(held).isInstanceOf(HttpExchangeCancelledException.class);
        // And a third call succeeds on the freed slot
        HttpResponse<Buffer> third =
                await(client.execute(web -> web.get("/second").send()));
        assertThat(third.statusCode()).isEqualTo(200);
        // And the timed-out call's request never reached the server: exactly one /second in total,
        // not even a late one after a settle period
        assertThat(awaitCondition(() -> server.requestsTo("/second").size() > 1, 300))
                .as("a second /second request reached the server")
                .isFalse();
        assertThat(server.requestsTo("/second")).hasSize(1);
        // And the pool never grew past its one slot
        assertThat(server.peakOpenConnections.get()).isEqualTo(1);

        // Given a client with a 300 ms default and a one-connection pool, whose only connection a
        // call without a timer and with a signal holds
        RecordingServer directServer = support.server((request, body) -> {
            if (request.uri().equals("/second2")) {
                request.response().setStatusCode(200).end("second2");
            }
        });
        VertiqueHttpClient directClient = client(directServer, 300, new PoolOptions().setHttp1MaxSize(1));
        CancellationSignal directSignal = CancellationSignal.create();
        Future<HttpResponse<Buffer>> directHold = directClient.execute(
                CallOptions.defaults().noReadTimeout().cancellation(directSignal),
                web -> web.get("/hold").send());
        assertThat(serverReceived(directServer, "/hold", 1)).isTrue();

        // When a direct webClient() request waits for the connection and its timer fires
        long directStart = System.nanoTime();
        Throwable directTimedOut =
                awaitFailure(directClient.webClient().get("/second").send());
        long directElapsedMs = millisSince(directStart);
        directSignal.cancel();
        assertThat(awaitFailure(directHold)).isInstanceOf(HttpExchangeCancelledException.class);

        // Then it failed with a TimeoutException in time
        assertConstantTimeoutMessage(directTimedOut, directServer, "/second");
        assertThat(directElapsedMs).isLessThan(300 + MARGIN_MS);
        // And a later direct request succeeds on the freed slot
        HttpResponse<Buffer> directLater =
                await(directClient.webClient().get("/second2").send());
        assertThat(directLater.statusCode()).isEqualTo(200);
        // And the server never saw the timed-out request, even after a settle period
        assertThat(awaitCondition(() -> !directServer.requestsTo("/second").isEmpty(), 300))
                .as("the timed-out direct request reached the server")
                .isFalse();
    }

    /**
     * AC-080: when an attempt's deadline passes while its exchange still waits for a pooled connection,
     * the exchange's own future fails at once with the raw timeout, not later and not as an ended-scope
     * failure.
     */
    @Test
    @DisplayName("the exchange waiting for a pooled connection fails with TimeoutException at the deadline")
    void theExchangeWaitingForAPooledConnectionFailsWithTimeoutAtTheDeadline() {
        // Given a one-connection pool whose only connection a hung call holds
        RecordingServer server = silentServer();
        VertiqueHttpClient client = client(server, -1, new PoolOptions().setHttp1MaxSize(1));
        CancellationSignal signal = CancellationSignal.create();
        Future<HttpResponse<Buffer>> hold =
                client.execute(CallOptions.defaults().noReadTimeout().cancellation(signal), web -> web.get("/hold")
                        .send());
        assertThat(serverReceived(server, "/hold", 1)).isTrue();

        // When a second call waits for the connection and the inner send future is observed
        AtomicReference<Future<HttpResponse<Buffer>>> inner = new AtomicReference<>();
        long start = System.nanoTime();
        Future<HttpResponse<Buffer>> second =
                client.execute(CallOptions.defaults().readTimeout(300, TimeUnit.MILLISECONDS), web -> {
                    Future<HttpResponse<Buffer>> send = web.get("/second").send();
                    inner.set(send);
                    return send;
                });
        awaitFailure(second);
        Throwable innerFailure = awaitFailure(inner.get());
        long elapsedMs = millisSince(start);
        // Then the inner future failed with the raw TimeoutException within the deadline plus margin
        assertThat(innerFailure).isExactlyInstanceOf(TimeoutException.class);
        assertThat(elapsedMs).isLessThan(300 + MARGIN_MS);
        // And a later release of the connection does not change that
        signal.cancel();
        awaitFailure(hold);
        assertThat(server.requestsTo("/second")).isEmpty();
    }

    // --- attempt timer, supporting ---

    /**
     * A timeout resets every open exchange of the attempt, and nothing of the attempt
     * outlives it: an exchange still open when the attempt settles is reset at once, and a finished
     * attempt's timer leaves a kept-alive connection alone.
     */
    @Test
    @DisplayName("a timeout resets every open exchange of the attempt and timers end with the attempt")
    void timeoutResetsEveryOpenExchangeOfTheAttemptAndTimersEndWithTheAttempt() {
        // Given a server that answers /ok and nothing else
        RecordingServer server = support.server((request, body) -> {
            if (request.uri().equals("/ok")) {
                request.response().setStatusCode(200).end("ok");
            }
        });
        VertiqueHttpClient client = client(server, -1, null);

        // When one attempt opens two exchanges that never answer and its 500 ms timer fires
        long start = System.nanoTime();
        Throwable failure =
                awaitFailure(client.execute(CallOptions.defaults().readTimeout(500, TimeUnit.MILLISECONDS), web -> {
                    web.get("/a").send().onFailure(ignored -> {});
                    return web.get("/b").send();
                }));
        long elapsedMs = millisSince(start);

        // Then it fails with a TimeoutException in time
        assertThat(failure).isInstanceOf(TimeoutException.class);
        assertThat(elapsedMs).isLessThan(500 + MARGIN_MS);
        // And the server saw both requests and both connections closed
        assertThat(server.requestsTo("/a")).hasSize(1);
        assertThat(server.requestsTo("/b")).hasSize(1);
        assertThat(serverClosedConnections(server, 2, MARGIN_MS))
                .as("both exchanges were reset")
                .isTrue();

        // When another attempt settles successfully while a second exchange is still open (5 s timer)
        int closedBefore = server.closedConnections.get();
        String settled = await(client.execute(CallOptions.defaults().readTimeout(5, TimeUnit.SECONDS), web -> {
            web.get("/hang").send().onFailure(ignored -> {});
            return web.get("/ok").send().map(response -> "done");
        }));
        assertThat(settled).isEqualTo("done");
        // Then the leftover exchange is reset when the attempt ends, long before its timer
        assertThat(serverClosedConnections(server, closedBefore + 1, MARGIN_MS))
                .as("exchange left open by a settled attempt was reset")
                .isTrue();

        // When a finished attempt's 300 ms timer passes on a kept-alive connection
        int openedBefore = server.openedConnections.get();
        HttpResponse<Buffer> first = await(
                client.execute(CallOptions.defaults().readTimeout(300, TimeUnit.MILLISECONDS), web -> web.get("/ok")
                        .send()));
        int closedAfterFirst = server.closedConnections.get();
        assertThat(awaitCondition(() -> server.closedConnections.get() > closedAfterFirst, 700))
                .as("the finished attempt's timer closed a connection")
                .isFalse();
        // Then the connection is still usable
        HttpResponse<Buffer> second = await(client.execute(web -> web.get("/ok").send()));
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(server.openedConnections.get()).isLessThanOrEqualTo(openedBefore + 1);

        // Given a client with a 300 ms default and a server that stalls /stall mid-body and answers
        // /ok after 100 ms
        RecordingServer direct = support.server((request, body) -> {
            if (request.uri().equals("/ok")) {
                support.vertx.setTimer(
                        100, id -> request.response().setStatusCode(200).end("ok"));
            } else {
                request.response().putHeader("Content-Length", "1000").write(Buffer.buffer("0123456789"));
            }
        });
        VertiqueHttpClient directClient = client(direct, 300, null);

        // When a request goes straight through webClient(), one attempt with the client defaults
        long directStart = System.nanoTime();
        Throwable directFailure =
                awaitFailure(directClient.webClient().get("/stall").send());
        long directElapsedMs = millisSince(directStart);

        // Then it fails with a TimeoutException with a constant message, in time
        assertConstantTimeoutMessage(directFailure, direct, "/stall");
        assertThat(directElapsedMs).isLessThan(300 + MARGIN_MS);
        // And the server saw the connection closed
        assertThat(serverClosedConnections(direct, 1, MARGIN_MS)).isTrue();
        // And a later direct request under 300 ms succeeds: the timer does not leak
        HttpResponse<Buffer> later = await(directClient.webClient().get("/ok").send());
        assertThat(later.statusCode()).isEqualTo(200);

        // Given a client that follows redirects and a server whose /hop1 redirects to /hop2, which
        // never answers
        RecordingServer hops = support.server((request, body) -> {
            if (request.uri().equals("/hop1")) {
                request.response()
                        .setStatusCode(302)
                        .putHeader("Location", "/hop2")
                        .end();
            }
        });
        VertiqueHttpClient hopClient = redirectingClient(hops, 400);

        // When the 400 ms timer fires while the second hop waits for its answer
        long hopStart = System.nanoTime();
        Throwable hopFailure =
                awaitFailure(hopClient.execute(web -> web.get("/hop1").send()));
        long hopElapsedMs = millisSince(hopStart);

        // Then it fails with a TimeoutException in time and the hop-2 exchange was reset
        assertThat(hopFailure).isInstanceOf(TimeoutException.class);
        assertThat(hopElapsedMs).isLessThan(400 + MARGIN_MS);
        assertThat(hops.requestsTo("/hop2")).hasSize(1);
        assertThat(serverClosedConnections(hops, 1, MARGIN_MS))
                .as("the connection of the second hop was closed")
                .isTrue();

        // And a cancel 200 ms after the second hop arrived resets it the same way
        RecordingServer cancelHops = support.server((request, body) -> {
            if (request.uri().equals("/hop1")) {
                request.response()
                        .setStatusCode(302)
                        .putHeader("Location", "/hop2")
                        .end();
            }
        });
        VertiqueHttpClient cancelClient = redirectingClient(cancelHops, -1);
        CancellationSignal hopSignal = CancellationSignal.create();
        Future<HttpResponse<Buffer>> hopCall =
                cancelClient.execute(CallOptions.defaults().cancellation(hopSignal), web -> web.get("/hop1")
                        .send());
        assertThat(serverReceived(cancelHops, "/hop2", 1)).isTrue();
        sleepQuietly(200);
        hopSignal.cancel();
        assertThat(awaitFailure(hopCall)).isInstanceOf(HttpExchangeCancelledException.class);
        assertThat(serverClosedConnections(cancelHops, 1, MARGIN_MS))
                .as("the connection of the second hop was closed by the cancel")
                .isTrue();

        // Given /hop1 on server A redirecting to /hop2 on server B, a different connection that
        // never answers
        RecordingServer serverB = silentServer();
        RecordingServer serverA = support.server((request, body) -> request.response()
                .setStatusCode(302)
                .putHeader("Location", "http://localhost:" + serverB.port() + "/hop2")
                .end());
        VertiqueHttpClient crossClient = redirectingClient(serverA, 400);

        // When the 400 ms timer fires while the second hop waits for its answer
        long crossStart = System.nanoTime();
        Throwable crossFailure =
                awaitFailure(crossClient.execute(web -> web.get("/hop1").send()));
        long crossElapsedMs = millisSince(crossStart);

        // Then it fails with a TimeoutException in time and server B saw its connection closed
        assertThat(crossFailure).isInstanceOf(TimeoutException.class);
        assertThat(crossElapsedMs).isLessThan(400 + MARGIN_MS);
        assertThat(serverB.requestsTo("/hop2")).hasSize(1);
        assertThat(serverClosedConnections(serverB, 1, MARGIN_MS))
                .as("server B's connection of the second hop was closed")
                .isTrue();

        // And a cancel 200 ms after the second hop arrived resets it the same way
        RecordingServer cancelB = silentServer();
        RecordingServer cancelA = support.server((request, body) -> request.response()
                .setStatusCode(302)
                .putHeader("Location", "http://localhost:" + cancelB.port() + "/hop2")
                .end());
        VertiqueHttpClient crossCancelClient = redirectingClient(cancelA, -1);
        CancellationSignal crossSignal = CancellationSignal.create();
        Future<HttpResponse<Buffer>> crossCall =
                crossCancelClient.execute(CallOptions.defaults().cancellation(crossSignal), web -> web.get("/hop1")
                        .send());
        assertThat(serverReceived(cancelB, "/hop2", 1)).isTrue();
        sleepQuietly(200);
        crossSignal.cancel();
        assertThat(awaitFailure(crossCall)).isInstanceOf(HttpExchangeCancelledException.class);
        assertThat(cancelB.requestsTo("/hop2")).hasSize(1);
        assertThat(serverClosedConnections(cancelB, 1, MARGIN_MS))
                .as("server B's connection of the second hop was closed by the cancel")
                .isTrue();
    }

    // --- cancellation, supporting ---

    /**
     * Cancel from many threads at once is idempotent, fails the call once, and a signal
     * cancelled before a call sends nothing for it.
     */
    @Test
    @DisplayName("cancel from any thread is idempotent and a signal cancelled before the call sends nothing")
    void cancelFromAnyThreadIsIdempotentAndASignalCancelledBeforeTheCallSendsNothing() throws Exception {
        // Given a call in flight against a server that never answers
        RecordingServer server = silentServer();
        VertiqueHttpClient client = client(server, -1, null);
        CancellationSignal signal = CancellationSignal.create();
        Future<HttpResponse<Buffer>> call =
                client.execute(CallOptions.defaults().cancellation(signal), web -> web.get("/never")
                        .send());
        assertThat(serverReceived(server, "/never", 1)).isTrue();
        assertThat(signal.isCancelled()).isFalse();

        // When eight plain threads cancel it at the same moment
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> cancellers = new ArrayList<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean anyOnVertxThread = new AtomicBoolean();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    anyOnVertxThread.compareAndSet(false, Context.isOnVertxThread());
                    signal.cancel();
                } catch (Throwable e) {
                    thrown.compareAndSet(null, e);
                }
            });
            cancellers.add(t);
            t.start();
        }
        assertThat(ready.await(HttpClientItSupport.WAIT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        go.countDown();
        for (Thread t : cancellers) {
            t.join(HttpClientItSupport.WAIT_MS);
        }
        Throwable failure = awaitFailure(call);

        // Then no cancel threw, the call failed once with the cancellation failure, one connection closed
        assertThat(thrown.get()).isNull();
        assertThat(anyOnVertxThread).isFalse();
        assertThat(failure).isInstanceOf(HttpExchangeCancelledException.class);
        assertThat(signal.isCancelled()).isTrue();
        assertThat(serverClosedConnections(server, 1, MARGIN_MS)).isTrue();
        assertThat(server.requestsTo("/never")).hasSize(1);
        // And cancelling again after the call settled changes nothing
        signal.cancel();
        assertThat(call.cause()).isSameAs(failure);
        assertThat(server.closedConnections.get()).isEqualTo(1);

        // And a call started with the already cancelled signal fails and sends nothing
        int requestsBefore = server.requests.size();
        Throwable reuse =
                awaitFailure(client.execute(CallOptions.defaults().cancellation(signal), web -> web.get("/second")
                        .send()));
        assertThat(reuse).isInstanceOf(HttpExchangeCancelledException.class);
        assertThat(awaitCondition(() -> server.requests.size() > requestsBefore, 300))
                .as("a request reached the server for a call whose signal was already cancelled")
                .isFalse();
        assertThat(server.requestsTo("/second")).isEmpty();
    }

    // --- AC-091 ---

    /** AC-091: a long-lived signal holds no registration for settled calls. */
    @Test
    @DisplayName("one signal shared by many calls holds no registration for settled calls")
    void oneSignalSharedByManyCallsHoldsNoRegistrationForSettledCalls() {
        // Given a client, one signal and a server that answers /ok and leaves /hang unanswered
        RecordingServer server = support.server((request, body) -> {
            if (request.uri().equals("/ok")) {
                request.response().setStatusCode(200).end("ok");
            }
        });
        VertiqueHttpClient client = client(server, -1, null);
        CancellationSignal signal = CancellationSignal.create();
        CallOptions options = CallOptions.defaults().cancellation(signal);

        // When the caller runs 1000 sequential calls
        for (int i = 0; i < 1000; i++) {
            HttpResponse<Buffer> response =
                    await(client.execute(options, web -> web.get("/ok").send()));
            // Then each succeeds and the signal holds no registration once it settled
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(signal.registrationCount())
                    .as("registrations after call %s", i)
                    .isZero();
        }

        // When one more call hangs
        Future<HttpResponse<Buffer>> hang =
                client.execute(options, web -> web.get("/hang").send());
        assertThat(serverReceived(server, "/hang", 1)).isTrue();
        // Then the signal holds exactly that call, and cancelling fails it
        assertThat(signal.registrationCount()).isEqualTo(1);
        signal.cancel();
        Throwable failure = awaitFailure(hang);
        assertThat(failure).isInstanceOf(HttpExchangeCancelledException.class);
        assertThat(signal.registrationCount()).isZero();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
