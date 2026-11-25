// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitCondition;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.vertique.http.client.HttpClientItSupport.RecordedRequest;
import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.impl.HttpContext;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests of the governed {@code WebClient}: {@code execute} with an attempt function,
 * the single-attempt {@code webClient()}, base-URL handling, header validation and the framework
 * delegate every handed-out {@code WebClient} is.
 *
 * <p>Every test runs against a real Vert.x HTTP server on {@code 127.0.0.1} and a dynamic port.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WebClientViewIT {

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

    private HttpClient client(String baseUrl) {
        HttpClientBuilder builder = HttpClientBuilder.create(support.vertx);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        HttpClient client = builder.build();
        support.track(client::close);
        return client;
    }

    /** Server that answers every request 200 with {@code ok}. */
    private RecordingServer okServer() {
        return support.server((rq, body) -> rq.response().end("ok"));
    }

    /** What happened to a request the client was asked to reject. */
    private record Rejection(Throwable cause, boolean throughFuture) {}

    /**
     * Sends a request built by {@code send} and reports its rejection. Vert.x itself validates
     * header characters when a header is set, so a rejection can surface as a throw from the
     * request builder or as the failure of the returned future.
     */
    private static Future<Rejection> rejection(Supplier<Future<?>> send) {
        Future<?> sent;
        try {
            sent = send.get();
        } catch (Throwable thrown) {
            return Future.succeededFuture(new Rejection(thrown, false));
        }
        return sent.transform(ar -> ar.failed()
                ? Future.succeededFuture(new Rejection(ar.cause(), true))
                : Future.failedFuture(new AssertionError("request was accepted but must be rejected")));
    }

    private static Rejection awaitRejection(Supplier<Future<?>> send) {
        return await(rejection(send));
    }

    private static Supplier<Future<?>> getWithHeader(WebClient web, String name, String value) {
        return () -> web.get("/").putHeader(name, value).send();
    }

    @Test
    @DisplayName("attempt function receives a governed WebClient and its result is returned")
    void attemptFunctionReceivesAWebClientViewAndItsResultIsReturned() {
        // Given a client with a base URL path and a server answering GET /api/items with "hello"
        RecordingServer server = support.server((rq, body) -> {
            if (rq.path().equals("/api/items")) {
                rq.response().end("hello");
            } else {
                rq.response().setStatusCode(404).end();
            }
        });
        HttpClient client = client(server.origin() + "/api");
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<WebClient> handed = new AtomicReference<>();

        // When the caller runs execute with a function that counts its invocations
        Future<String> result = client.execute(web -> {
            invocations.incrementAndGet();
            handed.set(web);
            return web.get("/items").send().map(HttpResponse::bodyAsString);
        });

        // Then the future succeeds with the value, the function ran once and the request reached /api/items
        assertThat(await(result)).isEqualTo("hello");
        assertThat(invocations.get()).isEqualTo(1);
        assertThat(handed.get()).isNotNull().isNotSameAs(client.webClient());
        assertThat(server.requests).extracting(RecordedRequest::target).containsExactly("GET /api/items");
    }

    @Test
    @DisplayName("open exchanges of a finished attempt are reset")
    void openExchangesOfAFinishedAttemptAreReset() {
        // Given a server that accepts GET /never, never answers and counts closed connections
        Promise<Void> firstSeen = Promise.promise();
        RecordingServer server = support.server((rq, body) -> firstSeen.tryComplete());
        HttpClient client = client(server.origin());

        // When the attempt starts an unanswered exchange, does not wait for it and succeeds
        // (the function returns once the server has the request, so the exchange is certainly open)
        Future<String> succeeded = client.execute(web -> {
            web.get("/never").send();
            return firstSeen.future().map("done");
        });

        // Then execute succeeds and the server sees the connection closed within 500 ms of the call finishing
        assertThat(await(succeeded)).isEqualTo("done");
        assertThat(awaitCondition(() -> server.closedConnections.get() == 1, 500))
                .as("server observed the connection closed within 500 ms of the call finishing")
                .isTrue();

        // And when the function instead fails after the same unanswered send
        Promise<Void> secondSeen = Promise.promise();
        RecordingServer second = support.server((rq, body) -> secondSeen.tryComplete());
        HttpClient other = client(second.origin());
        Future<String> failed = other.execute(web -> {
            web.get("/never").send();
            return secondSeen.future().compose(v -> Future.<String>failedFuture(new IllegalStateException("stop")));
        });

        // Then execute fails with that exception and the connection is again closed within 500 ms
        assertThat(awaitFailure(failed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stop");
        assertThat(awaitCondition(() -> second.closedConnections.get() == 1, 500))
                .as("server observed the connection closed within 500 ms after the failed attempt")
                .isTrue();
    }

    @Test
    @DisplayName("a scoped WebClient used after its attempt fails with IllegalState and opens no connection")
    void scopedWebClientUsedAfterItsAttemptFailsWithIllegalState() {
        // Given a client, a server counting requests and an attempt that stores its WebClient
        RecordingServer server = okServer();
        HttpClient client = client(server.origin());
        AtomicReference<WebClient> afterSuccess = new AtomicReference<>();
        AtomicReference<WebClient> afterFailure = new AtomicReference<>();
        await(client.execute(web -> {
            afterSuccess.set(web);
            return Future.succeededFuture("done");
        }));
        Throwable attemptFailure = awaitFailure(client.<String>execute(web -> {
            afterFailure.set(web);
            return Future.failedFuture(new IllegalStateException("stop"));
        }));
        assertThat(attemptFailure).hasMessage("stop");

        // When the stored WebClients send after their attempt ended
        Rejection afterSuccessful =
                awaitRejection(() -> afterSuccess.get().get("/x").send());
        Rejection afterFailed =
                awaitRejection(() -> afterFailure.get().get("/x").send());

        // Then each future fails with IllegalStateException, a constant message and no cause
        for (Rejection rejection : List.of(afterSuccessful, afterFailed)) {
            assertThat(rejection.throughFuture())
                    .as("returned future fails, request building does not throw")
                    .isTrue();
            assertThat(rejection.cause()).isInstanceOf(IllegalStateException.class);
            assertThat(rejection.cause().getCause()).isNull();
            assertThat(rejection.cause().getMessage()).isNotBlank().doesNotContain("/x");
        }
        assertThat(afterSuccessful.cause().getMessage())
                .isEqualTo(afterFailed.cause().getMessage());
        // And no connection was opened and the server received nothing
        assertThat(server.requests).isEmpty();
        assertThat(server.openedConnections.get()).isZero();
    }

    @Test
    @DisplayName("relative request uses the base URL and an absolute request is not prefixed")
    void relativeRequestUsesTheBaseUrlAndAbsoluteRequestIsNotPrefixed() {
        // Given a client with a base URL path, a recording server and a second client without a base URL
        RecordingServer server = okServer();
        HttpClient withBase = client(server.origin() + "/api");
        HttpClient withoutBase = client(null);

        // When a custom-method relative request and an absolute POST go through the first client's webClient()
        await(withBase.webClient()
                .request(HttpMethod.valueOf("PROPFIND"), "/v1/files?x=1")
                .send());
        await(withBase.webClient().postAbs(server.origin() + "/abs").sendBuffer(Buffer.buffer("abc")));

        // Then the relative request carries the base path and the absolute one is untouched with its body
        assertThat(server.requests).hasSize(2);
        assertThat(server.requests.get(0).target()).isEqualTo("PROPFIND /api/v1/files?x=1");
        assertThat(server.requests.get(1).target()).isEqualTo("POST /abs");
        assertThat(server.requests.get(1).body().toString()).isEqualTo("abc");

        // And a relative request through the client without a base URL fails its future and opens no connection
        int connectionsBefore = server.openedConnections.get();
        Rejection noBase =
                awaitRejection(() -> withoutBase.webClient().get("/x").send());
        assertThat(noBase.throughFuture()).isTrue();
        assertThat(noBase.cause()).isNotInstanceOf(ConnectException.class);
        assertThat(server.openedConnections.get()).isEqualTo(connectionsBefore);

        // And a request that sets only a port (host unset) does not name a target either: it fails
        // without connecting and the server sees no new connection and no request
        int requestsBefore = server.requests.size();
        Rejection portOnly = awaitRejection(() -> withoutBase
                .webClient()
                .request(new RequestOptions().setPort(server.port()).setURI("/x"))
                .send());
        assertThat(portOnly.throughFuture()).isTrue();
        assertThat(portOnly.cause()).isNotInstanceOf(ConnectException.class);
        assertThat(server.openedConnections.get()).isEqualTo(connectionsBefore);
        assertThat(server.requests).hasSize(requestsBefore);
    }

    @Test
    @DisplayName("invalid headers are rejected before sending")
    void invalidHeadersAreRejectedBeforeSending() {
        // Given a client and a server counting requests
        RecordingServer server = okServer();
        HttpClient client = client(server.origin());
        WebClient web = client.webClient();
        String[][] invalid = {
            {"X-A", "a\r\nX-B: b"},
            {"X-A\nB", "a"},
            {"X-A", "a\u0000b"},
            {"X-A\u0000B", "a"},
            {"X-A", "a\rb"},
        };

        // When GET / is sent once with each invalid header
        List<Rejection> rejections = new ArrayList<>();
        for (String[] header : invalid) {
            rejections.add(awaitRejection(getWithHeader(web, header[0], header[1])));
        }

        // Then each is rejected with IllegalArgumentException and no cause, and nothing is sent
        for (Rejection rejection : rejections) {
            assertThat(rejection.cause()).isInstanceOf(IllegalArgumentException.class);
            assertThat(rejection.cause().getCause()).isNull();
        }
        // And a rejection that comes through the governed client's future carries one constant message
        // that names the rule and contains neither the header name nor its value
        List<String> constant = new ArrayList<>();
        for (int i = 0; i < invalid.length; i++) {
            if (rejections.get(i).throughFuture()) {
                String message = rejections.get(i).cause().getMessage();
                assertThat(message).isNotBlank().doesNotContain(invalid[i][0]).doesNotContain(invalid[i][1]);
                constant.add(message);
            }
        }
        assertThat(Set.copyOf(constant))
                .as("the constant message is the same for every input")
                .hasSizeLessThanOrEqualTo(1);
        assertThat(server.requests).isEmpty();
    }

    @Test
    @DisplayName(
            "null arguments fail the returned future with IllegalArgumentException and futures complete on the caller's context")
    void nullArgumentsFailTheReturnedFutureWithIllegalArgumentAndFuturesCompleteOnTheCallersContext() {
        // Given a client and a server
        RecordingServer server = okServer();
        HttpClient client = client(server.origin());

        // When execute is called with a null attempt function, a null option set or a null function with options
        // Then no call throws; each returned future fails with IllegalArgumentException
        AtomicReference<Future<String>> nullAttempt = new AtomicReference<>();
        AtomicReference<Future<String>> nullOptions = new AtomicReference<>();
        AtomicReference<Future<String>> nullFunction = new AtomicReference<>();
        assertThatCode(() -> {
                    nullAttempt.set(client.execute((Function<WebClient, Future<String>>) null));
                    nullOptions.set(client.execute(null, web -> Future.succeededFuture("x")));
                    nullFunction.set(client.execute(CallOptions.defaults(), null));
                })
                .doesNotThrowAnyException();
        assertThat(awaitFailure(nullAttempt.get())).isInstanceOf(IllegalArgumentException.class);
        assertThat(awaitFailure(nullOptions.get())).isInstanceOf(IllegalArgumentException.class);
        assertThat(awaitFailure(nullFunction.get())).isInstanceOf(IllegalArgumentException.class);
        assertThat(server.requests).isEmpty();

        // And when execute is called on a Vert.x context, the future completes on that same context
        Context context = support.vertx.getOrCreateContext();
        context.put("marker", "caller");
        CompletableFuture<String> observed = new CompletableFuture<>();
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        context.runOnContext(v -> {
            callerThread.set(Thread.currentThread());
            client.execute(web -> web.get("/ctx").send().map(HttpResponse::bodyAsString))
                    .onComplete(ar -> {
                        Context completing = Vertx.currentContext();
                        boolean onCaller = completing != null
                                && "caller".equals(completing.get("marker"))
                                && Thread.currentThread() == callerThread.get();
                        observed.complete(ar.succeeded() ? (onCaller ? "on-caller-context" : "elsewhere") : "failed");
                    });
        });
        assertThat(await(Future.fromCompletionStage(observed))).isEqualTo("on-caller-context");
    }

    @Test
    @DisplayName("closing a handed-out WebClient leaves the client usable")
    void closingAHandedOutWebClientLeavesTheClientUsable() {
        // Given a client and a server answering GET /ping with "ok" and counting requests
        RecordingServer server = support.server((rq, body) -> rq.response().end("ok"));
        HttpClient client = client(server.origin());

        // When an attempt closes its scoped WebClient and the caller closes client.webClient()
        AtomicReference<Throwable> attemptClose = new AtomicReference<>();
        await(client.execute(web -> {
            try {
                web.close();
            } catch (Throwable t) {
                attemptClose.set(t);
            }
            return Future.succeededFuture("closed");
        }));
        assertThat(attemptClose.get())
                .as("scoped close completes without error")
                .isNull();
        assertThatCode(() -> client.webClient().close()).doesNotThrowAnyException();

        // And then runs execute and a webClient() request
        Future<String> viaExecute = client.execute(w -> w.get("/ping").send().map(HttpResponse::bodyAsString));
        HttpResponse<Buffer> viaWebClient =
                await(client.webClient().get("/ping").send());

        // Then both later requests succeed and the server received 2 requests
        assertThat(await(viaExecute)).isEqualTo("ok");
        assertThat(viaWebClient.statusCode()).isEqualTo(200);
        assertThat(server.requests).hasSize(2);
    }

    @Test
    @DisplayName("adding an interceptor to a handed-out WebClient is refused and the governance hook is unchanged")
    void addingAnInterceptorToAHandedOutWebClientIsRefused() {
        // Given a client, a server counting requests and a handler recording its invocations
        RecordingServer server = okServer();
        HttpClient client = client(server.origin());
        AtomicInteger invoked = new AtomicInteger();
        Handler<HttpContext<?>> handler = ctx -> {
            invoked.incrementAndGet();
            ctx.next();
        };

        // When the handler is added to client.webClient() and to the WebClient handed to an attempt
        WebClient direct = client.webClient();
        Throwable onDirect = null;
        try {
            direct.addInterceptor(handler);
        } catch (Throwable t) {
            onDirect = t;
        }
        AtomicReference<Throwable> onAttempt = new AtomicReference<>();
        AtomicReference<Throwable> attemptSsl = new AtomicReference<>();
        AtomicReference<Future<Rejection>> attemptBadHeader = new AtomicReference<>();
        Future<String> attempt = client.execute(web -> {
            try {
                web.addInterceptor(handler);
            } catch (Throwable t) {
                onAttempt.set(t);
            }
            attemptSsl.set(updateSslOptionsFailure(web));
            attemptBadHeader.set(rejection(getWithHeader(web, "X-A", "a\r\nX-B: b")));
            return attemptBadHeader.get().compose(r -> web.get("/").send()).map(HttpResponse::bodyAsString);
        });

        // Then each call throws UnsupportedOperationException with a constant message and no cause
        assertThat(await(attempt)).isEqualTo("ok");
        assertThat(onDirect).isInstanceOf(UnsupportedOperationException.class);
        assertThat(onAttempt.get()).isInstanceOf(UnsupportedOperationException.class);
        for (Throwable refused : List.of(onDirect, onAttempt.get())) {
            assertThat(refused.getCause()).isNull();
            assertThat(refused.getMessage()).isNotBlank();
        }
        assertThat(onDirect.getMessage()).isEqualTo(onAttempt.get().getMessage());

        // And the handler is invoked 0 times by any later request
        await(direct.get("/").send());
        assertThat(invoked.get()).isZero();

        // And updateSSLOptions(trustAll) on each of them throws UnsupportedOperationException with a
        // constant message and no cause
        Throwable sslOnDirect = updateSslOptionsFailure(direct);
        for (Throwable refused : java.util.Arrays.asList(sslOnDirect, attemptSsl.get())) {
            assertThat(refused).isInstanceOf(UnsupportedOperationException.class);
            assertThat(refused.getCause()).isNull();
            assertThat(refused.getMessage()).isNotBlank();
        }
        assertThat(sslOnDirect.getMessage()).isEqualTo(attemptSsl.get().getMessage());

        // And the governance hook is unchanged: an invalid header is still rejected without reaching a
        // server (through the attempt's WebClient above and through client.webClient() here)
        Rejection invalid = awaitRejection(getWithHeader(direct, "X-A", "a\r\nX-B: b"));
        assertThat(invalid.cause()).isInstanceOf(IllegalArgumentException.class);
        assertThat(await(attemptBadHeader.get()).cause()).isInstanceOf(IllegalArgumentException.class);
        // and a request without the invalid header succeeds
        assertThat(await(direct.get("/").send()).statusCode()).isEqualTo(200);
        assertThat(server.requests)
                .as("only the valid requests reached the server")
                .hasSize(3);

        // And after the refused trust-all update a request to a self-signed HTTPS server that the
        // builder does not trust still fails the TLS handshake and the server records no request
        RecordingServer tls = support.tlsServer((rq, body) -> rq.response().end("secret"));
        HttpClient tlsClient = client(tls.origin());
        Throwable handshakeBefore = awaitFailure(tlsClient.webClient().get("/").send());
        assertThat(handshakeBefore).isNotInstanceOf(UnsupportedOperationException.class);
        assertThat(updateSslOptionsFailure(tlsClient.webClient())).isInstanceOf(UnsupportedOperationException.class);
        Throwable handshakeAfter = awaitFailure(tlsClient.webClient().get("/").send());
        assertThat(handshakeAfter).isNotInstanceOf(UnsupportedOperationException.class);
        AtomicReference<Throwable> tlsAttemptRefusal = new AtomicReference<>();
        Throwable handshakeInAttempt = awaitFailure(tlsClient.execute(web -> {
            tlsAttemptRefusal.set(updateSslOptionsFailure(web));
            return web.get("/").send();
        }));
        assertThat(tlsAttemptRefusal.get()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(handshakeInAttempt).isNotInstanceOf(UnsupportedOperationException.class);
        assertThat(tls.requests)
                .as("no request reached the untrusted HTTPS server")
                .isEmpty();
    }

    /** Calls {@code updateSSLOptions} with trust-all on the WebClient and returns what it threw, or null. */
    private static Throwable updateSslOptionsFailure(WebClient web) {
        try {
            web.updateSSLOptions(new ClientSSLOptions().setTrustAll(true), false);
            return null;
        } catch (Throwable thrown) {
            return thrown;
        }
    }
}
