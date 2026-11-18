// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.resilience.BackoffStrategy;
import dev.vertique.resilience.annotation.CircuitBreaker;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.exception.RestClientUnavailableException;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SelfSignedCertificate;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * A REST client follows the governed redirect rules: on a hop that changes origin only the
 * safelisted request headers are forwarded, a hop from https to http and a {@code Location} that
 * carries userinfo are refused as a {@link RestClientException}, and same-origin hops and
 * Vert.x's method handling are unchanged. Servers run on dynamic ports and differ by host name:
 * {@code 127.0.0.1} versus {@code localhost}. The refusal's identity is read from the failure's cause
 * by name, so this class does not depend on the HTTP client module's types.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RestClientRedirectSafetyIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String LOCALHOST = "localhost";

    // --- Test client interfaces ---

    @RestClient(name = "redirect-plain")
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

    /** A client with the default retry policy, up to two retries and a breaker that opens on one failure. */
    @RestClient(name = "redirect-guarded")
    @Produces("text/plain")
    @CircuitBreaker(maxFailures = 1, timeoutMs = 5_000, resetTimeoutMs = 30_000)
    interface Guarded {
        @GET
        @Path("/{p}")
        @Retry(maxRetries = 2, backoff = NoDelayBackoff.class)
        Future<HttpClientResponse> get(@PathParam("p") String p);
    }

    /** No delay between retries, so the test stays fast. */
    public static class NoDelayBackoff implements BackoffStrategy {
        /** Creates the strategy. */
        public NoDelayBackoff() {}

        @Override
        public long delay(int retryCount) {
            return 0L;
        }
    }

    // --- Fixtures ---

    record Received(String method, String uri, Map<String, List<String>> headers) {
        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null ? null : values.get(0);
        }
    }

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

        List<Received> requestsFor(String uri) {
            return snapshot().stream().filter(r -> r.uri().equals(uri)).toList();
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
    private final List<SelfSignedCertificate> certificates = new ArrayList<>();
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
        certificates.forEach(SelfSignedCertificate::delete);
        if (!failures.isEmpty()) {
            RuntimeException first = new RuntimeException("teardown failed", failures.get(0));
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
    }

    private Origin startOrigin(String host) {
        return startOrigin(host, new HttpServerOptions(), "http");
    }

    private Origin startSecureOrigin(String host) {
        SelfSignedCertificate certificate = SelfSignedCertificate.create();
        certificates.add(certificate);
        return startOrigin(
                host, new HttpServerOptions().setSsl(true).setKeyCertOptions(certificate.keyCertOptions()), "https");
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

    private static Map<String, String> sentHeaders() {
        Map<String, String> sent = new LinkedHashMap<>();
        sent.put("Accept", "text/plain");
        sent.put("User-Agent", "ua-1");
        sent.put("Cache-Control", "no-cache");
        sent.put("X-Api-Key", "k1");
        sent.put("X-Custom", "c1");
        return sent;
    }

    /** The name of the refused rule: the failure's cause is the HTTP client's egress refusal. */
    private static String refusalReason(Throwable failure) throws Exception {
        Throwable cause = failure.getCause();
        assertThat(cause).as("the failure carries the refusal as its cause").isNotNull();
        assertThat(cause.getClass().getSimpleName()).isEqualTo("HttpEgressDeniedException");
        return String.valueOf(cause.getClass().getMethod("reason").invoke(cause));
    }

    @Test
    @DisplayName("A cross-origin redirect forwards only safelisted headers and refuses a downgrade or userinfo")
    void crossOriginRedirectForwardsOnlySafelistedHeadersAndRefusesDowngrade() throws Exception {
        // AC-070. Given server A on 127.0.0.1, server B on localhost, and an https server C whose
        // redirect leads back to A over plain http
        Origin a = startOrigin(LOOPBACK);
        Origin b = startOrigin(LOCALHOST);
        Origin c = startSecureOrigin(LOOPBACK);
        a.handler = request -> {
            switch (request.path()) {
                case "/start" -> Origin.redirect(302, b.base() + "/landing").accept(request);
                case "/same" -> Origin.redirect(302, "/landing").accept(request);
                case "/cred" ->
                    Origin.redirect(302, "http://u:p@" + LOCALHOST + ":" + b.server.actualPort() + "/cred-landing")
                            .accept(request);
                default -> Origin.ok(request);
            }
        };
        c.handler = Origin.redirect(302, a.base() + "/plain");
        Plain plain =
                builder().baseUrl(a.base()).register(headers(sentHeaders())).build(Plain.class);
        Plain secure = builder()
                .baseUrl(c.base())
                .webClientOptions(insecureTls())
                .register(headers(sentHeaders()))
                .build(Plain.class);

        // When the method is called against /start, /same and server C's /down
        await(plain.get("start"));
        await(plain.get("same"));
        Throwable downgrade = failureOrNull(secure.get("down"));
        Throwable userinfo = failureOrNull(plain.get("cred"));

        // Then server B receives the safelisted headers and neither X-Api-Key nor X-Custom
        List<Received> landing = b.requestsFor("/landing");
        assertThat(landing).hasSize(1);
        assertThat(landing.get(0).header("Accept")).isEqualTo("text/plain");
        assertThat(landing.get(0).header("User-Agent")).isEqualTo("ua-1");
        assertThat(landing.get(0).header("Cache-Control")).isEqualTo("no-cache");
        assertThat(landing.get(0).header("X-Api-Key")).isNull();
        assertThat(landing.get(0).header("X-Custom")).isNull();

        // And server A receives both custom headers on the same-origin hop of /same
        List<Received> sameHop = a.requestsFor("/landing");
        assertThat(sameHop).hasSize(1);
        assertThat(sameHop.get(0).header("X-Api-Key")).isEqualTo("k1");
        assertThat(sameHop.get(0).header("X-Custom")).isEqualTo("c1");

        // And the downgrade is refused as a RestClientException with the SCHEME reason and a constant
        // message that names no target, and server A never sees the plain request
        assertThat(downgrade).as("the https-to-http hop is refused").isInstanceOf(RestClientException.class);
        assertThat(refusalReason(downgrade)).isEqualTo("SCHEME");
        assertThat(downgrade.getMessage()).doesNotContain(LOOPBACK, LOCALHOST, "/plain", "://");
        assertThat(c.requestsFor("/down")).hasSize(1);
        assertThat(a.requestsFor("/plain")).isEmpty();

        // And a Location with userinfo is refused with the USER_INFO reason, and server B never sees it
        assertThat(userinfo).as("the userinfo redirect is refused").isInstanceOf(RestClientException.class);
        assertThat(refusalReason(userinfo)).isEqualTo("USER_INFO");
        assertThat(userinfo.getMessage()).doesNotContain("u:p", LOCALHOST, "/cred-landing");
        assertThat(b.requestsFor("/cred-landing")).isEmpty();
    }

    @Test
    @DisplayName("A same-origin hop keeps its headers and Vert.x method handling is unchanged")
    void sameOriginHopAndMethodHandlingAreUnchanged() {
        // FR-031. Given a server with a same-origin 302, a 303 and a 307
        Origin a = startOrigin(LOOPBACK);
        a.handler = request -> {
            switch (request.path()) {
                case "/same" -> Origin.redirect(302, "/landing").accept(request);
                case "/see-other" -> Origin.redirect(303, "/final").accept(request);
                case "/temporary" -> Origin.redirect(307, "/final").accept(request);
                default -> Origin.ok(request);
            }
        };
        Plain client =
                builder().baseUrl(a.base()).register(headers(sentHeaders())).build(Plain.class);

        // When a GET follows a same-origin 302, a POST gets a 303 and a POST gets a 307
        HttpClientResponse same = await(client.get("same"));
        HttpClientResponse seeOther = await(client.post("see-other", "payload"));
        Throwable temporary = failureOf(client.post("temporary", "payload"));

        // Then the same-origin hop forwards the custom headers
        assertThat(same.statusCode()).isEqualTo(200);
        Received hop = a.requestsFor("/landing").get(0);
        assertThat(hop.header("X-Api-Key")).isEqualTo("k1");
        assertThat(hop.header("X-Custom")).isEqualTo("c1");

        // And the 303 is followed as a GET
        assertThat(seeOther.statusCode()).isEqualTo(200);
        assertThat(a.requestsFor("/see-other")).extracting(Received::method).containsExactly("POST");
        assertThat(a.requestsFor("/final")).extracting(Received::method).containsExactly("GET");

        // And the same-origin 307 for a POST is returned, after a single request
        assertThat(temporary).isInstanceOf(RestClientResponseException.class);
        assertThat(((RestClientResponseException) temporary).statusCode()).isEqualTo(307);
        assertThat(a.requestsFor("/temporary")).hasSize(1);
        assertThat(a.requestsFor("/final")).hasSize(1);
    }

    @Test
    @DisplayName("A refused downgrade is not retried by the default policy and counts as a breaker failure")
    void aRefusedDowngradeIsNotRetriedByTheDefaultRestPolicyAndCountsAsARestBreakerFailure() {
        // Given an https server whose redirect leads to a plain http server, and a client with the
        // default retry policy (recorded), two allowed retries and a breaker that opens on one failure
        Origin plainTarget = startOrigin(LOOPBACK);
        Origin secure = startSecureOrigin(LOOPBACK);
        secure.handler = Origin.redirect(302, plainTarget.base() + "/plain");
        List<Throwable> seen = Collections.synchronizedList(new ArrayList<>());
        List<Boolean> decisions = Collections.synchronizedList(new ArrayList<>());
        DefaultRestClientRetryPolicy defaults = new DefaultRestClientRetryPolicy();
        Guarded client = builder()
                .baseUrl(secure.base())
                .webClientOptions(insecureTls())
                .retryPolicy((error, retryCount) -> {
                    boolean retry = defaults.shouldRetry(error, retryCount);
                    seen.add(error);
                    decisions.add(retry);
                    return retry;
                })
                .build(Guarded.class);

        // When the call is made, and then made again
        Throwable first = failureOf(client.get("down"));
        Throwable second = failureOf(client.get("down"));

        // Then the refusal reached the retry predicate once and was not retried
        assertThat(first).isInstanceOf(RestClientException.class);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).isInstanceOf(RestClientException.class);
        assertThat(decisions).containsExactly(false);

        // And it counted as a breaker failure: the breaker is open for the second call, which
        // never reached the server
        assertThat(second).isInstanceOf(RestClientUnavailableException.class);
        assertThat(secure.requestsFor("/down")).hasSize(1);
        assertThat(plainTarget.requestsFor("/plain")).isEmpty();
    }
}
