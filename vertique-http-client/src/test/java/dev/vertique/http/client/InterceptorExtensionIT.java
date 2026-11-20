// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitCondition;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.exception.HttpBodyLimitException.Direction;
import dev.vertique.http.client.exception.HttpCredentialException;
import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpEgressDeniedException.Reason;
import dev.vertique.http.client.exception.HttpExchangeCancelledException;
import dev.vertique.http.client.exception.HttpStatusRejectedException;
import dev.vertique.http.client.interceptor.HttpClientAttemptCompletion;
import dev.vertique.http.client.interceptor.HttpClientInterceptor;
import dev.vertique.http.client.interceptor.HttpClientRequestContext;
import dev.vertique.http.client.interceptor.HttpClientResponseHead;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClosedException;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClientOptions;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * Integration tests of the interceptor extension point: {@code beforeRequest} folds in order before
 * credentials, {@code onAttemptCompleted} runs exactly once per exchange on every terminal path that
 * reached the client (refusals before sending included), a throwing interceptor is isolated, no credential is
 * visible to {@code beforeRequest}, a failure or a {@code toString}, and the completion snapshot carries the
 * credential headers unredacted.
 *
 * <p>Every test dials only loopback addresses.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class InterceptorExtensionIT {

    private static final String SECRET = "s3cret-token";
    private static final String BEARER = "Bearer " + SECRET;
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

    /** One onAttemptCompleted call as the interceptor saw it. */
    record Completed(
            HttpClientRequestContext request,
            HttpClientResponseHead response,
            HttpClientAttemptCompletion completion) {}

    /** An interceptor that records every callback and prepares requests through a function. */
    static class Recorder implements HttpClientInterceptor {

        final String key;
        final List<String> events;
        final List<HttpClientRequestContext> contexts = new CopyOnWriteArrayList<>();
        final List<Completed> completed = new CopyOnWriteArrayList<>();
        private final Function<HttpClientRequestContext, Future<HttpClientRequestContext>> prepare;

        Recorder(String key) {
            this(key, new CopyOnWriteArrayList<>(), Future::succeededFuture);
        }

        Recorder(
                String key,
                List<String> events,
                Function<HttpClientRequestContext, Future<HttpClientRequestContext>> prepare) {
            this.key = key;
            this.events = events;
            this.prepare = prepare;
        }

        @Override
        public String orderKey() {
            return key;
        }

        @Override
        public Future<HttpClientRequestContext> beforeRequest(HttpClientRequestContext ctx) {
            contexts.add(ctx);
            events.add(key + ".before");
            return prepare.apply(ctx);
        }

        @Override
        public void onAttemptCompleted(
                HttpClientRequestContext request,
                HttpClientResponseHead response,
                HttpClientAttemptCompletion completion) {
            events.add(key + ".completed");
            completed.add(new Completed(request, response, completion));
        }
    }

    /** An interceptor whose onAttemptCompleted throws the throwable it is given. */
    abstract static class Throwing implements HttpClientInterceptor {

        final AtomicInteger calls = new AtomicInteger();

        abstract Throwable failure();

        @Override
        public void onAttemptCompleted(
                HttpClientRequestContext request,
                HttpClientResponseHead response,
                HttpClientAttemptCompletion completion) {
            calls.incrementAndGet();
            sneakyThrow(failure());
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
            throw (T) t;
        }
    }

    static final class ThrowsIllegalState extends Throwing {
        @Override
        public String orderKey() {
            return "a";
        }

        @Override
        Throwable failure() {
            return new IllegalStateException("boom-a");
        }
    }

    static final class ThrowsAssertion extends Throwing {
        @Override
        public String orderKey() {
            return "b";
        }

        @Override
        Throwable failure() {
            return new AssertionError("boom-b");
        }
    }

    static final class ThrowsStackOverflow extends Throwing {
        @Override
        public String orderKey() {
            return "c";
        }

        @Override
        Throwable failure() {
            return new StackOverflowError();
        }
    }

    static final class ThrowsLinkage extends Throwing {
        @Override
        public String orderKey() {
            return "d";
        }

        @Override
        Throwable failure() {
            return new LinkageError("boom-d");
        }
    }

    /** A credential source returning fixed headers, or failing, and counting resolutions. */
    private static final class CountingSource implements CredentialSource {

        final AtomicInteger resolutions = new AtomicInteger();
        private final Future<Map<String, String>> result;

        CountingSource(Future<Map<String, String>> result) {
            this.result = result;
        }

        static CountingSource bearer() {
            return new CountingSource(Future.succeededFuture(Map.of("Authorization", BEARER)));
        }

        @Override
        public Future<Map<String, String>> resolve(URI target) {
            resolutions.incrementAndGet();
            return result;
        }
    }

    /** Captures every record of every logger, at every level, until closed. */
    private static final class LogCapture implements AutoCloseable {

        private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final Level previousLevel = root.getLevel();

        LogCapture() {
            appender.start();
            root.setLevel(Level.TRACE);
            root.addAppender(appender);
        }

        List<String> records() {
            List<String> texts = new ArrayList<>();
            for (ILoggingEvent event : new ArrayList<>(appender.list)) {
                StringBuilder text = new StringBuilder()
                        .append(event.getLoggerName())
                        .append(' ')
                        .append(event.getFormattedMessage());
                if (event.getArgumentArray() != null) {
                    text.append(' ').append(Arrays.toString(event.getArgumentArray()));
                }
                if (event.getThrowableProxy() != null) {
                    text.append(' ').append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
                }
                texts.add(text.toString());
            }
            return texts;
        }

        @Override
        public void close() {
            root.detachAppender(appender);
            root.setLevel(previousLevel);
            appender.stop();
        }
    }

    private HttpClient build(HttpClientBuilder builder) {
        HttpClient client = builder.build();
        support.track(client::close);
        return client;
    }

    /** A builder with the given interceptors registered and an optional base URL. */
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
    private static List<Completed> settled(Recorder recorder, int expected) {
        if (expected > 0) {
            awaitCondition(() -> recorder.completed.size() >= expected, HttpClientItSupport.WAIT_MS);
        }
        sleepQuietly(SETTLE_MS);
        return List.copyOf(recorder.completed);
    }

    private static int unusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private RecordingServer okServer() {
        return support.server((request, body) -> request.response().end("ok"));
    }

    private RecordingServer silentServer() {
        return support.server((request, body) -> {});
    }

    // --- proofs ---

    /** AC-023. */
    @Test
    @DisplayName("AC-023: beforeRequest, failures and toString never show credentials; the completion snapshot does")
    void beforeRequestFailuresAndToStringNeverShowCredentials() {
        // Given a credential source, a recording interceptor and a server that answers 500 to GET /x
        RecordingServer server = support.server(
                (request, body) -> request.response().setStatusCode(500).end("failed"));
        Recorder recorder = new Recorder("rec");
        CountingSource source = CountingSource.bearer();
        HttpClientBuilder builder = builder(server.origin(), recorder).credentials(source);
        HttpClient client = build(builder);

        // When the caller runs a call that expects success
        Throwable failure =
                awaitFailure(client.execute(web -> web.get("/x").send().expecting(HttpExpectations.success())));

        // Then the server received the credential
        assertThat(server.requests).hasSize(1);
        assertThat(server.requests.get(0).headers().get("Authorization")).isEqualTo(BEARER);

        // And the beforeRequest context holds no Authorization header and no secret
        List<Completed> completed = settled(recorder, 1);
        assertThat(completed).hasSize(1);
        assertThat(recorder.contexts).hasSize(1);
        HttpClientRequestContext prepared = recorder.contexts.get(0);
        assertThat(prepared.headers().contains("Authorization")).isFalse();
        assertThat(headerNamesAndValues(prepared.headers())).doesNotContain(SECRET);

        // And the completion snapshot holds the credential unredacted, as the server received it
        HttpClientRequestContext snapshot = completed.get(0).request();
        assertThat(snapshot.headers().get("Authorization")).isEqualTo(BEARER);

        // And toString of neither context shows the secret or the header name
        for (HttpClientRequestContext context : List.of(prepared, snapshot)) {
            assertThat(context.toString()).doesNotContain(SECRET).doesNotContain("Authorization");
        }
        assertThat(completed.get(0).completion().toString()).doesNotContain(SECRET);

        // And the status failure, the built client and the builder show neither the secret nor the header name
        assertThat(failure).isInstanceOf(HttpStatusRejectedException.class);
        assertThat(failure.getMessage()).doesNotContain(SECRET).doesNotContain("Authorization");
        assertThat(failure.toString()).doesNotContain(SECRET).doesNotContain("Authorization");
        assertThat(client.toString()).doesNotContain(SECRET).doesNotContain("Authorization");
        assertThat(builder.toString()).doesNotContain(SECRET).doesNotContain("Authorization");

        // And a credential source failure that mentions the secret reaches the caller and the interceptor
        // as a credential failure that carries neither the secret nor the header name
        Recorder second = new Recorder("rec2");
        CountingSource failing = new CountingSource(Future.failedFuture(new IllegalStateException("vault " + SECRET)));
        HttpClient failingClient = build(builder(server.origin(), second).credentials(failing));
        Throwable credentialFailure =
                awaitFailure(failingClient.execute(web -> web.get("/x").send()));
        assertThat(credentialFailure).isInstanceOf(HttpCredentialException.class);
        assertThat(credentialFailure.getMessage()).doesNotContain(SECRET).doesNotContain("Authorization");
        assertThat(credentialFailure.toString()).doesNotContain(SECRET).doesNotContain("Authorization");
        List<Completed> credentialCompleted = settled(second, 1);
        assertThat(credentialCompleted).hasSize(1);
        assertThat(credentialCompleted.get(0).completion().error()).isSameAs(credentialFailure);
        assertThat(credentialCompleted.get(0).completion().toString())
                .doesNotContain(SECRET)
                .doesNotContain("Authorization");
        assertThat(credentialCompleted.get(0).request().toString()).doesNotContain(SECRET);
        assertThat(credentialCompleted.get(0).request().headers().contains("Authorization"))
                .isFalse();
    }

    /** FR-016, supporting. */
    @Test
    @DisplayName("the response head keeps every Set-Cookie value while its toString prints none")
    void theResponseHeadKeepsEverySetCookieValueWhileItsToStringPrintsNone() {
        // Given a server answering with two Set-Cookie headers and a recording interceptor
        RecordingServer server = support.server((request, body) -> request.response()
                .putHeader("Set-Cookie", List.<String>of("sid=" + SECRET + "; HttpOnly", "pref=dark"))
                .end("ok"));
        Recorder recorder = new Recorder("rec");
        HttpClient client = build(builder(server.origin(), recorder));

        // When the caller runs one call
        await(client.execute(web -> web.get("/x").send()));

        // Then the head handed to completion holds both values unredacted, in order
        HttpClientResponseHead head = settled(recorder, 1).get(0).response();
        assertThat(head.headers().getAll("Set-Cookie")).containsExactly("sid=" + SECRET + "; HttpOnly", "pref=dark");

        // And its toString prints no header value
        assertThat(head.toString()).doesNotContain(SECRET).doesNotContain("pref");
    }

    /** FR-016, supporting. */
    @Test
    @DisplayName("the snapshot carries the credential an asynchronous source resolved for the last original-origin hop")
    void theSnapshotCarriesTheCredentialAnAsynchronousSourceResolvedForTheLastOriginalOriginHop() {
        // Given a source that answers from a timer with a new value per resolution, a server whose /start
        // answers 302 to its own /landing and whose /away answers 302 to another server, and a recording interceptor
        AtomicInteger resolutions = new AtomicInteger();
        CredentialSource delayed = target -> {
            String value = "Bearer v" + resolutions.incrementAndGet();
            Promise<Map<String, String>> later = Promise.promise();
            support.vertx.setTimer(5, id -> later.complete(Map.of("Authorization", value)));
            return later.future();
        };
        RecordingServer other = okServer();
        RecordingServer server = support.server((request, body) -> {
            switch (request.path()) {
                case "/start" ->
                    request.response()
                            .setStatusCode(302)
                            .putHeader("Location", "/landing")
                            .end();
                case "/away" ->
                    request.response()
                            .setStatusCode(302)
                            .putHeader("Location", "http://localhost:" + other.port() + "/elsewhere")
                            .end();
                default -> request.response().end("ok");
            }
        });
        Recorder recorder = new Recorder("rec");
        HttpClient client = build(builder(server.origin(), recorder)
                .webClientOptions(
                        new WebClientOptions().setFollowRedirects(true).setMaxRedirects(5))
                .credentials(delayed));

        // When the caller follows the same-origin redirect
        await(client.execute(web -> web.get("/start").send()));

        // Then each hop got its own credential and the snapshot holds the one sent on the last hop
        assertThat(server.requests).hasSize(2);
        assertThat(server.requests.get(0).headers().get("Authorization")).isEqualTo("Bearer v1");
        assertThat(server.requests.get(1).headers().get("Authorization")).isEqualTo("Bearer v2");
        assertThat(settled(recorder, 1).get(0).request().headers().get("Authorization"))
                .isEqualTo("Bearer v2");

        // And when the caller follows a redirect to another origin, that origin receives no credential, while the
        // snapshot keeps the one set on the original-origin hop
        Recorder away = new Recorder("away");
        HttpClient leaving = build(builder(server.origin(), away)
                .webClientOptions(
                        new WebClientOptions().setFollowRedirects(true).setMaxRedirects(5))
                .credentials(delayed));
        await(leaving.execute(web -> web.get("/away").send()));
        assertThat(other.requests).hasSize(1);
        assertThat(other.requests.get(0).headers().contains("Authorization")).isFalse();
        assertThat(settled(away, 1).get(0).request().headers().get("Authorization"))
                .isEqualTo("Bearer v3");
    }

    private static String headerNamesAndValues(MultiMap headers) {
        StringBuilder text = new StringBuilder();
        headers.forEach(entry ->
                text.append(entry.getKey()).append('=').append(entry.getValue()).append(';'));
        return text.toString();
    }

    /** AC-039. */
    @Test
    @DisplayName("AC-039: interceptors add headers in order and beforeRequest sees no credentials")
    void interceptorsAddHeadersInOrderAndBeforeRequestSeesNoCredentials() {
        // Given interceptor A (key a) adding X-Trace: 1, B (key b) recording what it receives and adding X-Trace: 2,
        // a credential source and a recording server
        RecordingServer server = okServer();
        List<String> events = new CopyOnWriteArrayList<>();
        Recorder a = new Recorder("a", events, ctx -> Future.succeededFuture(ctx.withHeaders(addTrace(ctx, "1"))));
        Recorder b = new Recorder("b", events, ctx -> Future.succeededFuture(ctx.withHeaders(addTrace(ctx, "2"))));
        // B is registered first so that the order comes from the order key, not from registration
        HttpClient client = build(builder(server.origin(), b, a).credentials(CountingSource.bearer()));

        // When the caller runs client.execute(web -> web.get("/x").send()) once
        HttpResponse<Buffer> response =
                await(client.execute(web -> web.get("/x").send()));

        // Then each beforeRequest ran exactly once, A before B, and B received a context carrying X-Trace: 1
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(a.contexts).hasSize(1);
        assertThat(b.contexts).hasSize(1);
        assertThat(events.stream().filter(e -> e.endsWith(".before")).toList()).containsExactly("a.before", "b.before");
        assertThat(a.contexts.get(0).headers().getAll("X-Trace")).isEmpty();
        assertThat(b.contexts.get(0).headers().getAll("X-Trace")).containsExactly("1");

        // And the server received X-Trace 1 then 2, in that order, and the credential header
        assertThat(server.requests).hasSize(1);
        assertThat(server.requests.get(0).headers().getAll("X-Trace")).containsExactly("1", "2");
        assertThat(server.requests.get(0).headers().get("Authorization")).isEqualTo(BEARER);

        // And neither context contains Authorization or the secret
        for (HttpClientRequestContext context : List.of(a.contexts.get(0), b.contexts.get(0))) {
            assertThat(context.headers().contains("Authorization")).isFalse();
            assertThat(headerNamesAndValues(context.headers())).doesNotContain(SECRET);
            assertThat(context.toString()).doesNotContain(SECRET);
        }

        // And the snapshot handed to completion carries both trace values and the credential
        settled(b, 1);
        HttpClientRequestContext snapshot = b.completed.get(0).request();
        assertThat(snapshot.headers().getAll("X-Trace")).containsExactly("1", "2");
        assertThat(snapshot.headers().get("Authorization")).isEqualTo(BEARER);
        assertThat(snapshot.toString()).doesNotContain(SECRET);

        // And a URI changed by an interceptor to a host the egress policy denies fails the exchange with a HOST
        // denial, reaches the denied host (here the same server under another name) not at all, and notifies once
        RecordingServer untouched = okServer();
        Recorder rewriter = new Recorder("rewrite", new CopyOnWriteArrayList<>(), ctx -> {
            String denied = "http://localhost:" + untouched.port() + "/x";
            return Future.succeededFuture(new HttpClientRequestContext(
                    ctx.method(), denied, ctx.headers(), ctx.clientName(), ctx.callId(), ctx.attributes()));
        });
        HttpClient guarded = build(
                builder(untouched.origin(), rewriter).egress(EgressPolicy.allowHosts(List.of("127.0.0.1"), true)));
        Throwable denial = awaitFailure(guarded.execute(web -> web.get("/x").send()));
        assertThat(denial).isInstanceOfSatisfying(HttpEgressDeniedException.class, e -> assertThat(e.reason())
                .isEqualTo(Reason.HOST));
        assertThat(untouched.requests).isEmpty();
        assertThat(untouched.openedConnections.get()).isZero();
        List<Completed> denied = settled(rewriter, 1);
        assertThat(denied).hasSize(1);
        assertThat(denied.get(0).completion().error()).isSameAs(denial);
        assertThat(denied.get(0).response()).isNull();

        // And a successful rewrite keeps the query: an interceptor changing only the path to /y leaves the
        // server with exactly /y?a=1 (the parameter once), and the snapshot reports what was sent
        RecordingServer queryServer = okServer();
        Recorder pathChanger = new Recorder("path", new CopyOnWriteArrayList<>(), ctx -> {
            String changed = ctx.uri().replace("/x", "/y");
            return Future.succeededFuture(new HttpClientRequestContext(
                    ctx.method(), changed, ctx.headers(), ctx.clientName(), ctx.callId(), ctx.attributes()));
        });
        HttpClient rewriting = build(
                builder(queryServer.origin(), pathChanger).egress(EgressPolicy.allowHosts(List.of("127.0.0.1"), true)));
        await(rewriting.execute(web -> web.get("/x").addQueryParam("a", "1").send()));
        assertThat(queryServer.requests).hasSize(1);
        assertThat(queryServer.requests.get(0).uri()).isEqualTo("/y?a=1");
        List<Completed> rewritten = settled(pathChanger, 1);
        assertThat(rewritten).hasSize(1);
        assertThat(rewritten.get(0).request().uri())
                .isEqualTo(queryServer.origin() + queryServer.requests.get(0).uri());

        // And mutating the snapshot's or the response head's headers inside one interceptor's
        // onAttemptCompleted does not change what the next interceptor sees
        RecordingServer mutationServer = support.server(
                (request, body) -> request.response().putHeader("X-Reply", "r").end("ok"));
        Recorder first = new Recorder("a");
        Recorder after = new Recorder("c");
        HttpClientInterceptor mutator = new HttpClientInterceptor() {
            @Override
            public String orderKey() {
                return "b";
            }

            @Override
            public void onAttemptCompleted(
                    HttpClientRequestContext request,
                    HttpClientResponseHead response,
                    HttpClientAttemptCompletion completion) {
                mutateQuietly(request.headers());
                if (response != null) {
                    mutateQuietly(response.headers());
                }
            }
        };
        HttpClient mutating = build(builder(mutationServer.origin(), after, mutator, first));
        await(mutating.execute(web -> web.get("/x").send()));
        List<Completed> beforeMutation = settled(first, 1);
        List<Completed> afterMutation = settled(after, 1);
        assertThat(afterMutation).hasSize(1);
        assertThat(afterMutation.get(0).request().headers().contains("X-Mut")).isFalse();
        assertThat(afterMutation.get(0).response().headers().contains("X-Mut")).isFalse();
        assertThat(afterMutation.get(0).response().headers().get("X-Reply")).isEqualTo("r");
        assertThat(beforeMutation.get(0).request().headers().contains("X-Mut")).isFalse();
        assertThat(beforeMutation.get(0).response().headers().contains("X-Mut")).isFalse();

        // And mutating ctx.headers() in place inside a failing beforeRequest does not change the snapshot
        RecordingServer leakServer = okServer();
        Recorder leaker = new Recorder("a", new CopyOnWriteArrayList<>(), ctx -> {
            mutateQuietly(ctx.headers());
            return Future.failedFuture(new IllegalStateException("leak"));
        });
        Recorder leakCounter = new Recorder("z");
        awaitFailure(build(builder(leakServer.origin(), leaker, leakCounter))
                .execute(web -> web.get("/x").putHeader("X-Orig", "o").send()));
        List<Completed> leakDone = settled(leakCounter, 1);
        assertThat(leakDone).hasSize(1);
        assertThat(leakDone.get(0).request().headers().get("X-Orig")).isEqualTo("o");
        assertThat(leakDone.get(0).request().headers().contains("X-Mut")).isFalse();
        assertThat(leakServer.requests).isEmpty();
    }

    /** Tries to add a header in place; a read-only map that refuses is the expected, accepted outcome. */
    private static void mutateQuietly(MultiMap headers) {
        try {
            headers.add("X-Mut", "1");
        } catch (RuntimeException ignored) {
            // an immutable view refusing the change leaves everything as it was
        }
    }

    private static MultiMap addTrace(HttpClientRequestContext ctx, String value) {
        return MultiMap.caseInsensitiveMultiMap().addAll(ctx.headers()).add("X-Trace", value);
    }

    /** AC-040. */
    @Test
    @DisplayName("AC-040: a failing beforeRequest sends nothing and notifies once with its failure")
    void failingBeforeRequestSendsNothingAndNotifiesOnceWithItsFailure() {
        // Given a counting server and three failing interceptors: a failed future, a null result and a throw
        RecordingServer server = okServer();
        IllegalStateException prepFailure = new IllegalStateException("prep");
        IllegalArgumentException thrown = new IllegalArgumentException("thrown-in-prepare");
        List<Function<HttpClientRequestContext, Future<HttpClientRequestContext>>> failures =
                List.of(ctx -> Future.failedFuture(prepFailure), ctx -> null, ctx -> {
                    throw thrown;
                });
        List<Throwable> expected = new ArrayList<>(Arrays.asList(prepFailure, null, thrown));

        for (int i = 0; i < failures.size(); i++) {
            // And a counting interceptor after the failing one
            Recorder failing = new Recorder("a", new CopyOnWriteArrayList<>(), failures.get(i));
            Recorder counting = new Recorder("z");
            HttpClient client = build(builder(server.origin(), failing, counting));

            // When the caller runs client.execute(web -> web.get("/").send())
            Throwable failure = awaitFailure(client.execute(web -> web.get("/").send()));

            // Then the future fails with that failure (a failure for the null result)
            assertThat(failure).as("case %d", i).isNotNull();
            if (expected.get(i) != null) {
                assertThat(failure).as("case %d", i).isSameAs(expected.get(i));
            }

            // And onAttemptCompleted was called exactly once, with no response, no status and the same failure
            List<Completed> completed = settled(counting, 1);
            assertThat(completed).as("case %d", i).hasSize(1);
            assertThat(completed.get(0).response()).isNull();
            assertThat(completed.get(0).completion().statusCode()).isNull();
            assertThat(completed.get(0).completion().error()).isSameAs(failure);
            assertThat(failing.completed).as("case %d", i).hasSize(1);
        }

        // And the server received no request
        assertThat(server.requests).isEmpty();
        assertThat(server.openedConnections.get()).isZero();
    }

    /** AC-041. */
    @Test
    @DisplayName("AC-041: every buffered terminal path and every refusal before sending notifies exactly once")
    void everyBufferedTerminalPathAndEveryRefusalBeforeSendingNotifiesExactlyOnce() throws Exception {
        // Given servers able to produce each path, one exchange per path with its own recording client
        RecordingServer ok = okServer();
        RecordingServer rejecting = support.server(
                (request, body) -> request.response().setStatusCode(503).end("busy"));
        RecordingServer big =
                support.server((request, body) -> request.response().end("x".repeat((int) LIMIT * 2)));
        RecordingServer silent = silentServer();
        RecordingServer cut = support.server((request, body) -> {
            request.response().putHeader("Content-Length", "1000");
            request.response().write(Buffer.buffer(new byte[100])).onComplete(v -> request.connection()
                    .close());
        });
        int closedPort = unusedPort();
        RecordingServer landing = okServer();
        RecordingServer redirecting = support.server((request, body) -> request.response()
                .setStatusCode(302)
                .putHeader("Location", "http://localhost:" + landing.port() + "/landing")
                .end());

        // completed: 200
        Recorder completedRec = new Recorder("rec");
        await(build(builder(ok.origin(), completedRec))
                .execute(web -> web.get("/").send()));
        List<Completed> completed = settled(completedRec, 1);
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).response().statusCode()).isEqualTo(200);
        assertThat(completed.get(0).completion().statusCode()).isEqualTo(200);
        assertThat(completed.get(0).completion().error()).isNull();
        assertReachedClientAndTarget(completed.get(0), ok.port());

        // status rejected: 503 with HttpExpectations.success()
        Recorder rejectedRec = new Recorder("rec");
        Throwable rejected = awaitFailure(build(builder(rejecting.origin(), rejectedRec))
                .execute(web -> web.get("/").send().expecting(HttpExpectations.success())));
        List<Completed> rejectedDone = settled(rejectedRec, 1);
        assertThat(rejectedDone).hasSize(1);
        assertThat(rejected).isInstanceOf(HttpStatusRejectedException.class);
        assertThat(rejectedDone.get(0).response().statusCode()).isEqualTo(503);
        assertThat(rejectedDone.get(0).completion().statusCode()).isEqualTo(503);
        assertThat(rejectedDone.get(0).completion().error()).isInstanceOf(HttpStatusRejectedException.class);
        assertReachedClientAndTarget(rejectedDone.get(0), rejecting.port());

        // bound exceeded: a response over maxResponseBytes(1024)
        Recorder boundRec = new Recorder("rec");
        Throwable bound = awaitFailure(build(builder(big.origin(), boundRec).maxResponseBytes(LIMIT))
                .execute(web -> web.get("/").send()));
        List<Completed> boundDone = settled(boundRec, 1);
        assertThat(boundDone).hasSize(1);
        assertThat(bound).isInstanceOf(HttpBodyLimitException.class);
        assertThat(boundDone.get(0).completion().error()).isInstanceOf(HttpBodyLimitException.class);
        assertReachedClientAndTarget(boundDone.get(0), big.port());

        // timeout: the server never answers
        Recorder timeoutRec = new Recorder("rec");
        Throwable timeout = awaitFailure(build(builder(silent.origin(), timeoutRec))
                .execute(CallOptions.defaults().readTimeout(200, TimeUnit.MILLISECONDS), web -> web.get("/")
                        .send()));
        List<Completed> timeoutDone = settled(timeoutRec, 1);
        assertThat(timeoutDone).hasSize(1);
        assertThat(timeout).isInstanceOf(TimeoutException.class);
        assertThat(timeoutDone.get(0).response()).isNull();
        assertThat(timeoutDone.get(0).completion().error()).isInstanceOf(TimeoutException.class);
        assertReachedClientAndTarget(timeoutDone.get(0), silent.port());

        // reset: the server closes the socket mid-body
        Recorder resetRec = new Recorder("rec");
        Throwable reset = awaitFailure(build(builder(cut.origin(), resetRec))
                .execute(web -> web.get("/").send()));
        List<Completed> resetDone = settled(resetRec, 1);
        assertThat(resetDone).hasSize(1);
        assertThat(reset).isInstanceOf(HttpClosedException.class);
        assertThat(resetDone.get(0).completion().error()).isSameAs(reset);

        // connect failure: nothing listens
        Recorder connectRec = new Recorder("rec");
        Throwable connect = awaitFailure(
                build(builder(null, connectRec)).execute(web -> web.getAbs("http://127.0.0.1:" + closedPort + "/")
                        .send()));
        List<Completed> connectDone = settled(connectRec, 1);
        assertThat(connectDone).hasSize(1);
        assertThat(connect).isInstanceOf(ConnectException.class);
        assertThat(connectDone.get(0).response()).isNull();
        assertThat(connectDone.get(0).completion().error()).isInstanceOf(ConnectException.class);
        assertReachedClientAndTarget(connectDone.get(0), closedPort);

        // redirect-hop egress denial: the first hop answers 302 to a host the allowlist denies
        Recorder hopRec = new Recorder("rec");
        Throwable hop = awaitFailure(build(builder(redirecting.origin(), hopRec)
                        .webClientOptions(
                                new WebClientOptions().setFollowRedirects(true).setMaxRedirects(5))
                        .egress(EgressPolicy.allowHosts(List.of("127.0.0.1"), true)))
                .execute(web -> web.get("/").send()));
        List<Completed> hopDone = settled(hopRec, 1);
        assertThat(hopDone).hasSize(1);
        assertThat(hop).isInstanceOf(HttpEgressDeniedException.class);
        assertThat(hopDone.get(0).completion().error()).isInstanceOf(HttpEgressDeniedException.class);
        assertThat(landing.requests).isEmpty();

        // cancellation: the signal is cancelled while the server stalls
        Recorder cancelRec = new Recorder("rec");
        CancellationSignal signal = CancellationSignal.create();
        Future<HttpResponse<Buffer>> cancelled = build(builder(silent.origin(), cancelRec))
                .execute(CallOptions.defaults().noReadTimeout().cancellation(signal), web -> web.get("/cancel")
                        .send());
        assertThat(awaitCondition(() -> !silent.requestsTo("/cancel").isEmpty(), HttpClientItSupport.WAIT_MS))
                .isTrue();
        signal.cancel();
        Throwable cancellation = awaitFailure(cancelled);
        List<Completed> cancelDone = settled(cancelRec, 1);
        assertThat(cancelDone).hasSize(1);
        assertThat(cancellation).isInstanceOf(HttpExchangeCancelledException.class);
        assertThat(cancelDone.get(0).completion().error()).isInstanceOf(HttpExchangeCancelledException.class);

        // credential failure: the source fails, nothing is sent
        RecordingServer credentialServer = okServer();
        Recorder credentialRec = new Recorder("rec");
        Throwable credential = awaitFailure(build(builder(credentialServer.origin(), credentialRec)
                        .credentials(new CountingSource(Future.failedFuture(new IllegalStateException("down")))))
                .execute(web -> web.get("/").send()));
        List<Completed> credentialDone = settled(credentialRec, 1);
        assertThat(credentialDone).hasSize(1);
        assertThat(credential).isInstanceOf(HttpCredentialException.class);
        assertThat(credentialDone.get(0).response()).isNull();
        assertThat(credentialDone.get(0).completion().error()).isInstanceOf(HttpCredentialException.class);
        assertThat(credentialServer.requests).isEmpty();

        // initial-target egress denial
        RecordingServer deniedServer = okServer();
        Recorder egressRec = new Recorder("rec");
        Throwable egress = awaitFailure(
                build(builder(null, egressRec).egress(EgressPolicy.allowHosts(List.of("allowed.test"), true)))
                        .execute(web -> web.getAbs(deniedServer.origin() + "/").send()));
        List<Completed> egressDone = settled(egressRec, 1);
        assertThat(egressDone).hasSize(1);
        assertThat(egress).isInstanceOf(HttpEgressDeniedException.class);
        assertThat(egressDone.get(0).response()).isNull();
        assertThat(egressDone.get(0).completion().error()).isInstanceOf(HttpEgressDeniedException.class);
        assertThat(deniedServer.requests).isEmpty();

        // refused request body: over maxRequestBytes(1024)
        RecordingServer bodyServer = okServer();
        Recorder bodyRec = new Recorder("rec");
        Throwable body =
                awaitFailure(build(builder(bodyServer.origin(), bodyRec).maxRequestBytes(LIMIT))
                        .execute(web -> web.post("/").sendBuffer(Buffer.buffer(new byte[(int) LIMIT + 1]))));
        List<Completed> bodyDone = settled(bodyRec, 1);
        assertThat(bodyDone).hasSize(1);
        assertThat(body).isInstanceOfSatisfying(HttpBodyLimitException.class, e -> assertThat(e.direction())
                .isEqualTo(Direction.REQUEST));
        assertThat(bodyDone.get(0).completion().error())
                .isInstanceOfSatisfying(HttpBodyLimitException.class, e -> assertThat(e.direction())
                        .isEqualTo(Direction.REQUEST));
        assertThat(bodyDone.get(0).response()).isNull();
        assertThat(bodyServer.requests).isEmpty();

        // invalid header: a CR LF value added by an interceptor's beforeRequest
        RecordingServer injectServer = okServer();
        Recorder injector = new Recorder(
                "a", new CopyOnWriteArrayList<>(), ctx -> Future.succeededFuture(ctx.withHeader("X-A", "a\r\nX-B: b")));
        Recorder injectCounter = new Recorder("z");
        Throwable injected = awaitFailure(build(builder(injectServer.origin(), injector, injectCounter))
                .execute(web -> web.get("/").send()));
        List<Completed> injectDone = settled(injectCounter, 1);
        assertThat(injectDone).hasSize(1);
        assertThat(injected).isInstanceOf(IllegalArgumentException.class);
        assertThat(injectDone.get(0).completion().error()).isSameAs(injected);
        assertThat(injectDone.get(0).response()).isNull();
        assertThat(injectServer.requests).isEmpty();

        // And every completion has attemptOrdinal 1, identity from the framework and a target of scheme, host
        // and port only
        for (List<Completed> done : List.of(
                completed,
                rejectedDone,
                boundDone,
                timeoutDone,
                resetDone,
                connectDone,
                hopDone,
                cancelDone,
                credentialDone,
                egressDone,
                bodyDone,
                injectDone)) {
            HttpClientAttemptCompletion completion = done.get(0).completion();
            assertThat(completion.attemptOrdinal()).isEqualTo(1);
            assertThat(completion.durationMs()).isGreaterThanOrEqualTo(0);
            assertThat(completion.completedAt()).isNotNull();
            assertThat(completion.target().toString()).doesNotContain("/").doesNotContain("?");
            assertThat(done.get(0).request().callId()).isEqualTo(completion.callId());
            assertThat(done.get(0).request().method()).isEqualTo(done == bodyDone ? "POST" : "GET");
        }

        // And a call cancelled by a signal cancelled before execute, so that no request existed, notifies nobody
        Recorder earlyRec = new Recorder("rec");
        CancellationSignal early = CancellationSignal.create();
        early.cancel();
        RecordingServer earlyServer = okServer();
        Throwable earlyFailure = awaitFailure(build(builder(earlyServer.origin(), earlyRec))
                .execute(CallOptions.defaults().cancellation(early), web -> web.get("/")
                        .send()));
        assertThat(earlyFailure).isInstanceOf(HttpExchangeCancelledException.class);
        assertThat(settled(earlyRec, 0)).isEmpty();
        assertThat(earlyServer.requests).isEmpty();
    }

    private static void assertReachedClientAndTarget(Completed done, int port) {
        assertThat(done.completion().target().scheme()).isEqualTo("http");
        assertThat(done.completion().target().host()).isEqualTo("127.0.0.1");
        assertThat(done.completion().target().port()).isEqualTo(port);
    }

    /** AC-044. */
    @Test
    @DisplayName("AC-044: a throwing interceptor neither changes the outcome nor skips the next")
    void throwingInterceptorNeitherChangesTheOutcomeNorSkipsTheNext() {
        // Given interceptors A to D whose onAttemptCompleted throws, E that records, and a server answering "ok"
        RecordingServer server = okServer();
        ThrowsIllegalState a = new ThrowsIllegalState();
        ThrowsAssertion b = new ThrowsAssertion();
        ThrowsStackOverflow c = new ThrowsStackOverflow();
        ThrowsLinkage d = new ThrowsLinkage();
        Recorder e = new Recorder("e");
        HttpClient client = build(builder(server.origin(), e, d, c, b, a));

        try (LogCapture logs = new LogCapture()) {
            // When the caller runs client.execute(web -> web.get("/").send().map(HttpResponse::bodyAsString))
            String body = await(client.execute(web -> web.get("/").send().map(HttpResponse::bodyAsString)));

            // Then the future succeeds with "ok", unchanged by the throws
            assertThat(body).isEqualTo("ok");

            // And E's onAttemptCompleted was called exactly once, and so was each throwing interceptor
            List<Completed> completed = settled(e, 1);
            assertThat(completed).hasSize(1);
            assertThat(completed.get(0).completion().error()).isNull();
            assertThat(completed.get(0).completion().statusCode()).isEqualTo(200);
            assertThat(List.of(a.calls.get(), b.calls.get(), c.calls.get(), d.calls.get()))
                    .containsExactly(1, 1, 1, 1);

            // And each swallowed throwable was reported through the reporter naming the interceptor and callback
            List<String> records = logs.records();
            for (Throwing thrower : List.of(a, b, c, d)) {
                assertThat(records)
                        .as("report for %s", thrower.getClass().getSimpleName())
                        .anySatisfy(record -> assertThat(record)
                                .contains(thrower.getClass().getName())
                                .contains("onAttemptCompleted"));
            }
            assertThat(records).anySatisfy(record -> assertThat(record).contains("java.lang.IllegalStateException"));
            assertThat(records).anySatisfy(record -> assertThat(record).contains("java.lang.AssertionError"));
            assertThat(records).anySatisfy(record -> assertThat(record).contains("java.lang.StackOverflowError"));
        }

        // And the client keeps serving: the next call also succeeds
        String next = await(client.execute(web -> web.get("/").send().map(HttpResponse::bodyAsString)));
        assertThat(next).isEqualTo("ok");
    }

    /** FR-016, supporting. */
    @Test
    @DisplayName("concurrent exchanges each complete once with their own snapshot")
    void concurrentExchangesEachCompleteOnceWithTheirOwnSnapshot() {
        // Given an interceptor adding X-Pre in beforeRequest, a credential source and a recording server
        int calls = 24;
        RecordingServer server = okServer();
        Recorder recorder = new Recorder(
                "rec",
                new CopyOnWriteArrayList<>(),
                ctx -> Future.succeededFuture(ctx.withHeader("X-Pre", "added-before-credentials")));
        HttpClient client = build(builder(server.origin(), recorder).credentials(CountingSource.bearer()));

        // When 24 exchanges run concurrently, each carrying its own X-N header
        List<Future<HttpResponse<Buffer>>> futures = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            int n = i;
            futures.add(client.execute(
                    web -> web.get("/x").putHeader("X-N", String.valueOf(n)).send()));
        }
        await(Future.all(futures));

        // Then every exchange completed exactly once, each with its own call id and ordinal 1
        List<Completed> completed = settled(recorder, calls);
        assertThat(completed).hasSize(calls);
        assertThat(recorder.contexts).hasSize(calls);
        Set<String> callIds = new HashSet<>();
        Set<String> numbers = new HashSet<>();
        for (Completed done : completed) {
            callIds.add(done.completion().callId());
            numbers.add(done.request().headers().get("X-N"));
            assertThat(done.completion().attemptOrdinal()).isEqualTo(1);
            assertThat(done.completion().statusCode()).isEqualTo(200);

            // And the snapshot holds the interceptor's header, the call's own header and the credential
            assertThat(done.request().headers().get("X-Pre")).isEqualTo("added-before-credentials");
            assertThat(done.request().headers().get("Authorization")).isEqualTo(BEARER);
        }
        assertThat(callIds).hasSize(calls);
        assertThat(numbers).hasSize(calls);

        // And the server saw the interceptor's header and the credential on every request
        assertThat(server.requests).hasSize(calls);
        for (HttpClientItSupport.RecordedRequest request : server.requests) {
            assertThat(request.headers().get("X-Pre")).isEqualTo("added-before-credentials");
            assertThat(request.headers().get("Authorization")).isEqualTo(BEARER);
        }
        for (HttpClientRequestContext context : recorder.contexts) {
            assertThat(context.headers().contains("Authorization")).isFalse();
        }
    }

    /** FR-016, supporting. */
    @Test
    @DisplayName("a call cancelled before a request existed notifies nothing")
    void aCallCancelledBeforeARequestExistedNotifiesNothing() {
        // Given a recording interceptor, a counting server and a signal cancelled before the call
        RecordingServer server = okServer();
        Recorder recorder = new Recorder("rec");
        HttpClient client = build(builder(server.origin(), recorder));
        CancellationSignal signal = CancellationSignal.create();
        signal.cancel();

        // When the caller runs client.execute with that signal
        Throwable failure = awaitFailure(client.execute(
                CallOptions.defaults().cancellation(signal), web -> web.get("/").send()));

        // Then the call fails with the cancellation failure and nothing was sent
        assertThat(failure).isInstanceOf(HttpExchangeCancelledException.class);
        assertThat(server.requests).isEmpty();
        assertThat(server.openedConnections.get()).isZero();

        // And onAttemptCompleted was called 0 times
        assertThat(settled(recorder, 0)).isEmpty();

        // And a later call on the same client notifies once, so the recorder works
        await(client.execute(web -> web.get("/").send()));
        assertThat(settled(recorder, 1)).hasSize(1);

        // And an exchange whose beforeRequest began is always completed: with a beforeRequest that never
        // completes and a cancel 200 ms later, the call fails with the cancellation failure, nothing is sent
        // and every registered interceptor is told once with that failure
        RecordingServer hungServer = okServer();
        Recorder hanging =
                new Recorder("a", new CopyOnWriteArrayList<>(), ctx -> Promise.<HttpClientRequestContext>promise()
                        .future());
        Recorder other = new Recorder("z");
        CancellationSignal hungSignal = CancellationSignal.create();
        Future<HttpResponse<Buffer>> hung = build(builder(hungServer.origin(), hanging, other))
                .execute(CallOptions.defaults().noReadTimeout().cancellation(hungSignal), web -> web.get("/")
                        .send());
        assertThat(awaitCondition(() -> !hanging.contexts.isEmpty(), HttpClientItSupport.WAIT_MS))
                .isTrue();
        support.vertx.setTimer(200, id -> hungSignal.cancel());
        Throwable cancelled = awaitFailure(hung);
        assertThat(cancelled).isInstanceOf(HttpExchangeCancelledException.class);
        assertThat(hungServer.requests).isEmpty();
        assertThat(hungServer.openedConnections.get()).isZero();
        for (Recorder each : List.of(hanging, other)) {
            List<Completed> done = settled(each, 1);
            assertThat(done).hasSize(1);
            assertThat(done.get(0).completion().error()).isSameAs(cancelled);
            assertThat(done.get(0).response()).isNull();
        }
    }
}
