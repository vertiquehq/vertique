// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Pins the Vert.x client-side hazard behind this module's "correct status, empty body" IT flakes,
 * and pins the request idiom that is immune to it.
 *
 * <p><strong>The hazard.</strong> {@link HttpClientResponse} discards body buffers delivered before
 * a body handler is attached. A request that initiates the send and only then attaches the body
 * read — {@code client.request(..).compose(req -> req.send()).compose(resp -> resp.body())} — leaves
 * a window in which the whole response can arrive unobserved. The window is bounded by the
 * <em>caller thread's</em> scheduling rather than by anything the test controls, so a loaded CI
 * runner turns a rarity into a routine event: {@code body()} then completes <em>successfully</em>
 * with zero bytes while {@code statusCode()} carries whatever the server sent. Silent truncation is
 * the worst shape this can take — it surfaces as an assertion mismatch or a JSON decode error far
 * from its cause.
 *
 * <p><strong>The idiom.</strong> Attach the complete response continuation — including the body
 * read — to {@link HttpClientRequest#response()} <em>before</em> initiating the send, and end the
 * request with {@code end()} rather than {@code send()}. The continuation then exists before the
 * request leaves, so the body read is attached on the same event-loop tick that delivers the head,
 * ahead of any buffered body data. The send's own completion is deliberately not composed into the
 * result: a response the server did send must never be masked by a write failure.
 *
 * <p><strong>On the upgrade tripwire.</strong> {@link #lateAttachedBodyReadLosesTheBody()} asserts
 * the hazard <em>exists</em>. If a future Vert.x upgrade makes it fail, the drop behaviour is gone:
 * delete this test in the upgrade change and relax the testing rule it pins. Its failure is
 * information about the runtime, never a reason to hold back an upgrade.
 * {@link #preAttachedReadDeliversFullBodyUnderWorkerParking()} asserts the idiom's guarantee and
 * stays valid on any runtime, as does
 * {@link #deliveredResponseSettlesResultWhileSendIsStillPending()}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class HttpClientBodyReadRaceIT {

    /** Loopback host for both bind and dial; never a hostname (ADR-tracked loopback pinning). */
    private static final String LOOPBACK = "127.0.0.1";

    /** Deliberately split across two writes plus the terminal end, so a partial read is detectable. */
    private static final String CHUNK_ONE = "first-chunk-0123456789";

    private static final String CHUNK_TWO = "second-chunk-abcdefghij";

    private static final String CHUNK_THREE = "third-chunk-!";

    private static final String FULL_BODY = CHUNK_ONE + CHUNK_TWO + CHUNK_THREE;

    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    /**
     * Iterations of the parking loop. The racy shape this guards against loses the body on the large
     * majority of parked attempts, so a regression cannot hide behind a loop this long.
     */
    private static final int PARKED_ITERATIONS = 50;

    /** Caller-thread park, standing in for a test worker descheduled on a loaded CI runner. */
    private static final long PARK_MILLIS = 2;

    /** Path answered with an immediate final rejection, for the send-outcome test. */
    private static final String REJECT_PATH = "/reject";

    /**
     * Request body for the send-outcome test, far larger than any socket and kernel buffer can
     * absorb. Because the server for {@link #REJECT_PATH} never reads the body, the send can never
     * complete — which is what makes that test's ordering structural rather than timed.
     */
    private static final Buffer UNREADABLE_BODY = Buffer.buffer(new byte[32 * 1024 * 1024]);

    private static HttpServer server;
    private static HttpClient client;

    /**
     * Starts one server and one shared client for the whole class, per the async-IT resource rules.
     *
     * @param vertx the class-scoped Vert.x instance injected by vertx-junit5
     * @param ctx   the test context used to signal setup completion
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        vertx.createHttpServer()
                .requestHandler(HttpClientBodyReadRaceIT::respond)
                .listen(0, LOOPBACK)
                .onComplete(ctx.succeeding(listening -> {
                    server = listening;
                    client = vertx.createHttpClient();
                    ctx.completeNow();
                }));
    }

    /**
     * Closes the shared client and server.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future.join(clientClose, serverClose).onComplete(ar -> ctx.completeNow());
    }

    /**
     * Answers {@link #REJECT_PATH} with an immediate final rejection whose body is never read, and
     * every other request with {@link #FULL_BODY} written as three chunks.
     *
     * @param request the incoming request
     */
    private static void respond(HttpServerRequest request) {
        if (REJECT_PATH.equals(request.path())) {
            // Decide on the head alone and never drain the body: pausing the request stream is what
            // strands the client's send, and answering without closing is what keeps the response
            // intact. Closing here instead would race the client's read of this very response.
            request.pause();
            request.response().setStatusCode(413).end("rejected");
            return;
        }
        HttpServerResponse response = request.response();
        response.setChunked(true);
        response.write(CHUNK_ONE);
        response.write(CHUNK_TWO);
        response.end(CHUNK_THREE);
    }

    // --- The hazard the idiom exists to avoid ---

    @Test
    @DisplayName("A body read attached after the response arrived silently yields an empty body")
    void lateAttachedBodyReadLosesTheBody() throws Exception {
        // Capture the event loop the response is delivered on: the callback below runs on it.
        AtomicReference<Context> connectionContext = new AtomicReference<>();
        HttpClientResponse response = await(client.request(HttpMethod.GET, server.actualPort(), LOOPBACK, "/")
                .compose(HttpClientRequest::send)
                .andThen(delivered -> connectionContext.set(Vertx.currentContext())));

        assertNotNull(connectionContext.get(), "the response callback must run on a Vert.x context");
        // Let one full turn of THAT event loop elapse before reading. Because the head callback ran
        // inside the read event that carried the response, a task scheduled on the same loop cannot
        // run until that read event — head, every body chunk, and end — has been fully dispatched.
        // The body was therefore delivered with no handler attached, and dropped. This ordering is
        // structural, not a timing gamble: no sleep can substitute for it and none is used.
        awaitTurnOn(connectionContext.get());

        Buffer body = await(response.body());

        assertEquals(200, response.statusCode(), "the status rides the head and is never lost");
        assertEquals(
                0,
                body.length(),
                "Vert.x discards body buffers delivered before a handler is attached and reports the "
                        + "truncation as SUCCESS. If this fails, the runtime no longer drops pre-handler "
                        + "body data: delete this test and relax the testing rule it pins");
    }

    // --- The idiom that is immune to it ---

    @Test
    @DisplayName("A pre-attached body read delivers every byte even when the caller thread is parked")
    void preAttachedReadDeliversFullBodyUnderWorkerParking() throws Exception {
        for (int iteration = 0; iteration < PARKED_ITERATIONS; iteration++) {
            Future<HttpResult> result = get();
            // Park with the request already in flight. Under the racy shape this is exactly the
            // window in which the response arrives unobserved; under the idiom the body read is
            // already attached, so parking here must make no difference at all.
            Thread.sleep(PARK_MILLIS);

            HttpResult received = await(result);

            assertEquals(200, received.statusCode(), "iteration " + iteration + " lost the status");
            assertEquals(FULL_BODY, received.body().toString(), "iteration " + iteration + " lost body bytes");
        }
    }

    // --- The send's outcome must not decide the result ---

    @Test
    @DisplayName("A delivered response settles the result even though the send has not completed")
    void deliveredResponseSettlesResultWhileSendIsStillPending() throws Exception {
        AtomicReference<Future<Void>> send = new AtomicReference<>();
        AtomicReference<HttpConnection> connection = new AtomicReference<>();

        Future<HttpResult> result = client.request(HttpMethod.POST, server.actualPort(), LOOPBACK, REJECT_PATH)
                .compose(request -> {
                    // The idiom verbatim: attach the whole continuation, then initiate the send.
                    Future<HttpResult> pending = request.response().compose(response -> response.body()
                            .map(body -> new HttpResult(response.statusCode(), body)));
                    connection.set(request.connection());
                    send.set(request.end(UNREADABLE_BODY));
                    return pending;
                });

        // Load-bearing: the server answered on the head and is not reading the body, so the send
        // cannot finish. Had the send's future been composed into the result chain, the result could
        // never settle and this await would time the test out — which is what makes the assertions
        // below a real check rather than a restatement of the happy path.
        HttpResult received = await(result);

        assertEquals(413, received.statusCode(), "the answer the server did send must reach the caller");
        assertFalse(
                send.get().isComplete(),
                "the result must be decided by the response alone; the send had not settled yet");

        // Tearing the connection down now fails the stranded send. Observing that failure after the
        // fact is the point: a send that ends in failure still did not mask the response above.
        connection.get().close();
        assertFalse(send.get().succeeded(), "a send the server never read must never report success");
    }

    // --- Harness ---

    /**
     * Issues a GET through the idiom under test: the response continuation, including the body read,
     * is attached before {@code end()} initiates the send.
     *
     * @return the status and fully-read body of the response
     */
    private static Future<HttpResult> get() {
        return client.request(HttpMethod.GET, server.actualPort(), LOOPBACK, "/")
                .compose(request -> {
                    Future<HttpResult> result = request.response().compose(response -> response.body()
                            .map(body -> new HttpResult(response.statusCode(), body)));
                    request.end();
                    return result;
                });
    }

    /**
     * Blocks the caller thread until one full turn of {@code context} has elapsed.
     *
     * @param context the event-loop context to synchronize against
     * @throws Exception if the turn did not complete in time
     */
    private static void awaitTurnOn(Context context) throws Exception {
        Promise<Void> turn = Promise.promise();
        context.runOnContext(ignored -> turn.complete());
        await(turn.future());
    }

    /**
     * Blocks the caller thread until {@code future} settles.
     *
     * @param future the future to await
     * @param <T>    the future's value type
     * @return the future's value
     * @throws Exception if the future failed or did not settle in time
     */
    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Status and fully-read body of one exchange.
     *
     * @param statusCode the response status
     * @param body       the fully-read response body
     */
    private record HttpResult(int statusCode, Buffer body) {}
}
