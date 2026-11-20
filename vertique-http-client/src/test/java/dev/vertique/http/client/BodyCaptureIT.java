// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitCondition;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.core.payload.PayloadKind;
import dev.vertique.core.payload.PayloadSource;
import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpExchangeCancelledException;
import dev.vertique.http.client.exception.HttpStatusRejectedException;
import dev.vertique.http.client.interceptor.HttpClientAttemptCompletion;
import dev.vertique.http.client.interceptor.HttpClientInterceptor;
import dev.vertique.http.client.interceptor.HttpClientRequestContext;
import dev.vertique.http.client.interceptor.HttpClientResponseHead;
import dev.vertique.resilience.dagger.ResiliencePoliciesModule;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests of body capture: it is off by default, a client or its configuration turns it on, a
 * completed exchange hands the interceptor both bodies as written and received, a streamed or incomplete body
 * is reported as metadata only, a followed redirect reports the final hop, and bodies and credential headers are
 * unredacted while {@code toString} prints neither.
 *
 * <p>Every test dials only loopback addresses.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class BodyCaptureIT {

    private static final long LIMIT = 1024;

    /** How long the suite waits after the last expected notification to prove no second one arrives. */
    private static final long SETTLE_MS = 150;

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

    /** A payload as the interceptor saw it, copied inside the callback because the view dies with it. */
    record Body(PayloadKind kind, Optional<String> contentType, OptionalLong declaredLength, Optional<byte[]> bytes) {

        static Body copyOf(PayloadSource source) {
            return new Body(
                    source.kind(),
                    source.contentType(),
                    source.declaredLength(),
                    source.bufferedView().map(Buffer::getBytes));
        }

        String text() {
            return new String(bytes.orElseThrow(), StandardCharsets.UTF_8);
        }
    }

    /** One onAttemptCompleted call with both bodies copied and the printed form taken inside the callback. */
    record Seen(
            HttpClientRequestContext request,
            @Nullable HttpClientResponseHead response,
            HttpClientAttemptCompletion completion,
            Body requestBody,
            Body responseBody,
            String printed) {}

    /** An interceptor that records every completion with its copied bodies. */
    static final class Recorder implements HttpClientInterceptor {

        final List<Seen> seen = new CopyOnWriteArrayList<>();

        @Override
        public void onAttemptCompleted(
                HttpClientRequestContext request,
                @Nullable HttpClientResponseHead response,
                HttpClientAttemptCompletion completion) {
            seen.add(new Seen(
                    request,
                    response,
                    completion,
                    Body.copyOf(completion.requestBody()),
                    Body.copyOf(completion.responseBody()),
                    completion.toString()));
        }
    }

    /** A credential source adding a fixed bearer header. */
    private static final class BearerSource implements CredentialSource {
        @Override
        public Future<Map<String, String>> resolve(java.net.URI target) {
            return Future.succeededFuture(Map.of("Authorization", "Bearer t"));
        }
    }

    /** A pipe target that records that a body chunk arrived. */
    private static final class FirstChunkSink implements WriteStream<Buffer> {
        final AtomicBoolean received = new AtomicBoolean();

        @Override
        public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public Future<Void> write(Buffer data) {
            received.set(true);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> end() {
            return Future.succeededFuture();
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return false;
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            return this;
        }
    }

    /** A read stream delivering one buffer when resumed. */
    private static final class OneBufferStream implements ReadStream<Buffer> {
        private final Buffer buffer;
        private Handler<Buffer> handler;
        private Handler<Void> endHandler;
        private boolean delivered;

        OneBufferStream(Buffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            this.handler = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            if (!delivered && handler != null) {
                delivered = true;
                handler.handle(buffer);
                if (endHandler != null) {
                    endHandler.handle(null);
                }
            }
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return resume();
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            this.endHandler = endHandler;
            return this;
        }
    }

    private HttpClient build(HttpClientBuilder builder) {
        HttpClient client = builder.build();
        support.track(client::close);
        return client;
    }

    private HttpClientBuilder builder(String baseUrl, HttpClientInterceptor... interceptors) {
        HttpClientBuilder builder = HttpClientBuilder.create(support.vertx);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        for (HttpClientInterceptor interceptor : interceptors) {
            builder.register(interceptor);
        }
        return builder;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits for the expected number of completions, then proves no further one arrives. */
    private static List<Seen> settled(Recorder recorder, int expected) {
        awaitCondition(() -> recorder.seen.size() >= expected, HttpClientItSupport.WAIT_MS);
        sleepQuietly(SETTLE_MS);
        return List.copyOf(recorder.seen);
    }

    /** The one completion of a recorder that must have seen exactly one exchange. */
    private static Seen only(Recorder recorder) {
        List<Seen> all = settled(recorder, 1);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private RecordingServer okServer() {
        return support.server((request, body) -> request.response().end("ok"));
    }

    /** Answers with the request body and the request's content type; {@code /fail} answers 503. */
    private RecordingServer echoServer() {
        return support.server((request, body) -> {
            String contentType = request.getHeader("Content-Type");
            if (contentType != null) {
                request.response().putHeader("Content-Type", contentType);
            }
            if (request.path().equals("/fail")) {
                request.response().setStatusCode(503);
            }
            request.response().end(body);
        });
    }

    private static void assertBuffered(Body body, String text, @Nullable String contentType) {
        assertThat(body.kind()).isEqualTo(PayloadKind.BUFFERED);
        assertThat(body.text()).isEqualTo(text);
        assertThat(body.declaredLength()).isEqualTo(OptionalLong.of(text.getBytes(StandardCharsets.UTF_8).length));
        assertContentType(body, contentType);
    }

    private static void assertMetadataOnly(Body body, @Nullable String contentType, long declaredLength) {
        assertThat(body.kind()).isEqualTo(PayloadKind.STREAMING);
        assertThat(body.bytes()).isEmpty();
        assertThat(body.declaredLength())
                .isEqualTo(declaredLength < 0 ? OptionalLong.empty() : OptionalLong.of(declaredLength));
        assertContentType(body, contentType);
    }

    private static void assertContentType(Body body, @Nullable String contentType) {
        if (contentType == null) {
            assertThat(body.contentType()).isEmpty();
        } else {
            assertThat(body.contentType()).hasValue(contentType);
        }
    }

    private static void assertAbsent(Body body) {
        assertThat(body.kind()).isEqualTo(PayloadKind.ABSENT);
        assertThat(body.bytes()).isEmpty();
    }

    // --- the factory graph for the configuration key ---

    @Singleton
    @Component(
            modules = {
                HttpClientModule.class,
                ConfigParsingModule.class,
                ResiliencePoliciesModule.class,
                BodyCaptureIT.GraphModule.class
            })
    interface AppComponent {
        HttpClientFactory factory();

        java.util.Set<ApplicationShutdownStep> shutdownSteps();
    }

    @Module
    static final class GraphModule {
        private final Vertx vertx;
        private final JsonObject config;
        private final HttpClientInterceptor global;

        GraphModule(Vertx vertx, JsonObject config, HttpClientInterceptor global) {
            this.vertx = vertx;
            this.config = config;
            this.global = global;
        }

        @Provides
        Vertx vertx() {
            return vertx;
        }

        @Provides
        @Singleton
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }

        @Provides
        @IntoSet
        HttpClientInterceptor globalInterceptor() {
            return global;
        }
    }

    private AppComponent app(JsonObject clients, HttpClientInterceptor global) {
        AppComponent component = DaggerBodyCaptureIT_AppComponent.builder()
                .graphModule(new GraphModule(support.vertx, new JsonObject().put("httpClient", clients), global))
                .build();
        support.track(() -> {
            List<ApplicationShutdownStep> steps = new ArrayList<>(component.shutdownSteps());
            steps.sort(LifecycleOrdered.comparator());
            Future<Void> chain = Future.succeededFuture();
            for (ApplicationShutdownStep step : steps) {
                chain = chain.compose(v -> step.stop());
            }
            return chain;
        });
        return component;
    }

    // --- proofs ---

    /** AC-095: capture is off by default; the builder and the configuration key turn it on. */
    @Test
    @DisplayName("AC-095: body capture is off by default and a client or configuration turns it on")
    void captureIsOffByDefaultAndTheBuilderOrConfigurationTurnsItOn() {
        // Given a recording interceptor and a server answering 200 with body ok
        RecordingServer server = okServer();

        // When the caller sends web.post("/x").sendBuffer("hello") through a client built without captureBodies
        Recorder off = new Recorder();
        HttpClient plain = build(builder(server.origin(), off));
        await(plain.execute(web -> web.post("/x").sendBuffer(Buffer.buffer("hello"))));

        // Then both bodies are ABSENT
        Seen offSeen = only(off);
        assertAbsent(offSeen.requestBody());
        assertAbsent(offSeen.responseBody());
        assertThat(offSeen.completion().requestBody().kind()).isEqualTo(PayloadKind.ABSENT);
        assertThat(offSeen.completion().responseBody().kind()).isEqualTo(PayloadKind.ABSENT);

        // And the same exchange through a builder with captureBodies(true) reports both bodies as BUFFERED
        Recorder on = new Recorder();
        HttpClient capturing = build(builder(server.origin(), on).captureBodies(true));
        await(capturing.execute(web -> web.post("/x").sendBuffer(Buffer.buffer("hello"))));
        Seen onSeen = only(on);
        assertBuffered(onSeen.requestBody(), "hello", null);
        assertBuffered(onSeen.responseBody(), "ok", null);

        // And the same exchange through a factory client whose httpClient.{name}.captureBodies is true does too
        Recorder global = new Recorder();
        JsonObject configured = new JsonObject()
                .put("baseUrl", server.origin())
                .put("captureBodies", true)
                .put(
                        "egress",
                        new JsonObject()
                                .put("allowHosts", new JsonArray().add("127.0.0.1"))
                                .put("allowPlainHttp", true));
        JsonObject unconfigured = new JsonObject()
                .put("baseUrl", server.origin())
                .put(
                        "egress",
                        new JsonObject()
                                .put("allowHosts", new JsonArray().add("127.0.0.1"))
                                .put("allowPlainHttp", true));
        AppComponent app = app(new JsonObject().put("capturing", configured).put("quiet", unconfigured), global);
        await(app.factory().client("capturing").execute(web -> web.post("/x").sendBuffer(Buffer.buffer("hello"))));
        Seen factorySeen = only(global);
        assertBuffered(factorySeen.requestBody(), "hello", null);
        assertBuffered(factorySeen.responseBody(), "ok", null);

        // And the factory client without the key stays off
        await(app.factory().client("quiet").execute(web -> web.post("/x").sendBuffer(Buffer.buffer("hello"))));
        List<Seen> bothSeen = settled(global, 2);
        assertThat(bothSeen).hasSize(2);
        assertAbsent(bothSeen.get(1).requestBody());
        assertAbsent(bothSeen.get(1).responseBody());
    }

    /** AC-096: a completed exchange carries both bodies, as written and as received. */
    @Test
    @DisplayName("AC-096: a completed exchange carries both bodies")
    void aCompletedExchangeCarriesBothBodies() {
        // Given a capturing client, a recording interceptor and a server that echoes the request body and type
        RecordingServer server = echoServer();
        Recorder recorder = new Recorder();
        HttpClient client = build(builder(server.origin(), recorder).captureBodies(true));

        // When the caller sends a buffer, a JSON object, a form, a string-codec request and a refused 503
        Buffer callerBuffer = Buffer.buffer("hello");
        await(client.execute(web -> web.post("/buffer").sendBuffer(callerBuffer)));
        await(client.execute(web -> web.patch("/patch")
                .putHeader("Content-Type", "application/merge-patch+json")
                .sendJsonObject(new JsonObject().put("b", 2))));
        await(client.execute(web -> web.post("/json").sendJsonObject(new JsonObject().put("a", 1))));
        MultiMap form = MultiMap.caseInsensitiveMultiMap().add("a", "1").add("b", "2");
        await(client.execute(web -> web.post("/form").sendForm(form)));
        HttpResponse<String> asString = await(
                client.execute(web -> web.post("/string").as(BodyCodec.string()).sendBuffer(Buffer.buffer("text"))));
        Throwable refused = awaitFailure(client.execute(
                web -> web.post("/fail").sendBuffer(Buffer.buffer("busy")).expecting(HttpExpectations.success())));
        assertThat(asString.body()).isEqualTo("text");
        assertThat(refused).isInstanceOf(HttpStatusRejectedException.class);

        // Then each completion carries the bytes written to the wire and received, with content types
        List<Seen> all = settled(recorder, 6);
        assertThat(all).hasSize(6);
        // And the caller's own buffer was only read
        assertThat(callerBuffer.toString()).isEqualTo("hello");

        // And a Content-Type the caller set wins over the type the send method implies
        Seen patch = byPath(all, "/patch");
        assertBuffered(patch.requestBody(), "{\"b\":2}", "application/merge-patch+json");
        assertBuffered(patch.responseBody(), "{\"b\":2}", "application/merge-patch+json");
        Seen buffer = byPath(all, "/buffer");
        assertBuffered(buffer.requestBody(), "hello", null);
        assertBuffered(buffer.responseBody(), "hello", null);

        Seen json = byPath(all, "/json");
        assertBuffered(json.requestBody(), "{\"a\":1}", "application/json");
        assertBuffered(json.responseBody(), "{\"a\":1}", "application/json");

        Seen formSeen = byPath(all, "/form");
        assertBuffered(formSeen.requestBody(), "a=1&b=2", "application/x-www-form-urlencoded");
        assertBuffered(formSeen.responseBody(), "a=1&b=2", "application/x-www-form-urlencoded");

        Seen string = byPath(all, "/string");
        assertBuffered(string.requestBody(), "text", null);
        assertBuffered(string.responseBody(), "text", null);

        Seen failed = byPath(all, "/fail");
        assertThat(failed.completion().statusCode()).isEqualTo(503);
        assertBuffered(failed.requestBody(), "busy", null);
        assertBuffered(failed.responseBody(), "busy", null);

        // And the request body is what the server received
        assertThat(server.requestsTo("/json").get(0).body().toString())
                .isEqualTo(json.requestBody().text());
        assertThat(server.requestsTo("/form").get(0).body().toString())
                .isEqualTo(formSeen.requestBody().text());

        // And with maxResponseBytes(0) the string-codec exchange is STREAMING with the declared length
        // while a default Buffer response stays BUFFERED
        Recorder unbounded = new Recorder();
        HttpClient noBound =
                build(builder(server.origin(), unbounded).captureBodies(true).maxResponseBytes(0));
        await(noBound.execute(web -> web.post("/string").as(BodyCodec.string()).sendBuffer(Buffer.buffer("text"))));
        await(noBound.execute(web -> web.post("/buffer").sendBuffer(Buffer.buffer("hello"))));
        List<Seen> unboundedSeen = settled(unbounded, 2);
        assertThat(unboundedSeen).hasSize(2);
        Seen streamedString = byPath(unboundedSeen, "/string");
        assertBuffered(streamedString.requestBody(), "text", null);
        assertMetadataOnly(streamedString.responseBody(), null, 4);
        assertBuffered(byPath(unboundedSeen, "/buffer").responseBody(), "hello", null);
    }

    private static Seen byPath(List<Seen> all, String path) {
        return all.stream()
                .filter(s -> s.request().uri().endsWith(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no completion for " + path + " in " + all.size()));
    }

    /** AC-097: a streamed or incomplete body is reported as metadata only. */
    @Test
    @DisplayName("AC-097: a streamed or incomplete body is reported as metadata only")
    void aStreamedOrIncompleteBodyIsReportedAsMetadataOnly() throws Exception {
        // Given servers able to produce each path
        RecordingServer big = support.server((request, body) ->
                request.response().putHeader("Content-Type", "text/plain").end("x".repeat((int) LIMIT * 2)));
        RecordingServer cut = support.server((request, body) -> {
            request.response().putHeader("Content-Type", "text/plain").putHeader("Content-Length", "1000");
            request.response().write(Buffer.buffer(new byte[100])).onComplete(v -> request.connection()
                    .close());
        });
        RecordingServer stalling = support.server((request, body) -> {
            request.response().putHeader("Content-Type", "text/plain").putHeader("Content-Length", "1000");
            request.response().write(Buffer.buffer(new byte[100]));
        });
        RecordingServer ok = support.server((request, body) ->
                request.response().putHeader("Content-Type", "text/plain").end("abcdef"));

        // When a response exceeds maxResponseBytes(1024)
        Recorder boundRec = new Recorder();
        Throwable bound = awaitFailure(
                build(builder(big.origin(), boundRec).captureBodies(true).maxResponseBytes(LIMIT))
                        .execute(web -> web.get("/").send()));
        assertThat(bound).isInstanceOf(HttpBodyLimitException.class);
        // Then the response body is STREAMING with the head's content type and declared length, no bytes
        Seen boundSeen = only(boundRec);
        assertAbsent(boundSeen.requestBody());
        assertMetadataOnly(boundSeen.responseBody(), "text/plain", LIMIT * 2);

        // When the server resets mid-body
        Recorder resetRec = new Recorder();
        awaitFailure(build(builder(cut.origin(), resetRec).captureBodies(true))
                .execute(web -> web.get("/").send()));
        // Then the response body is STREAMING with the head's content type and declared length
        assertMetadataOnly(only(resetRec).responseBody(), "text/plain", 1000);

        // When the caller cancels mid-body
        Recorder cancelRec = new Recorder();
        CancellationSignal signal = CancellationSignal.create();
        FirstChunkSink firstChunk = new FirstChunkSink();
        Future<HttpResponse<Void>> cancelled = build(
                        builder(stalling.origin(), cancelRec).captureBodies(true))
                .execute(
                        CallOptions.defaults().noReadTimeout().cancellation(signal),
                        web -> web.get("/").as(BodyCodec.pipe(firstChunk)).send());
        // a body chunk reaching the codec proves the head arrived, so the cancel comes mid-body
        assertThat(awaitCondition(firstChunk.received::get, HttpClientItSupport.WAIT_MS))
                .isTrue();
        signal.cancel();
        assertThat(awaitFailure(cancelled)).isInstanceOf(HttpExchangeCancelledException.class);
        // Then the response body is STREAMING with the head's content type and declared length
        assertMetadataOnly(only(cancelRec).responseBody(), "text/plain", 1000);

        // When the egress policy denies the target
        Recorder deniedRec = new Recorder();
        Throwable denied = awaitFailure(build(builder(null, deniedRec)
                        .captureBodies(true)
                        .egress(EgressPolicy.allowHosts(List.of("allowed.test"), true)))
                .execute(web -> web.postAbs(ok.origin() + "/").sendBuffer(Buffer.buffer("denied"))));
        assertThat(denied).isInstanceOf(HttpEgressDeniedException.class);
        // Then it reports its buffered request body and an ABSENT response
        Seen deniedSeen = only(deniedRec);
        assertBuffered(deniedSeen.requestBody(), "denied", null);
        assertAbsent(deniedSeen.responseBody());
        assertThat(ok.requests).isEmpty();

        // When the request body exceeds maxRequestBytes(1024)
        Recorder oversizedRec = new Recorder();
        Throwable oversized = awaitFailure(
                build(builder(ok.origin(), oversizedRec).captureBodies(true).maxRequestBytes(LIMIT))
                        .execute(web -> web.post("/").sendBuffer(Buffer.buffer(new byte[(int) LIMIT + 1]))));
        assertThat(oversized).isInstanceOf(HttpBodyLimitException.class);
        // Then the request body is STREAMING with its declared length and the response is ABSENT
        Seen oversizedSeen = only(oversizedRec);
        assertMetadataOnly(oversizedSeen.requestBody(), null, LIMIT + 1);
        assertAbsent(oversizedSeen.responseBody());

        // When the caller sends sendStream with a Content-Length
        Recorder streamedRec = new Recorder();
        await(build(builder(ok.origin(), streamedRec).captureBodies(true)).execute(web -> web.post("/up")
                .putHeader("Content-Length", "5")
                .sendStream(new OneBufferStream(Buffer.buffer("12345")))));
        // Then the streamed request body is STREAMING with the declared length and the response is BUFFERED
        Seen streamedSeen = only(streamedRec);
        assertMetadataOnly(streamedSeen.requestBody(), null, 5);
        assertBuffered(streamedSeen.responseBody(), "abcdef", "text/plain");

        // When a stream(...) call reads its body to the end
        Recorder streamRec = new Recorder();
        HttpClient streaming = build(builder(ok.origin(), streamRec).captureBodies(true));
        Buffer collected = Buffer.buffer();
        Future<HttpStream> opened = streaming.stream(CallOptions.defaults(), web -> web.get("/s"), null);
        opened.onSuccess(stream -> stream.body().handler(collected::appendBuffer));
        await(await(opened).completion());
        // Then the stream's body is STREAMING with the head's content type and declared length
        assertThat(collected.toString()).isEqualTo("abcdef");
        Seen streamSeen = only(streamRec);
        assertAbsent(streamSeen.requestBody());
        assertMetadataOnly(streamSeen.responseBody(), "text/plain", 6);
    }

    /** AC-097: a refusal before sending describes the body the caller wrote. */
    @Test
    @DisplayName("AC-097: a relative request without a base URL reports the buffered body")
    void anEarlyRefusalReportsTheBufferedBody() {
        // Given a capturing client without a base URL
        Recorder recorder = new Recorder();

        // When the caller sends a relative request with a body
        Throwable relative = awaitFailure(build(builder(null, recorder).captureBodies(true))
                .execute(web -> web.post("/x").sendBuffer(Buffer.buffer("hello"))));

        // Then it is refused without sending and reports the request body BUFFERED and the response ABSENT
        assertThat(relative).isInstanceOf(IllegalStateException.class);
        Seen seen = only(recorder);
        assertBuffered(seen.requestBody(), "hello", null);
        assertAbsent(seen.responseBody());
    }

    /** AC-098: a followed redirect reports the final hop's response body. */
    @Test
    @DisplayName("AC-098: a followed redirect reports the final hop's response body")
    void aFollowedRedirectReportsTheFinalHopsResponseBody() {
        // Given a capturing client that follows redirects and a server redirecting POST /a to /b, which
        // answers 200 with body done. A 303 is used because a 307 on a POST is returned, not followed.
        RecordingServer server = support.server((request, body) -> {
            if (request.path().equals("/a")) {
                request.response()
                        .setStatusCode(303)
                        .putHeader("Location", "/b")
                        .end("moved");
            } else {
                request.response().setStatusCode(200).end("done");
            }
        });
        Recorder recorder = new Recorder();
        HttpClient client = build(builder(server.origin(), recorder)
                .captureBodies(true)
                .webClientOptions(
                        new WebClientOptions().setFollowRedirects(true).setMaxRedirects(5)));

        // When the caller sends sendBuffer("payload") to /a
        HttpResponse<Buffer> response =
                await(client.execute(web -> web.post("/a").sendBuffer(Buffer.buffer("payload"))));
        assertThat(response.statusCode()).isEqualTo(200);

        // Then the completion reports the request body and the final hop's response body, not the redirect's
        Seen seen = only(recorder);
        assertThat(server.requestsTo("/b")).hasSize(1);
        assertBuffered(seen.requestBody(), "payload", null);
        assertBuffered(seen.responseBody(), "done", null);

        // And the same through a decoding codec, whose copy sink crosses the hops, reports the final hop too
        HttpResponse<String> decoded = await(
                client.execute(web -> web.post("/a").as(BodyCodec.string()).sendBuffer(Buffer.buffer("payload"))));
        assertThat(decoded.body()).isEqualTo("done");
        List<Seen> both = settled(recorder, 2);
        assertThat(both).hasSize(2);
        assertBuffered(both.get(1).requestBody(), "payload", null);
        assertBuffered(both.get(1).responseBody(), "done", null);
    }

    /** AC-097: a chunked response over the bound, read by a decoding codec, is metadata only. */
    @Test
    @DisplayName("AC-097: a chunked response over the bound read by a decoding codec is metadata only")
    void aChunkedResponseOverTheBoundReadByADecodingCodecIsMetadataOnly() {
        // Given a capturing client with maxResponseBytes(1024) and a server streaming 1800 bytes with no length
        RecordingServer server = support.server((request, body) -> {
            request.response().setChunked(true).putHeader("Content-Type", "text/plain");
            request.response().write("x".repeat(600));
            request.response().write("x".repeat(600));
            request.response().write("x".repeat(600));
            request.response().end();
        });
        Recorder recorder = new Recorder();
        HttpClient client =
                build(builder(server.origin(), recorder).captureBodies(true).maxResponseBytes(LIMIT));

        // When the caller reads it with as(BodyCodec.string())
        Throwable bound = awaitFailure(
                client.execute(web -> web.get("/").as(BodyCodec.string()).send()));

        // Then the call fails on the bound and the response is STREAMING with the head's type and no length
        assertThat(bound).isInstanceOf(HttpBodyLimitException.class);
        Seen seen = only(recorder);
        assertAbsent(seen.requestBody());
        assertMetadataOnly(seen.responseBody(), "text/plain", -1);
    }

    /** AC-097: a body the caller pipes or discards is metadata only, with nothing copied. */
    @Test
    @DisplayName("AC-097: a piped or discarded response body is metadata only")
    void aPipedOrDiscardedResponseBodyIsMetadataOnly() {
        // Given a capturing client and a server answering 200 with a two-byte text body
        RecordingServer server = support.server((request, body) ->
                request.response().putHeader("Content-Type", "text/plain").end("ok"));
        Recorder recorder = new Recorder();
        HttpClient client = build(builder(server.origin(), recorder).captureBodies(true));

        // When the caller pipes the body to a sink and, in another exchange, discards it
        FirstChunkSink sink = new FirstChunkSink();
        await(client.execute(web -> web.get("/pipe").as(BodyCodec.pipe(sink)).send()));
        await(client.execute(web -> web.get("/none").as(BodyCodec.none()).send()));

        // Then both responses are STREAMING with the head's type and declared length, and no bytes
        assertThat(sink.received).isTrue();
        List<Seen> all = settled(recorder, 2);
        assertThat(all).hasSize(2);
        assertMetadataOnly(byPath(all, "/pipe").responseBody(), "text/plain", 2);
        assertMetadataOnly(byPath(all, "/none").responseBody(), "text/plain", 2);
    }

    /** AC-096: an empty body is absent, on the request and on the response, a 204 and a HEAD answer included. */
    @Test
    @DisplayName("AC-096: an empty body is absent")
    void anEmptyBodyIsAbsent() {
        // Given a capturing client and a server answering 204, 200 with an empty body, and HEAD with a length
        RecordingServer server = support.server((request, body) -> {
            if (request.path().equals("/empty")) {
                request.response().end();
            } else if (request.path().equals("/head")) {
                request.response()
                        .putHeader("Content-Type", "text/plain")
                        .putHeader("Content-Length", "5")
                        .end();
            } else {
                request.response().setStatusCode(204).end();
            }
        });
        Recorder recorder = new Recorder();
        HttpClient client = build(builder(server.origin(), recorder).captureBodies(true));

        // When the caller posts an empty buffer to each
        await(client.execute(web -> web.post("/none").sendBuffer(Buffer.buffer())));
        await(client.execute(web -> web.post("/empty").sendBuffer(Buffer.buffer())));
        await(client.execute(web -> web.get("/get").send()));
        // And sends HEAD with a decoding codec under the default response bound
        await(client.execute(web -> web.head("/head").as(BodyCodec.string()).send()));

        // Then every request and response body is ABSENT
        List<Seen> all = settled(recorder, 4);
        assertThat(all).hasSize(4);
        for (Seen seen : all) {
            assertAbsent(seen.requestBody());
            assertAbsent(seen.responseBody());
        }
        assertThat(byPath(all, "/none").completion().statusCode()).isEqualTo(204);
    }

    /** AC-095: capture with no interceptor registered changes nothing for the caller. */
    @Test
    @DisplayName("AC-095: capture without an interceptor leaves the exchange unchanged")
    void captureWithoutAnInterceptorLeavesTheExchangeUnchanged() {
        // Given a capturing client with no interceptor and a server answering ok
        RecordingServer server = okServer();
        HttpClient client = build(builder(server.origin()).captureBodies(true));

        // When the caller sends a JSON body, read by a decoding codec
        HttpResponse<String> response = await(client.execute(
                web -> web.post("/x").as(BodyCodec.string()).sendJsonObject(new JsonObject().put("a", 1))));

        // Then the exchange succeeds as it does without capture
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ok");
        assertThat(server.requestsTo("/x").get(0).body().toString()).isEqualTo("{\"a\":1}");
    }

    /** A value whose getter throws, so that encoding it as JSON fails. */
    public static final class Exploding {
        public String getValue() {
            throw new IllegalStateException("cannot encode");
        }
    }

    /** AC-096: a JSON body the capture cannot encode does not change the outcome of the exchange. */
    @Test
    @DisplayName("AC-096: a JSON body that cannot be encoded fails as it does without capture")
    void aJsonBodyThatCannotBeEncodedFailsAsItDoesWithoutCapture() {
        // Given a capturing and a plain client, each with a recorder, and a server answering ok
        RecordingServer server = okServer();
        Recorder capturingRec = new Recorder();
        Recorder plainRec = new Recorder();
        HttpClient capturing = build(builder(server.origin(), capturingRec).captureBodies(true));
        HttpClient plain = build(builder(server.origin(), plainRec));

        // When each sends a POJO that cannot be encoded
        Throwable withCapture =
                awaitFailure(capturing.execute(web -> web.post("/x").sendJson(new Exploding())));
        Throwable withoutCapture =
                awaitFailure(plain.execute(web -> web.post("/x").sendJson(new Exploding())));

        // Then both fail the same way, nothing is sent, and each is notified once
        assertThat(withCapture).isInstanceOf(withoutCapture.getClass());
        assertThat(server.requests).isEmpty();
        Seen capturingSeen = only(capturingRec);
        Seen plainSeen = only(plainRec);
        assertThat(capturingSeen.completion().error()).isSameAs(withCapture);
        assertThat(plainSeen.completion().error()).isSameAs(withoutCapture);
        // And the capturing client reports the body as streaming metadata
        assertMetadataOnly(capturingSeen.requestBody(), "application/json", -1);
        assertAbsent(plainSeen.requestBody());

        // And a client with a request bound fails with the encoding failure before sending
        Recorder boundedRec = new Recorder();
        Throwable bounded = awaitFailure(
                build(builder(server.origin(), boundedRec).captureBodies(true).maxRequestBytes(LIMIT))
                        .execute(web -> web.post("/x").sendJson(new Exploding())));
        assertThat(bounded).isNotInstanceOf(HttpBodyLimitException.class);
        assertThat(server.requests).isEmpty();
        assertThat(only(boundedRec).completion().error()).isSameAs(bounded);
    }

    /** AC-099: bodies and credentials are unredacted while printing stays excluded. */
    @Test
    @DisplayName("AC-099: bodies and credentials are unredacted while printing stays excluded")
    void bodiesAndCredentialsAreUnredactedWhilePrintingStaysExcluded() {
        // Given a capturing client with a bearer credential source and a server answering with a token body
        RecordingServer server = support.server((request, body) ->
                request.response().putHeader("Content-Type", "application/json").end("{\"access_token\":\"s3cr3t\"}"));
        Recorder recorder = new Recorder();
        HttpClient client = build(builder(server.origin(), recorder)
                .captureBodies(true)
                .credentials(new BearerSource())
                .allowCredentialsOverPlainHttp(true));

        // When the caller sends sendJsonObject({"password":"p"})
        await(client.execute(web -> web.post("/login").sendJsonObject(new JsonObject().put("password", "p"))));

        // Then both bodies are held unchanged
        Seen seen = only(recorder);
        assertBuffered(seen.requestBody(), "{\"password\":\"p\"}", "application/json");
        assertBuffered(seen.responseBody(), "{\"access_token\":\"s3cr3t\"}", "application/json");

        // And the credential reached the server and is in the request snapshot unredacted
        assertThat(server.requests.get(0).headers().get("Authorization")).isEqualTo("Bearer t");
        assertThat(seen.request().headers().get("Authorization")).isEqualTo("Bearer t");

        // And toString of the completion contains neither body, and toString of the snapshot no header value
        assertThat(seen.request().toString()).doesNotContain("Bearer");
        assertThat(seen.printed()).doesNotContain("s3cr3t").doesNotContain("password");
        assertThat(seen.completion().toString()).doesNotContain("s3cr3t").doesNotContain("password");
    }
}
