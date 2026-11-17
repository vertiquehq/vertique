// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitCondition;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import dev.vertique.http.client.exception.HttpClientTechnicalException;
import dev.vertique.http.client.exception.HttpStatusRejectedException;
import dev.vertique.resilience.RetryDelayHint;
import dev.vertique.resilience.exception.BulkheadQueueTimeoutException;
import dev.vertique.resilience.exception.BulkheadRejectedException;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import dev.vertique.resilience.exception.ResiliencePolicyFailureReason;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.net.NetServer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.codec.BodyCodec;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests of status expectations and of the failures that stay native: a refused status
 * becomes a typed rejection, every transport failure reaches the caller as the Vert.x or JDK
 * throwable.
 *
 * <p>Every test runs against a real Vert.x server on {@code 127.0.0.1} and a dynamic port.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StatusExpectationIT {

    private static final String FAILURE_PACKAGE = "dev.vertique.http.client.exception";

    /** A valid derived resilience key. */
    private static final String KEY = "op:" + "0123456789abcdef".repeat(4);

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

    private VertiqueHttpClient client(String baseUrl) {
        return client(baseUrl, null);
    }

    private VertiqueHttpClient client(String baseUrl, PoolOptions pool) {
        VertiqueHttpClientBuilder builder = VertiqueHttpClientBuilder.create(support.vertx);
        support.track(builder::close);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        if (pool != null) {
            builder.poolOptions(pool);
        }
        VertiqueHttpClient client = builder.build();
        support.track(client::close);
        return client;
    }

    /** A body of {@code size} bytes whose first bytes are readable text and whose bytes differ by offset. */
    private static Buffer body(int size) {
        StringBuilder text = new StringBuilder("BODYTEXT-");
        int i = 0;
        while (text.length() < size) {
            text.append((char) ('a' + (i++ % 26)));
        }
        return Buffer.buffer(text.substring(0, size));
    }

    /** A loopback port nothing listens on. */
    private static int unusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    /** Raw TCP server that runs {@code onConnect} for each accepted socket. */
    private static NetServer rawServer(java.util.function.Consumer<io.vertx.core.net.NetSocket> onConnect) {
        NetServer server = support.vertx.createNetServer().connectHandler(onConnect::accept);
        await(server.listen(0, "127.0.0.1"));
        support.track(server::close);
        return server;
    }

    private static void assertNotAFrameworkFailure(Throwable failure) {
        assertThat(failure.getClass().getName()).doesNotStartWith(FAILURE_PACKAGE);
        assertThat(failure).isNotInstanceOf(HttpClientTechnicalException.class);
    }

    // --- tests ---

    @Test
    @DisplayName("AC-009: a rejected 429 carries status, headers without Set-Cookie and Retry-After")
    void rejected429CarriesStatusHeadersWithoutSetCookieAndRetryAfter() {
        // Given a server answering GET /limited with 429, Retry-After 7, X-Req r1, Set-Cookie and a 5000-byte body
        Buffer payload = body(5000);
        RecordingServer server = support.server((rq, body) -> rq.response()
                .setStatusCode(429)
                .putHeader("Retry-After", "7")
                .putHeader("X-Req", "r1")
                .putHeader("Set-Cookie", "s=1")
                .end(payload));
        VertiqueHttpClient client = client(server.origin());

        // When the caller runs the request with HttpExpectations.success()
        Throwable failure = awaitFailure(client.execute(web ->
                web.get("/limited").send().expecting(HttpExpectations.success()).map(HttpResponse::statusCode)));

        // Then it fails with a status rejection carrying 429, the headers, Retry-After and a read-only excerpt
        assertThat(failure).isInstanceOf(HttpStatusRejectedException.class).isInstanceOf(RetryDelayHint.class);
        HttpStatusRejectedException rejected = (HttpStatusRejectedException) failure;
        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.retryAfter()).isEqualTo(Optional.of(Duration.ofSeconds(7)));
        assertThat(rejected.headers().get("X-Req")).isEqualTo("r1");
        assertThat(rejected.headers().getAll("Set-Cookie")).isEmpty();
        assertThatThrownBy(() -> rejected.headers().add("X-New", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(rejected.bodyExcerpt()).isPresent();
        Buffer excerpt = rejected.bodyExcerpt().orElseThrow();
        assertThat(excerpt.length()).isEqualTo(2048);
        assertThat(excerpt.getBytes()).isEqualTo(payload.getBuffer(0, 2048).getBytes());
        excerpt.setByte(0, (byte) (excerpt.getByte(0) + 1));
        assertThat(rejected.bodyExcerpt().orElseThrow().getBytes())
                .isEqualTo(payload.getBuffer(0, 2048).getBytes());
        assertThat(rejected.getMessage())
                .doesNotContain("r1")
                .doesNotContain("s=1")
                .doesNotContain("/limited");
        assertThat(rejected.getMessage()).doesNotContain("BODYTEXT");
        assertThat(rejected.toString())
                .doesNotContain("r1")
                .doesNotContain("/limited")
                .doesNotContain("BODYTEXT");

        // And the same request read with BodyCodec.none() has an empty excerpt
        Throwable unread = awaitFailure(client.execute(web -> web.get("/limited")
                .as(BodyCodec.none())
                .send()
                .expecting(HttpExpectations.success())
                .map(HttpResponse::statusCode)));
        assertThat(unread).isInstanceOf(HttpStatusRejectedException.class);
        assertThat(((HttpStatusRejectedException) unread).statusCode()).isEqualTo(429);
        assertThat(((HttpStatusRejectedException) unread).bodyExcerpt()).isEmpty();
    }

    @Test
    @DisplayName("AC-012: a rejected 503 message holds no URI path, query, header value or body text")
    void aRejected503MessageHoldsNoUriPathQueryHeaderValueOrBodyText() {
        // Given a server answering GET /secret/path?token=s3cret with 503, X-Key s3cret and body s3cret-body
        RecordingServer server = support.server((rq, body) ->
                rq.response().setStatusCode(503).putHeader("X-Key", "s3cret").end("s3cret-body"));
        VertiqueHttpClient client = client(server.origin());

        // When the caller sends it with HttpExpectations.success()
        Throwable failure = awaitFailure(client.execute(web -> web.get("/secret/path?token=s3cret")
                .send()
                .expecting(HttpExpectations.success())
                .map(HttpResponse::statusCode)));

        // Then the rejection's message and toString contain none of the secrets
        assertThat(failure).isInstanceOf(HttpStatusRejectedException.class);
        assertThat(((HttpStatusRejectedException) failure).statusCode()).isEqualTo(503);
        assertThat(failure.getMessage())
                .doesNotContain("s3cret")
                .doesNotContain("/secret/path")
                .doesNotContain("token")
                .doesNotContain("X-Key");
        assertThat(failure.toString())
                .doesNotContain("s3cret")
                .doesNotContain("/secret/path")
                .doesNotContain("token")
                .doesNotContain("X-Key");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    @DisplayName("AC-011: connection refused, an unresolvable host and a cut body surface as native throwables")
    void connectionDnsAndResetFailuresSurfaceAsNativeThrowables() throws Exception {
        // Given a closed local port, the host nohost.invalid and a server whose /cut promises 1000 bytes and sends 100
        int closedPort = unusedPort();
        RecordingServer cutServer = support.server((rq, body) -> {
            rq.response().putHeader("Content-Length", "1000");
            rq.response().write(Buffer.buffer(new byte[100])).onComplete(v -> rq.connection()
                    .close());
        });
        VertiqueHttpClient client = client(null);

        // When the caller sends the three requests through client.execute
        Throwable refused = awaitFailure(client.execute(
                web -> web.getAbs("http://127.0.0.1:" + closedPort + "/").send()));
        Throwable unresolved = awaitFailure(
                client.execute(web -> web.getAbs("http://nohost.invalid/").send()));
        Throwable cut = awaitFailure(
                client.execute(web -> web.getAbs(cutServer.origin() + "/cut").send()));

        // Then they fail with a ConnectException, an UnknownHostException and a native Vert.x closed failure
        assertThat(refused).isInstanceOf(ConnectException.class);
        assertThat(unresolved).isInstanceOf(UnknownHostException.class);
        assertThat(cut).isInstanceOf(HttpClosedException.class);

        // And none of them belongs to the framework's failure package
        assertNotAFrameworkFailure(refused);
        assertNotAFrameworkFailure(unresolved);
        assertNotAFrameworkFailure(cut);
    }

    @Test
    @DisplayName("FR-004: a status predicate accepts and refuses and success means 2xx")
    void statusPredicateAcceptsAndRefusesAndSuccessMeansTwoXx() {
        // Given a server answering the status carried in the path
        RecordingServer server = support.server((rq, body) -> rq.response()
                .setStatusCode(Integer.parseInt(rq.path().substring(1)))
                .end());
        VertiqueHttpClient client = client(server.origin());

        // When status(c == 404) checks a 404 and a 200, and success() checks 200, 204, 299, 300, 404 and 500

        // Then the predicate decides: 404 passes and 200 is rejected with its status
        int acceptedStatus = await(client.execute(web -> web.get("/404")
                .send()
                .expecting(HttpExpectations.status(c -> c == 404))
                .map(HttpResponse::statusCode)));
        assertThat(acceptedStatus).isEqualTo(404);
        Throwable refused200 = awaitFailure(client.execute(web -> web.get("/200")
                .send()
                .expecting(HttpExpectations.status(c -> c == 404))
                .map(HttpResponse::statusCode)));
        assertThat(refused200).isInstanceOf(HttpStatusRejectedException.class);
        assertThat(((HttpStatusRejectedException) refused200).statusCode()).isEqualTo(200);

        // And success() accepts exactly 2xx
        for (int accepted : List.of(200, 204, 299)) {
            int status = await(client.execute(web -> web.get("/" + accepted)
                    .send()
                    .expecting(HttpExpectations.success())
                    .map(HttpResponse::statusCode)));
            assertThat(status).isEqualTo(accepted);
        }
        for (int rejected : List.of(300, 404, 500)) {
            Throwable failure = awaitFailure(client.execute(web -> web.get("/" + rejected)
                    .send()
                    .expecting(HttpExpectations.success())
                    .map(HttpResponse::statusCode)));
            assertThat(failure).isInstanceOf(HttpStatusRejectedException.class);
            assertThat(((HttpStatusRejectedException) failure).statusCode()).isEqualTo(rejected);
        }
    }

    @Test
    @DisplayName("FR-005: resilience failures pass through unchanged")
    void resilienceFailuresPassThroughUnchanged() {
        // Given an attempt function that fails with each resilience exception
        List<RuntimeException> failures = List.of(
                new CircuitOpenException(KEY, KEY),
                new BulkheadRejectedException(KEY, 4),
                new BulkheadQueueTimeoutException(KEY, 100L),
                new ResilienceTimeoutException(KEY, 100L),
                new ResilienceClosedException(KEY),
                new ResiliencePolicyException(ResiliencePolicyFailureReason.INVALID_CONFIGURATION));
        VertiqueHttpClient client = client(null);

        for (RuntimeException original : failures) {
            // When the caller runs it through the client
            Throwable seen = awaitFailure(client.execute(web -> Future.failedFuture(original)));

            // Then the very same instance reaches the caller
            assertThat(seen).isSameAs(original);
        }
    }

    @Test
    @DisplayName("FR-005: a TLS handshake failure against a self-signed server surfaces as SSLHandshakeException")
    void tlsHandshakeFailureAgainstASelfSignedServerSurfacesAsSslHandshakeException() {
        // Given an HTTPS server with a self-signed certificate and a client with default trust
        RecordingServer server = support.tlsServer((rq, body) -> rq.response().end("ok"));
        VertiqueHttpClient client = client(null);

        // When the caller sends a request to it
        Throwable failure = awaitFailure(
                client.execute(web -> web.getAbs(server.origin() + "/").send()));

        // Then the native handshake failure reaches the caller and the server saw no request
        assertThat(failure).isInstanceOf(SSLHandshakeException.class);
        assertNotAFrameworkFailure(failure);
        assertThat(server.requests).isEmpty();
    }

    @Test
    @DisplayName("FR-005: an empty response after accept then close surfaces as the native closed failure")
    void emptyResponseAfterAcceptThenCloseSurfacesAsTheNativeClosedFailure() {
        // Given a raw server that accepts each connection, reads the request and closes without writing
        // (closing with the request unread would race a TCP reset against the close)
        NetServer server = rawServer(socket -> socket.handler(request -> socket.close()));
        VertiqueHttpClient client = client(null);

        // When the caller sends a request to it
        Throwable failure = awaitFailure(client.execute(web ->
                web.getAbs("http://127.0.0.1:" + server.actualPort() + "/").send()));

        // Then the native Vert.x closed failure reaches the caller
        assertThat(failure).isInstanceOf(HttpClosedException.class);
        assertNotAFrameworkFailure(failure);
    }

    @Test
    @DisplayName("FR-005: a truncated chunked body surfaces as the native closed failure")
    void truncatedChunkedBodySurfacesAsTheNativeClosedFailure() {
        // Given a raw server that sends a chunked head and one chunk, then closes without the last chunk
        NetServer server = rawServer(socket -> socket.handler(
                request -> socket.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n")
                        .onComplete(v -> socket.close())));
        VertiqueHttpClient client = client(null);

        // When the caller reads the whole body
        Throwable failure = awaitFailure(client.execute(web ->
                web.getAbs("http://127.0.0.1:" + server.actualPort() + "/").send()));

        // Then the native Vert.x closed failure reaches the caller
        assertThat(failure).isInstanceOf(HttpClosedException.class);
        assertNotAFrameworkFailure(failure);
    }

    @Test
    @DisplayName("FR-005: a per-request connect timeout bounds the pool wait with a native timeout")
    void perRequestConnectTimeoutBoundsThePoolWaitWithANativeTimeout() {
        // Given a pool of one connection held by a stalled request
        RecordingServer server = support.server((rq, body) -> {
            // never answers /stall; /second must never arrive
        });
        VertiqueHttpClient client = client(server.origin(), new PoolOptions().setHttp1MaxSize(1));
        AtomicLong waitedMs = new AtomicLong(-1);

        // When a second request with connectTimeout(200) waits for a connection inside the attempt
        Future<Void> result = client.execute(web -> {
            web.get("/stall").send();
            long start = System.nanoTime();
            return web.get("/second")
                    .connectTimeout(200)
                    .send()
                    .<Void>mapEmpty()
                    .onFailure(e -> waitedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
        });
        Throwable failure = awaitFailure(result);

        // Then a java.util.concurrent.TimeoutException reaches the caller within 200 ms plus 500 ms
        assertThat(failure).isInstanceOf(TimeoutException.class);
        assertNotAFrameworkFailure(failure);
        assertThat(waitedMs.get()).isBetween(0L, 700L);

        // And the stalled request held the connection, so the second request never reached the server
        assertThat(awaitCondition(() -> !server.requestsTo("/stall").isEmpty(), 2_000))
                .isTrue();
        assertThat(server.requestsTo("/second")).isEmpty();
    }
}
