// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.http.client.HttpClientItSupport.RecordedRequest;
import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import dev.vertique.http.client.exception.HttpCredentialException;
import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpEgressDeniedException.Reason;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * Integration tests of per-exchange credentials: the source runs once per exchange hop to the original
 * origin, its headers replace same-named ones, never reach another origin, never appear in a message or
 * log, and plain HTTP to a non-loopback host is refused.
 *
 * <p>Every test dials only loopback addresses. The two origins of a test differ by {@code 127.0.0.1}
 * versus {@code localhost}; {@code api.example.invalid} appears only as a refused target (and, with
 * plain HTTP allowed, as a name that does not resolve).
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class CredentialsIT {

    private static final String SECRET = "s3cret-token";
    private static final String BEARER = "Bearer " + SECRET;

    /** A refusal must come before any name resolution or connect attempt could matter. */
    private static final long FAST_REFUSAL_MS = 500;

    /** The headers a cross-origin hop may carry besides the transport's own. */
    private static final Set<String> SAFELIST = Set.of(
            "accept",
            "accept-language",
            "accept-encoding",
            "user-agent",
            "cache-control",
            "pragma",
            "if-none-match",
            "if-modified-since");

    private static final Set<String> TRANSPORT_HEADERS = Set.of("host", "connection", "content-length");

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

    /** A source returning fixed headers and recording every target it was resolved for. */
    private static final class RecordingSource implements CredentialSource {

        final List<URI> targets = new CopyOnWriteArrayList<>();
        private final Map<String, String> headers;

        RecordingSource(Map<String, String> headers) {
            this.headers = headers;
        }

        static RecordingSource bearer() {
            return new RecordingSource(Map.of("Authorization", BEARER));
        }

        @Override
        public Future<Map<String, String>> resolve(URI target) {
            targets.add(target);
            return Future.succeededFuture(headers);
        }

        List<String> targetStrings() {
            return targets.stream().map(URI::toString).toList();
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

        /** Every text a record carries: message, arguments and the rendered throwable. */
        List<String> records() {
            List<String> texts = new ArrayList<>();
            for (ILoggingEvent event : new ArrayList<>(appender.list)) {
                StringBuilder text = new StringBuilder()
                        .append(event.getLoggerName())
                        .append(' ')
                        .append(event.getFormattedMessage())
                        .append(' ')
                        .append(event.getMessage());
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

    private HttpClientBuilder builder(CredentialSource source) {
        return HttpClientBuilder.create(support.vertx).credentials(source);
    }

    private HttpClient client(CredentialSource source) {
        return build(builder(source));
    }

    private HttpClient clientFollowing(CredentialSource source) {
        return build(builder(source)
                .webClientOptions(
                        new WebClientOptions().setFollowRedirects(true).setMaxRedirects(5)));
    }

    private static void answer(HttpServerRequest request, int status, String body) {
        request.response().setStatusCode(status).end(body);
    }

    private static void redirect(HttpServerRequest request, int status, String location) {
        request.response().setStatusCode(status).putHeader("Location", location).end();
    }

    private static String localhostOrigin(RecordingServer server) {
        return "http://localhost:" + server.port();
    }

    private static int status(Future<HttpResponse<Buffer>> response) {
        return await(response).statusCode();
    }

    private static Set<String> lowerCaseNames(RecordedRequest request) {
        return request.headers().names().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /** Every text a failure exposes: message, rendering, and the same for each suppressed throwable. */
    private static List<String> exposedTexts(Throwable failure) {
        List<String> texts = new ArrayList<>();
        texts.add(String.valueOf(failure.getMessage()));
        texts.add(failure.toString());
        for (Throwable suppressed : failure.getSuppressed()) {
            texts.addAll(exposedTexts(suppressed));
        }
        return texts;
    }

    private static void assertCredentialFailure(Throwable failure, String... forbidden) {
        assertThat(failure).isInstanceOf(HttpCredentialException.class);
        assertThat(failure.getCause()).isNull();
        for (String text : exposedTexts(failure)) {
            for (String word : forbidden) {
                assertThat(text).doesNotContain(word);
            }
        }
    }

    // --- proofs ---

    /** AC-021. */
    @Test
    @DisplayName("credentials resolved per exchange replace a same-named header")
    void credentialsResolvedPerExchangeReplaceASameNamedHeader() {
        // Given a source returning Authorization: Bearer t-<n>, and a server answering 503 then 200
        AtomicInteger counter = new AtomicInteger();
        List<URI> targets = new CopyOnWriteArrayList<>();
        CredentialSource source = target -> {
            targets.add(target);
            return Future.succeededFuture(Map.of("Authorization", "Bearer t-" + counter.incrementAndGet()));
        };
        AtomicInteger served = new AtomicInteger();
        RecordingServer server =
                support.server((request, body) -> answer(request, served.incrementAndGet() == 1 ? 503 : 200, "x"));
        WebClient web = build(builder(source).baseUrl(server.origin())).webClient();

        // When the caller sends the same request twice in sequence, carrying its own Authorization
        int first = status(web.get("/x")
                .putHeader("Authorization", "Bearer caller-value")
                .putHeader("X-Other", "keep")
                .send());
        int second = status(web.get("/x")
                .putHeader("Authorization", "Bearer caller-value")
                .putHeader("X-Other", "keep")
                .send());

        // Then the source was called once per exchange, with the absolute URI of that exchange
        assertThat(first).isEqualTo(503);
        assertThat(second).isEqualTo(200);
        assertThat(counter.get()).isEqualTo(2);
        assertThat(targets.stream().map(URI::toString).toList())
                .containsExactly(server.origin() + "/x", server.origin() + "/x");

        // And each request carries exactly one Authorization header, the resolved one, and X-Other
        assertThat(server.requests).hasSize(2);
        assertThat(server.requests.get(0).headers().getAll("Authorization")).containsExactly("Bearer t-1");
        assertThat(server.requests.get(1).headers().getAll("Authorization")).containsExactly("Bearer t-2");
        assertThat(server.requests.get(0).headers().get("X-Other")).isEqualTo("keep");
        assertThat(server.requests.get(1).headers().get("X-Other")).isEqualTo("keep");
    }

    /** AC-024. */
    @Test
    @DisplayName("a cross-origin redirect drops credentials")
    void crossOriginRedirectDropsCredentials() {
        // Given a source returning Authorization and a second header only the source would add, redirects
        // followed, server B on localhost recording headers and server A redirecting to B and to itself
        Map<String, String> returned = new LinkedHashMap<>();
        returned.put("Authorization", BEARER);
        returned.put("X-Cred-Extra", "added-by-source");
        RecordingSource source = new RecordingSource(returned);
        RecordingServer b = support.server((request, body) -> answer(request, 200, "b"));
        String landingOnB = localhostOrigin(b) + "/landing";
        RecordingServer a = support.server((request, body) -> {
            switch (request.path()) {
                case "/start" -> redirect(request, 302, landingOnB);
                case "/same" -> redirect(request, 302, "/landing");
                default -> answer(request, 200, "a");
            }
        });
        WebClient web = clientFollowing(source).webClient();

        // When the caller sends GET /start and, separately, GET /same to A, with its own custom and
        // safelisted headers
        int start = status(web.getAbs(a.origin() + "/start")
                .putHeader("Accept", "text/plain")
                .putHeader("User-Agent", "ua-1")
                .putHeader("X-Custom", "c1")
                .send());
        int same = status(web.getAbs(a.origin() + "/same")
                .putHeader("Accept", "text/plain")
                .putHeader("X-Custom", "c1")
                .send());

        // Then server B receives the redirected request without any Authorization header
        assertThat(start).isEqualTo(200);
        assertThat(b.requestsTo("/landing")).hasSize(1);
        RecordedRequest onB = b.requestsTo("/landing").get(0);
        assertThat(onB.headers().contains("Authorization")).isFalse();

        // And no header the source would add, and no non-safelisted caller header, reaches B
        assertThat(onB.headers().contains("X-Cred-Extra")).isFalse();
        assertThat(onB.headers().contains("X-Custom")).isFalse();
        assertThat(lowerCaseNames(onB)).allSatisfy(name -> assertThat(name)
                .matches(n -> SAFELIST.contains(n) || TRANSPORT_HEADERS.contains(n)));
        assertThat(onB.headers().get("Accept")).isEqualTo("text/plain");
        assertThat(onB.headers().get("User-Agent")).isEqualTo("ua-1");

        // And server A receives Authorization on /same and on the same-origin /landing
        assertThat(same).isEqualTo(200);
        assertThat(a.requestsTo("/same")).hasSize(1);
        assertThat(a.requestsTo("/same").get(0).headers().getAll("Authorization"))
                .containsExactly(BEARER);
        assertThat(a.requestsTo("/landing")).hasSize(1);
        assertThat(a.requestsTo("/landing").get(0).headers().getAll("Authorization"))
                .containsExactly(BEARER);
        assertThat(a.requestsTo("/landing").get(0).headers().get("X-Cred-Extra"))
                .isEqualTo("added-by-source");

        // And the source was resolved for the two exchanges' first hops and the same-origin hop only
        assertThat(source.targetStrings())
                .containsExactlyInAnyOrder(a.origin() + "/start", a.origin() + "/same", a.origin() + "/landing");
    }

    /** AC-025. */
    @Test
    @DisplayName("credentials are refused over plain HTTP to a non-loopback host by default")
    void credentialsRefusedOverPlainHttpByDefault() {
        // Given a source, an allowlist admitting api.example.invalid and 127.0.0.1 over plain HTTP, no
        // allowCredentialsOverPlainHttp call, and a loopback server recording headers
        RecordingSource source = RecordingSource.bearer();
        EgressPolicy policy = EgressPolicy.allowHosts(List.of("api.example.invalid", "127.0.0.1"), true);
        RecordingServer server = support.server((request, body) -> answer(request, 200, "ok"));
        WebClient web = build(builder(source).egress(policy)).webClient();

        // When the caller sends GET http://api.example.invalid/ and, separately, GET to the loopback server
        long started = System.nanoTime();
        Throwable refused =
                awaitFailure(web.getAbs("http://api.example.invalid/").send());
        long refusalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int loopback = status(web.getAbs(server.origin() + "/").send());

        // Then the first future fails with PLAIN_HTTP_CREDENTIALS, quickly, without resolving the credentials
        assertThat(refused).isInstanceOfSatisfying(HttpEgressDeniedException.class, e -> assertThat(e.reason())
                .isEqualTo(Reason.PLAIN_HTTP_CREDENTIALS));
        assertThat(refusalMs).isLessThan(FAST_REFUSAL_MS);

        // And its message contains neither the secret nor the header name
        assertThat(refused.getMessage()).doesNotContain(SECRET).doesNotContain("Authorization");

        // And the loopback request succeeds, carrying the credential, and was the only one the source saw
        assertThat(loopback).isEqualTo(200);
        assertThat(server.requests).hasSize(1);
        assertThat(server.requests.get(0).headers().getAll("Authorization")).containsExactly(BEARER);
        assertThat(source.targetStrings()).containsExactly(server.origin() + "/");

        // And a client that allows credentials over plain HTTP does not refuse the first target for that
        // reason: it fails because the name does not resolve
        WebClient allowing = build(
                        builder(RecordingSource.bearer()).egress(policy).allowCredentialsOverPlainHttp(true))
                .webClient();
        Throwable unresolved =
                awaitFailure(allowing.getAbs("http://api.example.invalid/").send());
        assertThat(unresolved).isNotInstanceOf(HttpEgressDeniedException.class);
        assertThat(hasInChain(unresolved, UnknownHostException.class)).isTrue();
    }

    private static boolean hasInChain(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /** AC-078. */
    @Test
    @DisplayName("a credential value with a newline never reaches a message or log")
    void aCredentialValueWithANewlineNeverReachesAMessageOrLog() {
        // Given sources returning values with a trailing newline, a trailing space and a leading space, a
        // log capture on every logger, and a counting server
        RecordingServer server = support.server((request, body) -> answer(request, 200, "ok"));
        List<String> values = List.of("t0ken\n", "t0ken ", " t0ken");
        try (LogCapture logs = new LogCapture()) {
            for (String value : values) {
                RecordingSource source = new RecordingSource(Map.of("Authorization", value));
                HttpClient client = build(builder(source).baseUrl(server.origin()));

                // When the caller runs execute for the source
                Throwable failure =
                        awaitFailure(client.execute(web -> web.get("/").send()));

                // Then the future fails with HttpCredentialException and the server received nothing
                assertCredentialFailure(failure, "t0ken", "Authorization");
                assertThat(server.requests)
                        .as("requests after %s", value.strip())
                        .isEmpty();

                // And the failure has no cause type and the source was asked once
                assertThat(((HttpCredentialException) failure).causeType()).isEmpty();
                assertThat(source.targets).hasSize(1);

                // And neither the client nor its builder renders the value
                assertThat(client.toString()).doesNotContain("t0ken");
            }

            // And no captured log record of any logger contains the value or the header name
            assertThat(logs.records()).isNotEmpty().allSatisfy(record -> assertThat(record)
                    .doesNotContain("t0ken")
                    .doesNotContain("Authorization"));
        }
    }

    /** The valid secret never shows in a log record or a rendering either (supporting AC-078). */
    @Test
    @DisplayName("a valid credential value is sent but never appears in a log record or a rendering")
    void aValidCredentialIsNeverLoggedOrRendered() {
        // Given a source with a valid secret, a log capture on every logger, a client and its builder
        RecordingServer server = support.server((request, body) -> answer(request, 200, "ok"));
        RecordingSource source = RecordingSource.bearer();
        HttpClientBuilder builder = builder(source).baseUrl(server.origin());
        try (LogCapture logs = new LogCapture()) {
            HttpClient client = build(builder);

            // When the caller sends a request through the client
            int status = status(client.webClient().get("/").send());

            // Then the server received the secret
            assertThat(status).isEqualTo(200);
            assertThat(server.requests.get(0).headers().get("Authorization")).isEqualTo(BEARER);

            // And no log record, no client rendering and no builder rendering contains it
            assertThat(logs.records()).allSatisfy(record -> assertThat(record).doesNotContain(SECRET));
            assertThat(client.toString()).doesNotContain(SECRET);
            assertThat(builder.toString()).doesNotContain(SECRET);
        }
    }

    /** AC-079. */
    @Test
    @DisplayName("concurrent redirecting exchanges keep their own origin")
    void concurrentRedirectingExchangesKeepTheirOwnOrigin() {
        // Given redirects followed, a counting source, server A (127.0.0.1) answering /x with an immediate
        // redirect to B, /y with a redirect to /landing after 200 ms, and server B (localhost) redirecting
        // /hop back to A's /back after 500 ms
        RecordingSource source = RecordingSource.bearer();
        AtomicReference<RecordingServer> aRef = new AtomicReference<>();
        RecordingServer b = support.server((request, body) -> {
            if (request.path().equals("/hop")) {
                support.vertx.setTimer(
                        500, id -> redirect(request, 302, aRef.get().origin() + "/back"));
            } else {
                answer(request, 200, "b");
            }
        });
        RecordingServer a = support.server((request, body) -> {
            switch (request.path()) {
                case "/x" -> redirect(request, 302, localhostOrigin(b) + "/hop");
                case "/y" -> support.vertx.setTimer(200, id -> redirect(request, 302, "/landing"));
                default -> answer(request, 200, "a");
            }
        });
        aRef.set(a);
        WebClient web = clientFollowing(source).webClient();

        // When the caller sends GET /x and GET /y to A at the same moment on one client
        Future<HttpResponse<Buffer>> x = web.getAbs(a.origin() + "/x").send();
        Future<HttpResponse<Buffer>> y = web.getAbs(a.origin() + "/y").send();
        assertThat(status(x)).isEqualTo(200);
        assertThat(status(y)).isEqualTo(200);

        // Then A receives the credential on /x, on /y and on /landing, the same-origin hop of the second
        // exchange, which ran while the first was at B
        for (String path : List.of("/x", "/y", "/landing")) {
            assertThat(a.requestsTo(path)).as(path).hasSize(1);
            assertThat(a.requestsTo(path).get(0).headers().getAll("Authorization"))
                    .as(path)
                    .containsExactly(BEARER);
        }

        // And B receives none on /hop, and A receives none on /back, where the first exchange returned
        assertThat(b.requestsTo("/hop")).hasSize(1);
        assertThat(b.requestsTo("/hop").get(0).headers().contains("Authorization"))
                .isFalse();
        assertThat(a.requestsTo("/back")).hasSize(1);
        assertThat(a.requestsTo("/back").get(0).headers().contains("Authorization"))
                .isFalse();

        // And the source was called 3 times, for /x, /y and /landing, never for /hop or /back
        assertThat(source.targetStrings())
                .containsExactlyInAnyOrder(a.origin() + "/x", a.origin() + "/y", a.origin() + "/landing");
    }

    /** Supporting proof of credential failures. */
    @Test
    @DisplayName("a failing source and invalid or framework-owned names and values fail the exchange without sending")
    void failingCredentialSourceAndInvalidOrFrameworkOwnedNamesFailTheExchangeWithoutSending() {
        // Given a counting server and a table of sources that fail, throw, return null, or return a
        // name or value the contract rejects
        RecordingServer server = support.server((request, body) -> answer(request, 200, "ok"));
        String value = "v-secret-value";
        Map<String, CredentialSource> sources = new LinkedHashMap<>();
        sources.put("failed future", target -> Future.failedFuture(new IllegalStateException("vault down")));
        sources.put("thrown", target -> {
            throw new IllegalArgumentException("vault down");
        });
        sources.put("null future", target -> null);
        sources.put("null map", target -> Future.succeededFuture(null));
        for (String name : List.of(
                "Content-Length",
                "content-length",
                "Host",
                "host",
                "Transfer-Encoding",
                "Connection",
                "Upgrade",
                "TE",
                "",
                "Bad Name",
                "Bad:Name",
                "X-A\rB",
                "X-A\nB",
                "X-A\u0000B")) {
            sources.put(
                    "name '" + name.replace("\r", "\\r").replace("\n", "\\n").replace("\u0000", "\\0") + "'",
                    target -> Future.succeededFuture(Map.of(name, value)));
        }
        for (String badValue : List.of("a\rb", "a\nb", "a\u0000b", "a\u0001b", "a\tb", "a\u007fb", " a", "a ", "\ta")) {
            sources.put(
                    "value "
                            + badValue.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "|",
                    target -> Future.succeededFuture(Map.of("X-Token", badValue)));
        }

        for (Map.Entry<String, CredentialSource> entry : sources.entrySet()) {
            AtomicInteger calls = new AtomicInteger();
            CredentialSource counted = target -> {
                calls.incrementAndGet();
                return entry.getValue().resolve(target);
            };
            HttpClient client = build(builder(counted).baseUrl(server.origin()));

            // When the caller runs execute for the source
            Throwable failure = awaitFailure(client.execute(web -> web.get("/").send()));

            // Then the future fails with HttpCredentialException, without a cause, exposing neither the
            // value nor the failure text of the source
            assertCredentialFailure(failure, value, "vault down");
            assertThat(calls.get()).as(entry.getKey()).isEqualTo(1);
            assertThat(server.requests).as(entry.getKey()).isEmpty();
        }

        // And a failing future and a throwing source keep only the cause's simple class name
        HttpCredentialException failed = (HttpCredentialException)
                awaitFailure(build(builder(sources.get("failed future")).baseUrl(server.origin()))
                        .execute(web -> web.get("/").send()));
        assertThat(failed.causeType()).isEqualTo(Optional.of("IllegalStateException"));
        HttpCredentialException thrown = (HttpCredentialException)
                awaitFailure(build(builder(sources.get("thrown")).baseUrl(server.origin()))
                        .execute(web -> web.get("/").send()));
        assertThat(thrown.causeType()).isEqualTo(Optional.of("IllegalArgumentException"));
    }

    /** Supporting proof of origin tracking. */
    @Test
    @DisplayName("a chain that left the original origin never gets credentials again")
    void chainThatLeftTheOriginalOriginNeverGetsCredentialsAgain() {
        // Given redirects followed, server A redirecting /go to B, B redirecting /hop back to A's /back,
        // and A's /back redirecting to its own /final
        RecordingSource source = RecordingSource.bearer();
        AtomicReference<RecordingServer> aRef = new AtomicReference<>();
        RecordingServer b = support.server(
                (request, body) -> redirect(request, 302, aRef.get().origin() + "/back"));
        RecordingServer a = support.server((request, body) -> {
            switch (request.path()) {
                case "/go" -> redirect(request, 302, localhostOrigin(b) + "/hop");
                case "/back" -> redirect(request, 302, "/final");
                default -> answer(request, 200, "a");
            }
        });
        aRef.set(a);
        WebClient web = clientFollowing(source).webClient();

        // When the caller sends GET /go
        int go = status(web.getAbs(a.origin() + "/go").send());

        // Then the first hop carried the credential
        assertThat(go).isEqualTo(200);
        assertThat(a.requestsTo("/go").get(0).headers().getAll("Authorization")).containsExactly(BEARER);

        // And the cross-origin hop, the return hop and the hop after it carried none
        assertThat(b.requestsTo("/hop").get(0).headers().contains("Authorization"))
                .isFalse();
        assertThat(a.requestsTo("/back").get(0).headers().contains("Authorization"))
                .isFalse();
        assertThat(a.requestsTo("/final").get(0).headers().contains("Authorization"))
                .isFalse();

        // And the source was resolved once, for the first hop only
        assertThat(source.targetStrings()).containsExactly(a.origin() + "/go");

        // And the next exchange on the same client starts from the original origin again
        assertThat(status(web.getAbs(a.origin() + "/plain").send())).isEqualTo(200);
        assertThat(a.requestsTo("/plain").get(0).headers().getAll("Authorization"))
                .containsExactly(BEARER);
        assertThat(source.targetStrings()).containsExactly(a.origin() + "/go", a.origin() + "/plain");
    }

    /** Supporting proof of origin tracking. */
    @Test
    @DisplayName("no credentials for a server address, a virtual host or a request proxy under allowAll")
    void noCredentialsForAServerAddressVirtualHostOrRequestProxyUnderAllowAll() {
        // Given allowAll, a counting source, a loopback server and a loopback listener acting as the proxy,
        // and a client with no base URL, so the exchange's own origin is the original origin
        RecordingSource source = RecordingSource.bearer();
        RecordingServer server = support.server((request, body) -> answer(request, 200, "ok"));
        RecordingServer proxy = support.server((request, body) -> answer(request, 200, "proxied"));
        WebClient web = build(builder(source).egress(EgressPolicy.allowAll())).webClient();
        String url = server.origin() + "/";

        // When a plain request goes first, as the control that the source and the server work
        assertThat(status(web.getAbs(url).send())).isEqualTo(200);
        assertThat(server.requests.get(0).headers().getAll("Authorization")).containsExactly(BEARER);
        assertThat(source.targets).hasSize(1);

        // And the caller sends a request with a server address, with a virtual host, with a caller-set
        // Host header and with a request-level proxy
        int serverAddress =
                status(web.requestAbs(HttpMethod.GET, SocketAddress.inetSocketAddress(server.port(), "127.0.0.1"), url)
                        .send());
        int virtualHost = status(web.getAbs(url).virtualHost("localhost").send());
        int hostHeader = status(web.getAbs(url).putHeader("Host", "localhost").send());
        int proxied = status(web.getAbs(url)
                .proxy(new ProxyOptions().setHost("127.0.0.1").setPort(proxy.port()))
                .send());

        // Then each proceeds
        assertThat(List.of(serverAddress, virtualHost, hostHeader, proxied)).containsOnly(200);
        assertThat(server.requests).hasSize(4);
        assertThat(proxy.requests).hasSize(1);

        // And the source was not called again, and no Authorization reached the server or the proxy
        assertThat(source.targets).hasSize(1);
        for (RecordedRequest request : server.requests.subList(1, 4)) {
            assertThat(request.headers().contains("Authorization")).isFalse();
        }
        assertThat(proxy.requests.get(0).headers().contains("Authorization")).isFalse();
    }
}
