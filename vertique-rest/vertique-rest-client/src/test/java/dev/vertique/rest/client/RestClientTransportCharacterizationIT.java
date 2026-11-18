// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.exception.RestClientUnavailableException;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import dev.vertique.rest.client.interceptor.RestClientResponseContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SelfSignedCertificate;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Characterization of the request and response behavior the REST client gets from its Vert.x
 * {@code WebClient} transport (AC-047), recorded as observed against real servers on dynamic ports
 * through the public REST client API.
 *
 * <p>These tests record today's behavior; they do not argue for it. Where a test records
 * cross-origin forwarding of a custom header or a followed https-to-http hop, a later change
 * replaces that behavior on purpose and the test is replaced with it. Origins differ by host name:
 * {@code 127.0.0.1} versus {@code localhost}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RestClientTransportCharacterizationIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String LOCALHOST = "localhost";

    // --- Test client interfaces ---

    /** One method per REST verb, each sending an {@code X-Api-Key} header to {@code /start}. */
    @RestClient(name = "transport-verbs")
    @Path("/start")
    @Consumes("text/plain")
    @Produces("text/plain")
    interface Verbs {
        @GET
        Future<HttpClientResponse> get(@HeaderParam("X-Api-Key") String key);

        @POST
        Future<HttpClientResponse> post(@HeaderParam("X-Api-Key") String key, String body);

        @PUT
        Future<HttpClientResponse> put(@HeaderParam("X-Api-Key") String key, String body);

        @PATCH
        Future<HttpClientResponse> patch(@HeaderParam("X-Api-Key") String key, String body);

        @DELETE
        Future<HttpClientResponse> delete(@HeaderParam("X-Api-Key") String key);

        @HEAD
        Future<HttpClientResponse> head(@HeaderParam("X-Api-Key") String key);
    }

    /** Reads whatever the server returns for a path. */
    @RestClient(name = "transport-content")
    @Produces("application/json")
    interface Content {
        @GET
        @Path("/{p}")
        Future<HttpClientResponse> raw(@PathParam("p") String p);

        @GET
        @Path("/{p}")
        Future<String> text(@PathParam("p") String p);
    }

    /** A plain GET against a fixed path, for header and redirect scenarios. */
    @RestClient(name = "transport-plain")
    @Produces("text/plain")
    interface Plain {
        @GET
        @Path("/{p}")
        Future<HttpClientResponse> get(@PathParam("p") String p);

        @POST
        @Path("/{p}")
        @Consumes("text/plain")
        Future<HttpClientResponse> post(@PathParam("p") String p, String body);
    }

    /** A call with a per-call absolute URL and a query parameter. */
    @RestClient(name = "transport-url")
    @Produces("text/plain")
    interface UrlApi {
        @GET
        Future<HttpClientResponse> viaUrl(@Url URI url, @QueryParam("added") String added);

        @GET
        @Path("/original")
        Future<HttpClientResponse> original(@QueryParam("q") String q);
    }

    // --- Fixtures ---

    /** One request as a server saw it. */
    record Received(String method, String uri, Map<String, List<String>> headers) {
        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null ? null : values.get(0);
        }

        List<String> all(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }
    }

    /** A real HTTP server recording every request, answering through a replaceable handler. */
    static final class Origin {
        final HttpServer server;
        final String scheme;
        final String host;
        final List<Received> received = Collections.synchronizedList(new ArrayList<>());
        volatile Consumer<HttpServerRequest> handler = Origin::ok;

        Origin(HttpServer server, String scheme, String host) {
            this.server = server;
            this.scheme = scheme;
            this.host = host;
        }

        String base() {
            return scheme + "://" + host + ":" + server.actualPort();
        }

        List<Received> snapshot() {
            synchronized (received) {
                return new ArrayList<>(received);
            }
        }

        static void ok(HttpServerRequest request) {
            request.response().setStatusCode(200).end("ok");
        }

        static Consumer<HttpServerRequest> redirect(int status, String location) {
            return request -> request.response()
                    .setStatusCode(status)
                    .putHeader("Location", location)
                    .end();
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
        List<Throwable> failures = new ArrayList<>();
        for (RestClientBuilder builder : builders) {
            try {
                await(builder.close());
            } catch (Throwable t) {
                failures.add(t);
            }
        }
        for (Origin origin : origins) {
            try {
                await(origin.server.close());
            } catch (Throwable t) {
                failures.add(t);
            }
        }
        if (!failures.isEmpty()) {
            RuntimeException first = new RuntimeException("teardown failed", failures.get(0));
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
    }

    private Origin startOrigin(String host) {
        return startOrigin(host, new HttpServerOptions(), "http");
    }

    private Origin startOrigin(String host, HttpServerOptions options, String scheme) {
        HttpServer server = vertx.createHttpServer(options);
        Origin origin = new Origin(server, scheme, host);
        server.requestHandler(request -> {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            request.headers()
                    .forEach(e -> headers.computeIfAbsent(e.getKey().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(e.getValue()));
            origin.received.add(new Received(request.method().name(), request.uri(), headers));
            origin.handler.accept(request);
        });
        await(server.listen(0, host));
        origins.add(origin);
        return origin;
    }

    private RestClientBuilder builder() {
        RestClientBuilder builder = RestClientBuilder.create(vertx);
        builders.add(builder);
        return builder;
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

    private static Throwable failureOrNull(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new AssertionError("future did not complete", e);
        }
    }

    private static RestClientInterceptor headers(Map<String, String> headers) {
        return new RestClientInterceptor() {
            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext ctx) {
                RestClientRequestContext result = ctx;
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    result = result.withHeader(e.getKey(), e.getValue());
                }
                return Future.succeededFuture(result);
            }
        };
    }

    private static WebClientOptions insecureTls() {
        return new WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false);
    }

    @Test
    @DisplayName("A header put twice on one request is sent once, with the last value")
    void repeatedHeaderIsReplacedNotAppended() {
        // Given a server and an interceptor that adds X-Dup twice
        Origin origin = startOrigin(LOOPBACK);
        RestClientInterceptor twice = new RestClientInterceptor() {
            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext ctx) {
                ctx.headers().add("X-Dup", "a").add("X-Dup", "b");
                return Future.succeededFuture(ctx);
            }
        };
        Plain client = builder().baseUrl(origin.base()).register(twice).build(Plain.class);

        // When the call is made
        await(client.get("dup"));

        // Then the server receives a single X-Dup header with the last value
        assertThat(origin.snapshot()).hasSize(1);
        assertThat(origin.snapshot().get(0).all("X-Dup")).containsExactly("b");
    }

    @Test
    @DisplayName("A 204 and an empty 200 read as no body; a non-empty body reads as its bytes")
    void emptyResponseBodyReadsAsNullAndNonEmptyAsBytes() {
        // Given a server answering 204, an empty 200 and a 200 with a body
        Origin origin = startOrigin(LOOPBACK);
        origin.handler = request -> {
            switch (request.path()) {
                case "/no-content" -> request.response().setStatusCode(204).end();
                case "/empty" -> request.response().setStatusCode(200).end();
                default -> request.response().setStatusCode(200).end(Buffer.buffer(new byte[] {1, 2, 3}));
            }
        };
        List<RestClientResponseContext> contexts = Collections.synchronizedList(new ArrayList<>());
        RestClientInterceptor capture = new RestClientInterceptor() {
            @Override
            public void onResponse(RestClientRequestContext request, RestClientResponseContext response) {
                contexts.add(response);
            }
        };
        Content client = builder().baseUrl(origin.base()).register(capture).build(Content.class);

        // When each is read as a typed value and as the raw response
        String typed204 = await(client.text("no-content"));
        String typedEmpty = await(client.text("empty"));
        HttpClientResponse raw204 = await(client.raw("no-content"));
        HttpClientResponse rawEmpty = await(client.raw("empty"));
        HttpClientResponse rawBody = await(client.raw("body"));

        // Then empty responses carry no typed value and an empty (never null) body
        assertThat(typed204).isNull();
        assertThat(typedEmpty).isNull();
        assertThat(raw204.statusCode()).isEqualTo(204);
        assertThat(raw204.body()).isNotNull();
        assertThat(raw204.body().length()).isZero();
        assertThat(rawEmpty.statusCode()).isEqualTo(200);
        assertThat(rawEmpty.body()).isNotNull();
        assertThat(rawEmpty.body().length()).isZero();
        // And the non-empty body is its exact bytes
        assertThat(rawBody.body().getBytes()).containsExactly(1, 2, 3);
        // And the response context seen by interceptors follows the same rule
        assertThat(contexts).hasSize(5);
        assertThat(contexts.get(0).body()).isNotNull();
        assertThat(contexts.get(0).body().length()).isZero();
        assertThat(contexts.get(1).body().length()).isZero();
        assertThat(contexts.get(4).body().getBytes()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("The configured user agent is sent; none is sent when disabled")
    void userAgentIsSentWhenEnabledAndAbsentWhenDisabled() {
        // Given a server and three clients: default, configured and disabled
        Origin origin = startOrigin(LOOPBACK);
        Plain byDefault = builder().baseUrl(origin.base()).build(Plain.class);
        Plain configured = builder()
                .baseUrl(origin.base())
                .webClientOptions(new WebClientOptions().setUserAgent("characterization-agent/1"))
                .build(Plain.class);
        Plain disabled = builder()
                .baseUrl(origin.base())
                .webClientOptions(new WebClientOptions().setUserAgentEnabled(false))
                .build(Plain.class);

        // When each makes a call
        await(byDefault.get("ua"));
        await(configured.get("ua"));
        await(disabled.get("ua"));

        // Then the default is the Vert.x agent, the configured one is sent, and the disabled one is absent
        List<Received> seen = origin.snapshot();
        assertThat(seen.get(0).header("User-Agent")).startsWith("Vert.x-WebClient/");
        assertThat(seen.get(1).header("User-Agent")).isEqualTo("characterization-agent/1");
        assertThat(seen.get(2).header("User-Agent")).isNull();
    }

    @Test
    @DisplayName("Redirects: 16 followed, the 17th not; 303 becomes GET; a 307 for POST is returned")
    void redirectMethodHandlingAndLimitsFollowVertx() {
        // Given a server with a redirect chain, an endless loop, a 303 and a 307
        Origin origin = startOrigin(LOOPBACK);
        origin.handler = request -> {
            String path = request.path();
            if (path.startsWith("/chain-")) {
                int n = Integer.parseInt(path.substring("/chain-".length()));
                if (n < 16) {
                    Origin.redirect(302, "/chain-" + (n + 1)).accept(request);
                } else {
                    Origin.ok(request);
                }
            } else if (path.startsWith("/loop-")) {
                int n = Integer.parseInt(path.substring("/loop-".length()));
                Origin.redirect(302, "/loop-" + (n + 1)).accept(request);
            } else if (path.equals("/see-other")) {
                Origin.redirect(303, "/final").accept(request);
            } else if (path.equals("/temporary")) {
                Origin.redirect(307, "/final").accept(request);
            } else {
                Origin.ok(request);
            }
        };
        Plain client = builder().baseUrl(origin.base()).build(Plain.class);

        // When a chain of exactly 16 redirects is followed
        HttpClientResponse chained = await(client.get("chain-0"));
        int chainRequests = origin.snapshot().size();
        origin.received.clear();

        // Then all 16 are followed: 17 requests and the final 200
        assertThat(chained.statusCode()).isEqualTo(200);
        assertThat(chainRequests).isEqualTo(17);

        // When an endless loop is followed
        Throwable loopFailure = failureOf(client.get("loop-0"));
        int loopRequests = origin.snapshot().size();
        origin.received.clear();

        // Then the 17th redirect is not followed: 17 requests and the 302 is surfaced
        assertThat(loopRequests).isEqualTo(17);
        assertThat(loopFailure).isInstanceOf(RestClientResponseException.class);
        assertThat(((RestClientResponseException) loopFailure).statusCode()).isEqualTo(302);

        // When a POST gets a 303
        HttpClientResponse seeOther = await(client.post("see-other", "payload"));
        List<Received> afterSeeOther = origin.snapshot();
        origin.received.clear();

        // Then it is followed as a GET
        assertThat(seeOther.statusCode()).isEqualTo(200);
        assertThat(afterSeeOther).extracting(Received::method).containsExactly("POST", "GET");
        assertThat(afterSeeOther.get(1).uri()).isEqualTo("/final");

        // When a POST gets a 307
        Throwable temporary = failureOf(client.post("temporary", "payload"));

        // Then it is not followed: the 307 is surfaced after a single request
        assertThat(temporary).isInstanceOf(RestClientResponseException.class);
        assertThat(((RestClientResponseException) temporary).statusCode()).isEqualTo(307);
        assertThat(origin.snapshot()).extracting(Received::method).containsExactly("POST");
    }

    @Test
    @DisplayName("Same-origin and cross-origin redirects drop the recorded header sets")
    void redirectHeaderDropsSameOriginAndCrossOriginAreRecorded() {
        // Given two origins and a request carrying credential, cookie, length and custom headers
        Origin first = startOrigin(LOOPBACK);
        Origin other = startOrigin(LOCALHOST);
        first.handler = request -> {
            if (request.path().equals("/same")) {
                Origin.redirect(302, "/same-target").accept(request);
            } else if (request.path().equals("/cross")) {
                Origin.redirect(302, other.base() + "/cross-target").accept(request);
            } else {
                Origin.ok(request);
            }
        };
        Map<String, String> sent = new LinkedHashMap<>();
        sent.put("Authorization", "Bearer secret");
        sent.put("Proxy-Authorization", "Basic proxy");
        sent.put("Cookie", "session=abc");
        sent.put("Content-Length", "0");
        sent.put("X-Api-Key", "key-1");
        sent.put("X-Custom", "custom-1");
        Plain client = builder().baseUrl(first.base()).register(headers(sent)).build(Plain.class);

        // When a same-origin redirect and a cross-origin redirect are followed
        await(client.get("same"));
        await(client.get("cross"));

        // Then each hop's received header names are exactly the recorded sets
        List<Received> firstSeen = first.snapshot();
        Received sameFirstHop = firstSeen.get(0);
        Received sameSecondHop = firstSeen.get(1);
        Received crossFirstHop = firstSeen.get(2);
        Received crossSecondHop = other.snapshot().get(0);

        assertThat(sameFirstHop.uri()).isEqualTo("/same");
        assertThat(sameSecondHop.uri()).isEqualTo("/same-target");
        assertThat(crossFirstHop.uri()).isEqualTo("/cross");
        assertThat(crossSecondHop.uri()).isEqualTo("/cross-target");

        // Credentials survive a same-origin hop but not a cross-origin one; a custom header survives a same-origin hop
        assertThat(sameSecondHop.header("Authorization")).isEqualTo("Bearer secret");
        assertThat(sameSecondHop.header("X-Custom")).isEqualTo("custom-1");
        assertThat(crossSecondHop.header("Authorization")).isNull();
        assertThat(droppedOnHop(sameFirstHop, sameSecondHop, sent))
                .containsExactlyInAnyOrder("cookie", "content-length");
        assertThat(droppedOnHop(crossFirstHop, crossSecondHop, sent))
                .containsExactlyInAnyOrder(
                        "cookie", "content-length", "authorization", "proxy-authorization", "x-api-key", "x-custom");
    }

    private static TreeSet<String> droppedOnHop(Received firstHop, Received nextHop, Map<String, String> sent) {
        TreeSet<String> dropped = new TreeSet<>();
        for (String name : sent.keySet()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (firstHop.header(lower) != null && nextHop.header(lower) == null) {
                dropped.add(lower);
            }
        }
        return dropped;
    }

    @Test
    @DisplayName(
            "Cross-origin redirects drop custom headers for every REST verb, refuse https to http and refuse userinfo")
    void crossOriginRedirectFactsAreReplacedByTheSafelistAndTheDowngradeRefusal() throws Exception {
        // AC-070, replacing the recorded forwarding, the followed downgrade and the followed userinfo
        // redirect. Given a redirect from one origin to another: 302 for GET and HEAD, 303 for the rest
        Origin first = startOrigin(LOOPBACK);
        Origin other = startOrigin(LOCALHOST);
        first.handler = request -> {
            String method = request.method().name();
            int status = method.equals("GET") || method.equals("HEAD") ? 302 : 303;
            Origin.redirect(status, other.base() + "/landing").accept(request);
        };
        Verbs client = builder().baseUrl(first.base()).build(Verbs.class);

        // When each verb follows the redirect
        List<Future<HttpClientResponse>> calls = new ArrayList<>();
        calls.add(client.get("key-GET"));
        calls.add(client.post("key-POST", "body"));
        calls.add(client.put("key-PUT", "body"));
        calls.add(client.delete("key-DELETE"));
        calls.add(client.patch("key-PATCH", "body"));
        calls.add(client.head("key-HEAD"));
        calls.forEach(RestClientTransportCharacterizationIT::await);

        // Then every verb sent the custom header on its first hop, and the other origin received
        // six followed requests (the GET after a 303, HEAD staying HEAD) and none of them carries it
        Map<String, Received> firstHop = new LinkedHashMap<>();
        first.snapshot().forEach(r -> firstHop.put(r.header("X-Api-Key"), r));
        for (String verb : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD")) {
            assertThat(firstHop.get("key-" + verb).method()).isEqualTo(verb);
        }
        assertThat(other.snapshot()).hasSize(6);
        assertThat(other.snapshot())
                .allSatisfy(r -> assertThat(r.header("X-Api-Key")).isNull());
        assertThat(other.snapshot())
                .extracting(Received::method)
                .containsExactlyInAnyOrder("GET", "GET", "GET", "GET", "GET", "HEAD");

        // Given an https origin that redirects to a plain http origin
        SelfSignedCertificate certificate = SelfSignedCertificate.create();
        try {
            Origin secure = startOrigin(
                    LOOPBACK,
                    new HttpServerOptions().setSsl(true).setKeyCertOptions(certificate.keyCertOptions()),
                    "https");
            Origin plain = startOrigin(LOOPBACK);
            secure.handler = request ->
                    Origin.redirect(302, plain.base() + "/downgraded").accept(request);
            Verbs secureClient = builder()
                    .baseUrl(secure.base())
                    .webClientOptions(insecureTls())
                    .build(Verbs.class);

            // When the call follows the redirect
            Throwable downgrade = failureOf(secureClient.get("tls-key"));

            // Then the hop is refused as a RestClientException caused by the SCHEME refusal, and the
            // plain server receives nothing
            assertThat(downgrade).isInstanceOf(RestClientException.class);
            assertThat(downgrade.getCause().getClass().getSimpleName()).isEqualTo("HttpEgressDeniedException");
            assertThat(String.valueOf(
                            downgrade.getCause().getClass().getMethod("reason").invoke(downgrade.getCause())))
                    .isEqualTo("SCHEME");
            assertThat(secure.snapshot()).hasSize(1);
            assertThat(plain.snapshot()).isEmpty();
        } finally {
            certificate.delete();
        }

        // Given a redirect whose Location carries userinfo
        Origin userinfoSource = startOrigin(LOOPBACK);
        Origin userinfoTarget = startOrigin(LOCALHOST);
        userinfoSource.handler =
                Origin.redirect(302, "http://u:p@" + LOCALHOST + ":" + userinfoTarget.server.actualPort() + "/target");
        Verbs userinfoClient = builder().baseUrl(userinfoSource.base()).build(Verbs.class);

        // When the call is made
        Throwable userinfo = failureOf(userinfoClient.get("userinfo-key"));

        // Then it is refused as a RestClientException caused by the USER_INFO refusal and the target
        // receives nothing
        assertThat(userinfo).isInstanceOf(RestClientException.class);
        assertThat(userinfo.getCause().getClass().getSimpleName()).isEqualTo("HttpEgressDeniedException");
        assertThat(String.valueOf(
                        userinfo.getCause().getClass().getMethod("reason").invoke(userinfo.getCause())))
                .isEqualTo("USER_INFO");
        assertThat(userinfoTarget.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("A request header containing CR and LF has a recorded outcome")
    void requestHeaderContainingCrLfOutcomeIsRecordedToday() {
        // Given a server and a header value with an embedded line break and a second header line
        Origin origin = startOrigin(LOOPBACK);
        Verbs client = builder().baseUrl(origin.base()).build(Verbs.class);

        // When the call is made
        Future<HttpClientResponse> call = client.get("a\r\nX-Injected: yes");
        Throwable failure = failureOf(call);

        // Then the call fails with the transport's own unmapped IllegalArgumentException and nothing is sent
        assertThat(failure).isExactlyInstanceOf(IllegalArgumentException.class);
        assertThat(failure.getMessage()).startsWith("only ' ' and '\\t' are allowed after '\\n'");
        assertThat(origin.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("A call after the builder is closed fails with the recorded identity")
    void callAfterBuilderCloseFailsWithTheRecordedIdentity() {
        // Given a built client that worked, then its builder is closed
        Origin origin = startOrigin(LOOPBACK);
        RestClientBuilder builder = builder().baseUrl(origin.base());
        Plain client = builder.build(Plain.class);
        await(client.get("before"));
        await(builder.close());
        origin.received.clear();

        // When the proxy is called again
        Throwable failure = failureOf(client.get("after"));

        // Then it fails as unavailable because the resilience runtime is closed, and nothing reaches the server
        assertThat(origin.snapshot()).isEmpty();
        assertThat(failure).isExactlyInstanceOf(RestClientUnavailableException.class);
        assertThat(failure.getMessage()).isEqualTo("REST client runtime is closed");
        assertThat(failure.getCause()).isExactlyInstanceOf(ResilienceClosedException.class);
    }

    @Test
    @DisplayName("A per-call URL and an interceptor-rewritten URI reach the servers as recorded")
    void urlValueAndInterceptorRewrittenUriReachTheServerAsRecorded() {
        // Given a base-url origin, a per-call origin and a rewrite target on another host name
        Origin base = startOrigin(LOOPBACK);
        Origin perCall = startOrigin(LOOPBACK);
        Origin rewritten = startOrigin(LOCALHOST);
        RestClientInterceptor rewrite = new RestClientInterceptor() {
            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext ctx) {
                if (ctx.requestUri().contains("/original")) {
                    return Future.succeededFuture(ctx.withRequestUri(rewritten.base() + "/rewritten/path?y=2"));
                }
                return Future.succeededFuture(ctx);
            }
        };
        UrlApi client = builder().baseUrl(base.base()).register(rewrite).build(UrlApi.class);

        // When the call uses a @Url value with its own query, and another call is rewritten
        await(client.viaUrl(URI.create(perCall.base() + "/abs/path?x=1"), "added value"));
        await(client.original("kept"));

        // Then the targets are recorded: the base origin is never contacted
        assertThat(base.snapshot()).isEmpty();
        assertThat(perCall.snapshot()).extracting(Received::uri).containsExactly("/abs/path?x=1&added=added%20value");
        assertThat(rewritten.snapshot()).extracting(Received::uri).containsExactly("/rewritten/path?y=2");
        assertThat(rewritten.snapshot().get(0).header("Host"))
                .isEqualTo(LOCALHOST + ":" + rewritten.server.actualPort());
    }
}
