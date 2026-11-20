// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitCondition;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.http.client.HttpClientItSupport.RecordedRequest;
import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientBuilder;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.impl.ClientPhase;
import io.vertx.ext.web.client.impl.HttpContext;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.codec.spi.BodyStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins the Vert.x 5.2.2 {@code WebClient} interceptor behavior the governed client builds on: the
 * order and content of the interceptor phases, reset after the response head, the request timeout
 * stopping at the head, the redirect handler of a wrapped agent, interceptor uniqueness per class,
 * re-preparation after a body-codec swap and the pre-send veto.
 *
 * <p>The tests drive raw Vert.x types on purpose. They use the Vert.x internal {@code HttpContext}
 * directly and say nothing about the governed client: a Vert.x upgrade that changes one of these
 * behaviors fails here first. They prove the hook behavior behind AC-001 and AC-002 (supporting).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WebClientHookContractIT {

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

    private Vertx vertx() {
        return support.vertx;
    }

    private HttpClientAgent agent(
            int port, PoolOptions pool, Function<HttpClientBuilder, HttpClientBuilder> customize) {
        HttpClientBuilder builder = vertx().httpClientBuilder()
                .with(new HttpClientOptions().setDefaultHost("127.0.0.1").setDefaultPort(port));
        if (pool != null) {
            builder = builder.with(pool);
        }
        HttpClientAgent agent = customize.apply(builder).build();
        support.track(agent::close);
        return agent;
    }

    private HttpClientAgent agent(RecordingServer server) {
        return agent(server.port(), null, b -> b);
    }

    private static WebClient web(HttpClientAgent agent, boolean followRedirects) {
        return WebClient.wrap(agent, new WebClientOptions().setFollowRedirects(followRedirects));
    }

    private static WebClient web(HttpClientAgent agent) {
        return web(agent, false);
    }

    private static void respondOk(HttpServerRequest request, String body) {
        request.response().end(body);
    }

    /** Server whose connections are counted and whose handler chooses the answer per path. */
    private RecordingServer server(RecordingServer.Responder responder) {
        return support.server(responder);
    }

    @Test
    @DisplayName("interceptor phases run in the order the governance relies on, with the state each phase offers")
    void interceptorPhasesRunInTheOrderTheGovernanceRelies() {
        // Given a wrapped agent with one recording interceptor and a server that answers 200
        RecordingServer server = server((rq, body) -> respondOk(rq, "ok"));
        List<ClientPhase> phases = new CopyOnWriteArrayList<>();
        AtomicReference<Object> prepareRequestOptions = new AtomicReference<>("unset");
        AtomicReference<RequestOptions> createRequestOptions = new AtomicReference<>();
        AtomicBoolean clientRequestAtCreate = new AtomicBoolean(true);
        AtomicBoolean clientRequestAtSend = new AtomicBoolean();
        AtomicBoolean clientResponseAtReceive = new AtomicBoolean();
        AtomicInteger statusAtReceive = new AtomicInteger();
        AtomicReference<Object> attributeAtDispatch = new AtomicReference<>();
        WebClient web = web(agent(server)).addInterceptor(ctx -> {
            ClientPhase phase = ctx.phase();
            phases.add(phase);
            switch (phase) {
                case PREPARE_REQUEST -> {
                    prepareRequestOptions.set(ctx.requestOptions());
                    ctx.set("attribute", "kept");
                    ctx.request().putHeader("x-added-in-prepare", "yes");
                }
                case CREATE_REQUEST -> {
                    createRequestOptions.set(ctx.requestOptions());
                    clientRequestAtCreate.set(ctx.clientRequest() != null);
                }
                case SEND_REQUEST -> clientRequestAtSend.set(ctx.clientRequest() != null);
                case RECEIVE_RESPONSE -> {
                    clientResponseAtReceive.set(ctx.clientResponse() != null);
                    statusAtReceive.set(ctx.clientResponse().statusCode());
                }
                case DISPATCH_RESPONSE -> attributeAtDispatch.set(ctx.get("attribute"));
                default -> {
                    // FOLLOW_REDIRECT and FAILURE are not part of a plain successful exchange
                }
            }
            ctx.next();
        });

        // When one GET completes
        HttpResponse<Buffer> response = await(web.get("/one").send());

        // Then the phases ran exactly once each in the order the governance depends on
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(phases)
                .containsExactly(
                        ClientPhase.PREPARE_REQUEST,
                        ClientPhase.CREATE_REQUEST,
                        ClientPhase.SEND_REQUEST,
                        ClientPhase.RECEIVE_RESPONSE,
                        ClientPhase.DISPATCH_RESPONSE);
        // And request preparation happens before the connect options exist and can still change the request
        assertThat(prepareRequestOptions.get()).isNull();
        assertThat(server.requests).hasSize(1);
        assertThat(server.requests.get(0).headers().get("x-added-in-prepare")).isEqualTo("yes");
        // And the connect address is visible in CREATE_REQUEST, before a request object exists
        assertThat(createRequestOptions.get()).isNotNull();
        assertThat(createRequestOptions.get().getHost()).isEqualTo("127.0.0.1");
        assertThat(createRequestOptions.get().getPort()).isEqualTo(server.port());
        assertThat(clientRequestAtCreate.get()).isFalse();
        // And the Vert.x request exists in SEND_REQUEST and the head exists in RECEIVE_RESPONSE
        assertThat(clientRequestAtSend.get()).isTrue();
        assertThat(clientResponseAtReceive.get()).isTrue();
        assertThat(statusAtReceive.get()).isEqualTo(200);
        // And per-exchange attributes survive from the first phase to the last
        assertThat(attributeAtDispatch.get()).isEqualTo("kept");
    }

    @Test
    @DisplayName("a failed exchange reports FAILURE with its cause and never reaches DISPATCH_RESPONSE")
    void failedExchangeEndsInFailurePhaseWithItsCause() {
        // Given a port with nothing listening
        RecordingServer closed = server((rq, body) -> respondOk(rq, "ok"));
        int deadPort = closed.port();
        await(closed.close());
        List<ClientPhase> phases = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        WebClient web = WebClient.wrap(agent(deadPort, null, b -> b), new WebClientOptions())
                .addInterceptor(ctx -> {
                    phases.add(ctx.phase());
                    if (ctx.phase() == ClientPhase.FAILURE) {
                        failure.set(ctx.failure());
                    }
                    ctx.next();
                });

        // When the request is sent
        Throwable error = awaitFailure(web.get("/x").send());

        // Then the failure phase saw the same cause the caller gets, and no response phase ran
        assertThat(phases).last().isEqualTo(ClientPhase.FAILURE);
        assertThat(phases).doesNotContain(ClientPhase.RECEIVE_RESPONSE, ClientPhase.DISPATCH_RESPONSE);
        assertThat(failure.get()).isSameAs(error);
    }

    @Test
    @DisplayName("a PREPARE_REQUEST interceptor can rewrite the request URI before the target is built")
    void prepareRequestCanRewriteTheRequestUri() {
        // Given an interceptor that prefixes the base path in PREPARE_REQUEST
        RecordingServer server = server((rq, body) -> respondOk(rq, "ok"));
        WebClient web = web(agent(server)).addInterceptor(ctx -> {
            if (ctx.phase() == ClientPhase.PREPARE_REQUEST) {
                ctx.request().uri("/api" + ctx.request().uri());
            }
            ctx.next();
        });

        // When a relative request is sent
        await(web.get("/v1/files?x=1").send());

        // Then the server saw the prefixed target
        assertThat(server.requests).extracting(RecordedRequest::target).containsExactly("GET /api/v1/files?x=1");
    }

    @Test
    @DisplayName(
            "reset after the head through clientResponse().request() stops the body and fails the exchange with the cause")
    void resetAfterTheHeadThroughClientResponseRequestStopsTheBody() {
        // Given a server that sends the head and then keeps writing chunks until a write fails
        AtomicInteger written = new AtomicInteger();
        AtomicBoolean writeFailed = new AtomicBoolean();
        RecordingServer server = server((rq, body) -> {
            var response = rq.response().setChunked(true);
            response.write("first");
            vertx().setPeriodic(25, id -> response.write("more").onComplete(ar -> {
                if (ar.failed()) {
                    writeFailed.set(true);
                    vertx().cancelTimer(id);
                } else {
                    written.incrementAndGet();
                }
            }));
        });
        AtomicReference<Runnable> resetter = new AtomicReference<>();
        IllegalStateException cause = new IllegalStateException("stop after the head");
        WebClient web = web(agent(server)).addInterceptor(ctx -> {
            if (ctx.phase() == ClientPhase.RECEIVE_RESPONSE) {
                resetter.set(() -> ctx.clientResponse().request().reset(0, cause));
            }
            ctx.next();
        });
        Future<HttpResponse<String>> exchange =
                web.get("/stream").as(BodyCodec.string()).send();
        assertThat(awaitCondition(() -> written.get() >= 3 && resetter.get() != null, 5_000))
                .as("the head arrived and the body is flowing")
                .isTrue();

        // When the interceptor-held response is used to reset the request
        resetter.get().run();

        // Then the caller's future fails with exactly that cause, the server's write fails and
        // its connection closes: the transfer stopped
        assertThat(awaitFailure(exchange)).isSameAs(cause);
        assertThat(awaitCondition(() -> writeFailed.get() && server.closedConnections.get() >= 1, 5_000))
                .as("server write failed and connection closed")
                .isTrue();
    }

    @Test
    @DisplayName("request timeout stops at the head, so a body deadline needs the client timer")
    void requestTimeoutStopsAtTheHeadSoABodyDeadlineNeedsTheClientTimer() {
        // Given a server that never answers /nohead and sends a head plus one chunk on /stallbody
        RecordingServer server = server((rq, body) -> {
            if (rq.path().equals("/stallbody")) {
                rq.response().setChunked(true).write("first");
            }
        });
        AtomicReference<Runnable> resetter = new AtomicReference<>();
        TimeoutException ourDeadline = new TimeoutException("client attempt timer");
        WebClient web = web(agent(server)).addInterceptor(ctx -> {
            if (ctx.phase() == ClientPhase.RECEIVE_RESPONSE) {
                resetter.set(() -> ctx.clientResponse().request().reset(0, ourDeadline));
            }
            ctx.next();
        });

        // When no head arrives within the request timeout
        Throwable beforeHead = awaitFailure(web.get("/nohead").timeout(200).send());

        // Then the request fails with a timeout and the connection is closed
        assertThat(beforeHead).isInstanceOf(TimeoutException.class);
        assertThat(awaitCondition(() -> server.closedConnections.get() >= 1, 5_000))
                .as("connection closed by the timeout")
                .isTrue();

        // And when the head arrives and the body then stalls past the same timeout
        Future<HttpResponse<String>> stalled =
                web.get("/stallbody").timeout(200).as(BodyCodec.string()).send();
        assertThat(awaitCondition(() -> resetter.get() != null, 5_000)).isTrue();
        assertThatThrownBy(
                        () -> stalled.toCompletionStage().toCompletableFuture().get(1_000, TimeUnit.MILLISECONDS))
                .as("the request timeout does not end an exchange whose head has arrived")
                .isInstanceOf(TimeoutException.class);
        assertThat(stalled.isComplete()).isFalse();

        // Then only the client's own timer, resetting through the response, ends it
        resetter.get().run();
        assertThat(awaitFailure(stalled)).isSameAs(ourDeadline);
    }

    @Test
    @DisplayName("redirect handler on the wrapped agent sees every hop and can stop or rewrite it")
    void redirectHandlerOnTheWrappedAgentSeesEveryHopAndCanStopOrRewriteIt() {
        // Given a server with a two-hop chain and a redirect that the handler will refuse
        RecordingServer server = server((rq, body) -> {
            switch (rq.path()) {
                case "/hop1" ->
                    rq.response()
                            .setStatusCode(302)
                            .putHeader("location", "/hop2")
                            .end();
                case "/hop2" ->
                    rq.response()
                            .setStatusCode(302)
                            .putHeader("location", "/hop3")
                            .end();
                case "/stop" ->
                    rq.response()
                            .setStatusCode(302)
                            .putHeader("location", "/never-followed")
                            .end();
                default -> rq.response().end("done");
            }
        });
        List<String> hops = new CopyOnWriteArrayList<>();
        AtomicInteger followed = new AtomicInteger();
        HttpClientAgent agent = agent(
                server.port(),
                null,
                builder -> builder.withRedirectHandler(response -> {
                    String from = response.request().getURI();
                    hops.add(response.statusCode() + " " + from + " -> " + response.getHeader("location"));
                    if (from.equals("/stop")) {
                        return null;
                    }
                    return Future.succeededFuture(new RequestOptions()
                            .setHost("127.0.0.1")
                            .setPort(server.port())
                            .setMethod(HttpMethod.GET)
                            .setURI(response.getHeader("location"))
                            .putHeader("x-rewritten", String.valueOf(followed.incrementAndGet())));
                }));
        WebClient web = web(agent, true);

        // When a request walks the chain and another meets the refused redirect
        HttpResponse<Buffer> chain = await(web.get("/hop1").send());
        HttpResponse<Buffer> stopped = await(web.get("/stop").send());

        // Then the handler saw each hop, and the rewrite reached the server
        assertThat(chain.bodyAsString()).isEqualTo("done");
        assertThat(hops).containsExactly("302 /hop1 -> /hop2", "302 /hop2 -> /hop3", "302 /stop -> /never-followed");
        assertThat(server.requestsTo("/hop2").get(0).headers().get("x-rewritten"))
                .isEqualTo("1");
        assertThat(server.requestsTo("/hop3").get(0).headers().get("x-rewritten"))
                .isEqualTo("2");
        // And returning null stops the chain: the caller gets the 3xx and the target is never requested
        assertThat(stopped.statusCode()).isEqualTo(302);
        assertThat(stopped.getHeader("location")).isEqualTo("/never-followed");
        assertThat(server.requestsTo("/never-followed")).isEmpty();
    }

    @Test
    @DisplayName(
            "addInterceptor refuses two interceptors of one class, and per-call wraps over one agent keep their own state")
    void addInterceptorRefusesTwoInterceptorsOfOneClass() {
        // Given one agent and a wrapped client carrying one CallBound interceptor
        RecordingServer server = server((rq, body) -> respondOk(rq, "ok"));
        HttpClientAgent agent = agent(server);
        AtomicInteger seenOne = new AtomicInteger();
        AtomicInteger seenTwo = new AtomicInteger();
        WebClient one = web(agent).addInterceptor(new CallBound("c1", seenOne));

        // When a second interceptor of the same class is added to the same client
        // Then it is refused
        assertThatThrownBy(() -> one.addInterceptor(new CallBound("dup", seenOne)))
                .isInstanceOf(IllegalStateException.class);
        // And one lambda call site added twice is two instances of one class and is refused too
        WebClient looped = web(agent);
        List<Handler<HttpContext<?>>> sameSite = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            sameSite.add(ctx -> ctx.next());
        }
        looped.addInterceptor(sameSite.get(0));
        assertThatThrownBy(() -> looped.addInterceptor(sameSite.get(1))).isInstanceOf(IllegalStateException.class);
        // And different classes on one client are accepted
        WebClient mixed = web(agent).addInterceptor(new CallBound("m", new AtomicInteger()));
        mixed.addInterceptor(new SecondKind());

        // And two wraps over the same agent each take their own instance of the same class
        WebClient two = web(agent).addInterceptor(new CallBound("c2", seenTwo));
        Future<HttpResponse<Buffer>> r1 = one.get("/call").send();
        Future<HttpResponse<Buffer>> r2 = two.get("/call").send();
        await(r1);
        await(r2);
        assertThat(server.requests).extracting(r -> r.headers().get("x-call")).containsExactlyInAnyOrder("c1", "c2");
        assertThat(seenOne.get()).isEqualTo(1);
        assertThat(seenTwo.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "codec swap re-runs earlier PREPARE interceptors, so governance prepares once per exchange when the swap is first")
    void codecSwapReRunsEarlierPrepareInterceptorsSoGovernancePreparesOncePerExchange() {
        // Given a server answering "hello-world" and two registration orders around a codec swap
        RecordingServer server = server((rq, body) -> respondOk(rq, "hello-world"));
        HttpClientAgent agent = agent(server);
        PrepareCounter beforeSwap = new PrepareCounter();
        CodecWrap swapAfterOther = new CodecWrap(1_000_000);
        PrepareCounterAfter afterSwap = new PrepareCounterAfter();
        WebClient other = web(agent)
                .addInterceptor(beforeSwap)
                .addInterceptor(swapAfterOther)
                .addInterceptor(afterSwap);

        PrepareCounterFirstOrder governance1 = new PrepareCounterFirstOrder();
        PrepareCounterSecondOrder governance2 = new PrepareCounterSecondOrder();
        CodecWrap swapFirst = new CodecWrap(1_000_000);
        WebClient governed =
                web(agent).addInterceptor(swapFirst).addInterceptor(governance1).addInterceptor(governance2);

        // When one request is sent through each
        HttpResponse<String> viaOther =
                await(other.get("/hello").as(BodyCodec.string()).send());
        HttpResponse<String> viaGoverned =
                await(governed.get("/hello").as(BodyCodec.string()).send());

        // Then an interceptor registered before the swap saw PREPARE_REQUEST twice, the one after it once
        assertThat(beforeSwap.prepare.get()).isEqualTo(2);
        assertThat(afterSwap.prepare.get()).isEqualTo(1);
        // And with the swap registered first every later interceptor prepared exactly once
        assertThat(governance1.prepare.get()).isEqualTo(1);
        assertThat(governance2.prepare.get()).isEqualTo(1);
        // And the codec ran exactly once per exchange and the body arrived intact through the wrapper
        assertThat(swapAfterOther.wraps.get()).isEqualTo(1);
        assertThat(swapFirst.wraps.get()).isEqualTo(1);
        assertThat(viaOther.body()).isEqualTo("hello-world");
        assertThat(viaGoverned.body()).isEqualTo("hello-world");
        assertThat(swapAfterOther.counted.get()).isEqualTo("hello-world".length());
        assertThat(swapFirst.counted.get()).isEqualTo("hello-world".length());
    }

    @Test
    @DisplayName(
            "wrapping a caller-chosen BodyCodec with a counting BodyStream keeps the codec's result type and resets on overrun")
    void wrappingACallerChosenCodecCountsBytesAndResetsOnOverrun() {
        // Given a server with a small JSON body and a 64 KiB body, and a 8 KiB bound in the wrapper
        RecordingServer server = server((rq, body) -> {
            switch (rq.path()) {
                case "/json" ->
                    rq.response()
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("pad", "x".repeat(40)).encode());
                case "/big" -> {
                    rq.response().setChunked(true);
                    for (int i = 0; i < 64; i++) {
                        rq.response().write("y".repeat(1024));
                    }
                    rq.response().end();
                }
                default -> rq.response().end("ok");
            }
        });
        CodecWrap wrap = new CodecWrap(8 * 1024);
        HttpClientAgent agent = agent(server.port(), new PoolOptions().setHttp1MaxSize(1), b -> b);
        WebClient web = web(agent).addInterceptor(wrap);

        // When a JSON codec is used under the bound
        HttpResponse<JsonObject> json =
                await(web.get("/json").as(BodyCodec.jsonObject()).send());

        // Then the caller still gets the codec's own type and the bytes were counted
        assertThat(json.body().getString("pad")).hasSize(40);
        assertThat(wrap.counted.get()).isPositive();

        // And when string and default buffer reads exceed the bound
        Throwable overString =
                awaitFailure(web.get("/big").as(BodyCodec.string()).send());
        Throwable overBuffer = awaitFailure(web.get("/big").send());

        // Then each fails with the wrapper's cause, and the single pooled connection stays usable
        assertThat(overString).isInstanceOf(IllegalStateException.class).hasMessage("BODY_LIMIT");
        assertThat(overBuffer).isInstanceOf(IllegalStateException.class).hasMessage("BODY_LIMIT");
        assertThat(await(web.get("/ok").send()).bodyAsString()).isEqualTo("ok");
        assertThat(wrap.wraps.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("a SEND_REQUEST veto resets without writing, also for a request queued behind the pool")
    void sendRequestVetoResetsWithoutWriting() {
        // Given a pool of one connection and an interceptor that vetoes marked requests when told to
        RecordingServer server = server((rq, body) -> {
            if (rq.path().equals("/slow")) {
                vertx().setTimer(500, id -> rq.response().end("slow"));
            } else {
                rq.response().end("ok");
            }
        });
        Veto veto = new Veto();
        HttpClientAgent agent = agent(server.port(), new PoolOptions().setHttp1MaxSize(1), b -> b);
        WebClient web = web(agent).addInterceptor(veto);
        await(web.get("/warm").send());
        int served = server.requests.size();

        // When a marked request is vetoed in SEND_REQUEST
        veto.enabled.set(true);
        Throwable vetoed =
                awaitFailure(web.post("/marked").putHeader("x-veto", "1").sendBuffer(Buffer.buffer("payload")));

        // Then it fails with the veto's cause and nothing reaches the server
        assertThat(vetoed).isInstanceOf(IllegalStateException.class).hasMessage("VETO");
        assertThat(awaitCondition(() -> server.requests.size() > served, 300))
                .as("the vetoed request must not be written")
                .isFalse();

        // And the pool still serves the next request
        veto.enabled.set(false);
        assertThat(await(web.get("/after").send()).statusCode()).isEqualTo(200);

        // And a request queued behind a slow one is vetoed once the decision changes while it waits
        int servedBeforeQueue = server.requests.size();
        Future<HttpResponse<Buffer>> slow = web.get("/slow").send();
        assertThat(awaitCondition(() -> server.requests.size() > servedBeforeQueue, 5_000))
                .isTrue();
        Future<HttpResponse<Buffer>> queued =
                web.post("/queued").putHeader("x-veto", "1").sendBuffer(Buffer.buffer("payload"));
        veto.enabled.set(true);
        assertThat(await(slow).bodyAsString()).isEqualTo("slow");
        assertThat(awaitFailure(queued))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("VETO");
        assertThat(awaitCondition(() -> !server.requestsTo("/queued").isEmpty(), 300))
                .as("the queued request must never be written")
                .isFalse();
        veto.enabled.set(false);
        assertThat(await(web.get("/final").send()).statusCode()).isEqualTo(200);
    }

    // --- interceptors under test ---

    /** Per-call interceptor: one class, a new instance per wrapped client. */
    static final class CallBound implements Handler<HttpContext<?>> {
        private final String callId;
        private final AtomicInteger seen;

        CallBound(String callId, AtomicInteger seen) {
            this.callId = callId;
            this.seen = seen;
        }

        @Override
        public void handle(HttpContext<?> ctx) {
            if (ctx.phase() == ClientPhase.PREPARE_REQUEST) {
                seen.incrementAndGet();
                ctx.request().putHeader("x-call", callId);
            }
            ctx.next();
        }
    }

    /** A second class, to show that distinct classes coexist on one client. */
    static final class SecondKind implements Handler<HttpContext<?>> {
        @Override
        public void handle(HttpContext<?> ctx) {
            ctx.next();
        }
    }

    /** Counts PREPARE_REQUEST passes; registered before the codec swap. */
    static final class PrepareCounter implements Handler<HttpContext<?>> {
        final AtomicInteger prepare = new AtomicInteger();

        @Override
        public void handle(HttpContext<?> ctx) {
            if (ctx.phase() == ClientPhase.PREPARE_REQUEST) {
                prepare.incrementAndGet();
            }
            ctx.next();
        }
    }

    /** Counts PREPARE_REQUEST passes; registered after the codec swap. */
    static final class PrepareCounterAfter implements Handler<HttpContext<?>> {
        final AtomicInteger prepare = new AtomicInteger();

        @Override
        public void handle(HttpContext<?> ctx) {
            if (ctx.phase() == ClientPhase.PREPARE_REQUEST) {
                prepare.incrementAndGet();
            }
            ctx.next();
        }
    }

    /** First governance step registered after a swap that comes first. */
    static final class PrepareCounterFirstOrder implements Handler<HttpContext<?>> {
        final AtomicInteger prepare = new AtomicInteger();

        @Override
        public void handle(HttpContext<?> ctx) {
            if (ctx.phase() == ClientPhase.PREPARE_REQUEST) {
                prepare.incrementAndGet();
            }
            ctx.next();
        }
    }

    /** Second governance step registered after a swap that comes first. */
    static final class PrepareCounterSecondOrder implements Handler<HttpContext<?>> {
        final AtomicInteger prepare = new AtomicInteger();

        @Override
        public void handle(HttpContext<?> ctx) {
            if (ctx.phase() == ClientPhase.PREPARE_REQUEST) {
                prepare.incrementAndGet();
            }
            ctx.next();
        }
    }

    /**
     * PREPARE_REQUEST: swaps the request's codec for a wrapper that counts bytes, by re-preparing
     * with {@code request.as(wrapped)}. A context attribute guards against wrapping twice.
     */
    static final class CodecWrap implements Handler<HttpContext<?>> {
        private final long max;
        final AtomicInteger wraps = new AtomicInteger();
        final AtomicInteger counted = new AtomicInteger();

        CodecWrap(long max) {
            this.max = max;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void handle(HttpContext<?> context) {
            HttpContext ctx = context;
            if (ctx.phase() == ClientPhase.PREPARE_REQUEST && ctx.get("wrapped") == null) {
                ctx.set("wrapped", Boolean.TRUE);
                wraps.incrementAndGet();
                HttpRequest request = ctx.request();
                BodyCodec inner = request.bodyCodec();
                BodyCodec wrapped = () -> new Counting(inner.stream(), max, counted, () -> {
                    var response = ctx.clientResponse();
                    if (response != null) {
                        response.request().reset(0, new IllegalStateException("BODY_LIMIT"));
                    }
                });
                ctx.prepareRequest(request.as(wrapped), ctx.contentType(), ctx.body());
                return;
            }
            ctx.next();
        }
    }

    /** Counting {@link BodyStream} delegate; calls {@code onOver} once when the bound is crossed. */
    static final class Counting<T> implements BodyStream<T> {
        private final BodyStream<T> delegate;
        private final long max;
        private final AtomicInteger counted;
        private final Runnable onOver;
        private long total;
        private boolean over;

        Counting(BodyStream<T> delegate, long max, AtomicInteger counted, Runnable onOver) {
            this.delegate = delegate;
            this.max = max;
            this.counted = counted;
            this.onOver = onOver;
        }

        @Override
        public Future<T> result() {
            return delegate.result();
        }

        @Override
        public void handle(Throwable failure) {
            delegate.handle(failure);
        }

        @Override
        public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            delegate.exceptionHandler(handler);
            return this;
        }

        @Override
        public Future<Void> write(Buffer data) {
            total += data.length();
            counted.addAndGet(data.length());
            if (total > max) {
                if (!over) {
                    over = true;
                    onOver.run();
                }
                return Future.failedFuture("over");
            }
            return delegate.write(data);
        }

        @Override
        public Future<Void> end() {
            return delegate.end();
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            delegate.setWriteQueueMaxSize(maxSize);
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return delegate.writeQueueFull();
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            delegate.drainHandler(handler);
            return this;
        }
    }

    /** SEND_REQUEST: vetoes requests carrying {@code x-veto} while enabled, before anything is written. */
    static final class Veto implements Handler<HttpContext<?>> {
        final AtomicBoolean enabled = new AtomicBoolean();

        @Override
        public void handle(HttpContext<?> ctx) {
            if (ctx.phase() == ClientPhase.SEND_REQUEST
                    && enabled.get()
                    && ctx.request().headers().contains("x-veto")) {
                Throwable cause = new IllegalStateException("VETO");
                ctx.clientRequest().reset(0, cause);
                ctx.fail(cause);
                return;
            }
            ctx.next();
        }
    }
}
