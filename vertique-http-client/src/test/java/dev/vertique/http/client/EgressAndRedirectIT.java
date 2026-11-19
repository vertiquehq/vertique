// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.http.client.HttpClientItSupport.RecordedRequest;
import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpEgressDeniedException.Reason;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.uritemplate.UriTemplate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests of the egress allowlist and the redirect rules: every hop is checked before a
 * connection opens, and a followed redirect forwards only safelisted headers.
 *
 * <p>Every test dials only loopback addresses. {@code 10.0.0.5} appears as a denied target and is
 * never dialed; admitted non-local hosts are judged by {@link EgressPolicy#check} in {@link
 * EgressPolicyTest}, never by a request.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class EgressAndRedirectIT {

    /** A refusal must come before the 10 s connect timeout below could matter. */
    private static final long FAST_REFUSAL_MS = 500;

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

    /** A client with the given policy and options; a null argument leaves that setting at its default. */
    private HttpClient client(EgressPolicy policy, WebClientOptions options) {
        HttpClientBuilder builder = HttpClientBuilder.create(support.vertx);
        support.track(builder::close);
        if (options != null) {
            builder.webClientOptions(options);
        }
        if (policy != null) {
            builder.egress(policy);
        }
        HttpClient client = builder.build();
        support.track(client::close);
        return client;
    }

    private static WebClientOptions followingUpTo(int maxRedirects) {
        return new WebClientOptions().setFollowRedirects(true).setMaxRedirects(maxRedirects);
    }

    private static void answer(HttpServerRequest request, int status, String body) {
        request.response().setStatusCode(status).end(body);
    }

    private static void redirect(HttpServerRequest request, int status, String location) {
        request.response().setStatusCode(status).putHeader("Location", location).end();
    }

    private static void assertDenied(Throwable failure, Reason reason) {
        assertThat(failure).isInstanceOfSatisfying(HttpEgressDeniedException.class, e -> assertThat(e.reason())
                .isEqualTo(reason));
    }

    private static String localhostOrigin(RecordingServer server) {
        return "http://localhost:" + server.port();
    }

    // --- proofs ---

    /** AC-056. */
    @Test
    @DisplayName("an allowlist admits exact and suffix hosts and denies others before connecting")
    void allowlistAdmitsExactAndSuffixHostsAndDeniesOthersBeforeConnecting() {
        // Given a policy for the loopback literal and a suffix, and a loopback server counting connections
        EgressPolicy policy = EgressPolicy.allowHosts(List.of("127.0.0.1", "*.example.com"), false);
        RecordingServer server = support.server((request, body) -> answer(request, 200, "ok"));
        WebClient web = client(policy, null).webClient();

        // When check runs for admitted and denied targets
        // Then the loopback target and the admitted non-local hosts pass, asserted through check only
        policy.check(java.net.URI.create(server.origin() + "/"));
        policy.check(java.net.URI.create("https://api.example.com/"));
        policy.check(java.net.URI.create("https://a.b.example.com/"));

        // And each denied target is refused with its reason
        String[][] denied = {
            {"https://example.com/", "HOST"},
            {"https://EXAMPLE.COM./", "HOST"},
            {"https://xexample.com/", "HOST"},
            {"https://evil.test/", "HOST"},
            {"http://api.example.com/", "SCHEME"},
            {"https://user:pw@api.example.com/", "USER_INFO"},
            {"https:///path", "NO_HOST"},
        };
        for (String[] target : denied) {
            try {
                policy.check(java.net.URI.create(target[0]));
                throw new AssertionError("check must deny " + target[0]);
            } catch (HttpEgressDeniedException e) {
                assertThat(e.reason()).as(target[0]).isEqualTo(Reason.valueOf(target[1]));
            }
        }

        // And each denied target sent through the client fails with its reason, fast, with no connection
        String[][] sent = {
            {"https://example.com/", "HOST"},
            {"https://EXAMPLE.COM./", "HOST"},
            {"https://xexample.com/", "HOST"},
            {"https://evil.test/", "HOST"},
            {"http://api.example.com/", "SCHEME"},
        };
        for (String[] target : sent) {
            long started = System.nanoTime();
            Throwable failure = awaitFailure(web.getAbs(target[0]).send());
            assertDenied(failure, Reason.valueOf(target[1]));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .as("refusal time for %s", target[0])
                    .isLessThan(FAST_REFUSAL_MS);
        }
        // And a template target with user information is refused before connecting (loopback keeps it local)
        Throwable userInfo = awaitFailure(
                web.requestAbs(HttpMethod.GET, UriTemplate.of("http://user:pw@127.0.0.1:" + server.port() + "/"))
                        .send());
        assertDenied(userInfo, Reason.USER_INFO);
        assertThat(server.openedConnections.get())
                .as("connections before the loopback request")
                .isZero();

        // And the loopback request succeeds
        HttpResponse<Buffer> ok = await(web.getAbs(server.origin() + "/").send());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(server.openedConnections.get()).isEqualTo(1);

        // And a String URI with user information reaches the server without an Authorization header
        HttpResponse<Buffer> stripped = await(web.getAbs("http://user:pw@127.0.0.1:" + server.port() + "/userinfo")
                .send());
        assertThat(stripped.statusCode()).isEqualTo(200);
        RecordedRequest seen = server.requestsTo("/userinfo").get(0);
        assertThat(seen.headers().get("Authorization")).isNull();

        // And a request whose host option smuggles URI syntax is refused for its host before any dial
        WebClient allowedOnly = client(EgressPolicy.allowHosts(List.of("allowed.test"), false), null)
                .webClient();
        List<String> notRefused = new java.util.ArrayList<>();
        java.util.Map<String, Reason> smuggledHosts = new java.util.LinkedHashMap<>();
        smuggledHosts.put("allowed.test#.evil.test", Reason.HOST);
        smuggledHosts.put("allowed.test/x", Reason.HOST);
        smuggledHosts.put("allowed.test?x", Reason.HOST);
        smuggledHosts.put("allowed.test@evil.test", Reason.USER_INFO);
        smuggledHosts.put("allowed .test", Reason.HOST);
        for (java.util.Map.Entry<String, Reason> entry : smuggledHosts.entrySet()) {
            String host = entry.getKey();
            long smuggleStarted = System.nanoTime();
            Future<HttpResponse<Buffer>> smuggleSent;
            try {
                smuggleSent = allowedOnly
                        .request(new RequestOptions()
                                .setMethod(HttpMethod.GET)
                                .setHost(host)
                                .setPort(443)
                                .setSsl(true)
                                .setURI("/"))
                        .send();
            } catch (RuntimeException thrown) {
                smuggleSent = Future.failedFuture(thrown);
            }
            Throwable smuggled = awaitFailure(smuggleSent);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - smuggleStarted);
            if (!(smuggled instanceof HttpEgressDeniedException denial) || denial.reason() != entry.getValue()) {
                notRefused.add(host + " -> " + smuggled);
            } else if (elapsedMs >= FAST_REFUSAL_MS) {
                notRefused.add(host + " -> refused after " + elapsedMs + " ms");
            }
        }
        assertThat(notRefused)
                .as("hosts not refused with its reason within 500 ms")
                .isEmpty();

        // And a redirect Location with user information fails the hop with USER_INFO before connecting
        RecordingServer target = support.server((request, body) -> answer(request, 200, "target"));
        RecordingServer origin = support.server(
                (request, body) -> redirect(request, 302, "http://user:pw@127.0.0.1:" + target.port() + "/landing"));
        WebClient following = client(policy, followingUpTo(5)).webClient();
        Throwable hop = awaitFailure(following.getAbs(origin.origin() + "/go").send());
        assertDenied(hop, Reason.USER_INFO);
        assertThat(target.openedConnections.get()).isZero();
        assertThat(target.requests).isEmpty();
    }

    /** AC-057. */
    @Test
    @DisplayName("a redirect to a denied host fails without connecting")
    void redirectToADeniedHostFailsWithoutConnecting() {
        // Given a policy for 127.0.0.1 only, redirects followed up to 5, server A redirecting to localhost
        // and server B counting connections
        RecordingServer b = support.server((request, body) -> answer(request, 200, "b"));
        RecordingServer a = support.server((request, body) -> redirect(request, 302, localhostOrigin(b) + "/"));
        WebClient web = client(EgressPolicy.allowHosts(List.of("127.0.0.1"), true), followingUpTo(5))
                .webClient();

        // When the caller sends GET /out to A
        Throwable failure = awaitFailure(web.getAbs(a.origin() + "/out").send());

        // Then the future fails with HOST
        assertDenied(failure, Reason.HOST);

        // And server B accepted no connection
        assertThat(a.requestsTo("/out")).hasSize(1);
        assertThat(b.openedConnections.get()).isZero();
        assertThat(b.requests).isEmpty();
    }

    /** AC-056: a redirect Location the client cannot read is refused with a constant failure. */
    @Test
    @DisplayName("a redirect with an unreadable Location is refused with HOST and no Location text")
    void redirectWithAnUnreadableLocationIsRefusedWithHostAndNoLocationText() {
        // Given a server redirecting to a Location with a space and a secret query
        RecordingServer origin = support.server((request, body) -> redirect(request, 302, "http://h/a b?sig=SECRET"));
        WebClient web = client(EgressPolicy.allowAll(), followingUpTo(5)).webClient();

        // When the caller sends GET /go
        Throwable failure = awaitFailure(web.getAbs(origin.origin() + "/go").send());

        // Then the failure is HOST and no message in the cause chain carries the Location text
        assertDenied(failure, Reason.HOST);
        for (Throwable link = failure; link != null; link = link.getCause()) {
            assertThat(String.valueOf(link.getMessage())).doesNotContain("sig=");
        }
    }

    /** AC-056: userinfo is refused even when its separator is percent-encoded. */
    @Test
    @DisplayName("a redirect whose authority carries an encoded @ is refused with USER_INFO")
    void redirectWhoseAuthorityCarriesAnEncodedAtIsRefusedWithUserInfo() {
        // Given a target and a server redirecting to http://a%40127.0.0.1:<port>/x
        RecordingServer target = support.server((request, body) -> answer(request, 200, "target"));
        RecordingServer origin = support.server(
                (request, body) -> redirect(request, 302, "http://a%40127.0.0.1:" + target.port() + "/x"));
        WebClient web = client(EgressPolicy.allowAll(), followingUpTo(5)).webClient();

        // When the caller sends GET /go
        Throwable failure = awaitFailure(web.getAbs(origin.origin() + "/go").send());

        // Then the hop is refused with USER_INFO and the target is never contacted
        assertDenied(failure, Reason.USER_INFO);
        assertThat(target.openedConnections.get()).isZero();
    }

    /** AC-056: a Location whose host cannot be read is refused with HOST. */
    @Test
    @DisplayName("a redirect whose host cannot be read is refused with HOST")
    void redirectWhoseHostCannotBeReadIsRefusedWithHost() {
        // Given a target and a server redirecting to a host the JDK reads as a registry-based authority
        RecordingServer target = support.server((request, body) -> answer(request, 200, "target"));
        RecordingServer origin =
                support.server((request, body) -> redirect(request, 302, "http://bad_host:" + target.port() + "/"));
        WebClient web = client(EgressPolicy.allowAll(), followingUpTo(5)).webClient();

        // When the caller sends GET /go
        Throwable failure = awaitFailure(web.getAbs(origin.origin() + "/go").send());

        // Then the hop is refused with HOST
        assertDenied(failure, Reason.HOST);
        assertThat(target.openedConnections.get()).isZero();
    }

    /** AC-059. */
    @Test
    @DisplayName("a default client returns the 3xx when redirects are off, and a followed chain stops at the limit")
    void defaultClientReturnsTheThreeXxWhenRedirectsAreOff() {
        // Given a client built with defaults and a server whose GET /old answers 302 to /new
        RecordingServer server = support.server((request, body) -> {
            String uri = request.uri();
            if (uri.equals("/old")) {
                redirect(request, 302, "/new");
            } else if (uri.startsWith("/hop/")) {
                int n = Integer.parseInt(uri.substring("/hop/".length()));
                redirect(request, 302, "/hop/" + (n + 1));
            } else {
                answer(request, 200, "new");
            }
        });
        WebClient web = client(null, null).webClient();

        // When the caller sends GET /old
        HttpResponse<Buffer> old = await(web.getAbs(server.origin() + "/old").send());

        // Then the response is the 302 with Location /new
        assertThat(old.statusCode()).isEqualTo(302);
        assertThat(old.getHeader("Location")).isEqualTo("/new");

        // And the server received no request for /new
        assertThat(server.requestsTo("/new")).isEmpty();

        // And a client that follows up to 3 redirects against an endless chain receives exactly 4
        // requests and returns the 302 for /hop/4
        WebClient following = client(null, followingUpTo(3)).webClient();
        HttpResponse<Buffer> last =
                await(following.getAbs(server.origin() + "/hop/1").send());
        assertThat(last.statusCode()).isEqualTo(302);
        assertThat(last.getHeader("Location")).isEqualTo("/hop/5");
        List<String> hops = server.requests.stream()
                .map(RecordedRequest::uri)
                .filter(uri -> uri.startsWith("/hop/"))
                .toList();
        assertThat(hops).containsExactly("/hop/1", "/hop/2", "/hop/3", "/hop/4");
    }

    /** AC-060. */
    @Test
    @DisplayName("a cross-origin hop forwards only safelisted headers and Vert.x's method handling is kept")
    void crossOriginHopForwardsOnlySafelistedHeaders() {
        // Given redirects followed up to 5, server A with the redirecting routes and server B recording
        RecordingServer b = support.server((request, body) -> answer(request, 200, "b"));
        String landingOnB = localhostOrigin(b) + "/landing";
        RecordingServer a = support.server((request, body) -> {
            String route = request.method().name() + " " + request.path();
            switch (route) {
                case "GET /start" -> redirect(request, 302, landingOnB);
                case "GET /same" -> redirect(request, 302, "/landing");
                case "POST /post" -> redirect(request, 307, "/landing");
                case "POST /see" -> redirect(request, 303, "/landing");
                case "QUERY /x307" -> redirect(request, 307, landingOnB);
                case "QUERY /x308" -> redirect(request, 308, landingOnB);
                default -> answer(request, 200, "a");
            }
        });
        WebClient web = client(null, followingUpTo(5)).webClient();
        Buffer abc = Buffer.buffer("abc");

        // When the caller sends GET /start and GET /same with custom and safelisted headers
        HttpResponse<Buffer> start = await(web.getAbs(a.origin() + "/start")
                .putHeader("Accept", "text/plain")
                .putHeader("User-Agent", "ua-1")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("X-Api-Key", "k1")
                .putHeader("X-Custom", "c1")
                .send());
        HttpResponse<Buffer> same = await(web.getAbs(a.origin() + "/same")
                .putHeader("Accept", "text/plain")
                .putHeader("User-Agent", "ua-1")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("X-Api-Key", "k1")
                .putHeader("X-Custom", "c1")
                .send());

        // Then the cross-origin hop reached B with the safelisted headers and without the custom ones
        assertThat(start.statusCode()).isEqualTo(200);
        assertThat(b.requestsTo("/landing")).hasSize(1);
        RecordedRequest onB = b.requestsTo("/landing").get(0);
        assertThat(onB.headers().get("Accept")).isEqualTo("text/plain");
        assertThat(onB.headers().get("User-Agent")).isEqualTo("ua-1");
        assertThat(onB.headers().get("Cache-Control")).isEqualTo("no-cache");
        assertThat(onB.headers().get("X-Api-Key")).isNull();
        assertThat(onB.headers().get("X-Custom")).isNull();

        // And the same-origin hop reached A with the custom headers unchanged
        assertThat(same.statusCode()).isEqualTo(200);
        RecordedRequest sameHop = a.requestsTo("/landing").get(0);
        assertThat(sameHop.headers().get("X-Api-Key")).isEqualTo("k1");
        assertThat(sameHop.headers().get("X-Custom")).isEqualTo("c1");

        // And a POST answered 307 returns the 307 as the response, without a second request
        HttpResponse<Buffer> post = await(web.postAbs(a.origin() + "/post").sendBuffer(abc));
        assertThat(post.statusCode()).isEqualTo(307);
        assertThat(a.requestsTo("/landing")).hasSize(1);

        // And a POST answered 303 is followed as a GET with no body
        HttpResponse<Buffer> see = await(web.postAbs(a.origin() + "/see").sendBuffer(abc));
        assertThat(see.statusCode()).isEqualTo(200);
        assertThat(a.requestsTo("/landing")).hasSize(2);
        RecordedRequest seeHop = a.requestsTo("/landing").get(1);
        assertThat(seeHop.method()).isEqualTo("GET");
        assertThat(seeHop.body().length()).isZero();

        // And a QUERY with a body answered 307 or 308 to another origin returns that 3xx as the response
        HttpMethod query = HttpMethod.valueOf("QUERY");
        HttpResponse<Buffer> x307 =
                await(web.requestAbs(query, a.origin() + "/x307").sendBuffer(abc));
        HttpResponse<Buffer> x308 =
                await(web.requestAbs(query, a.origin() + "/x308").sendBuffer(abc));
        assertThat(x307.statusCode()).isEqualTo(307);
        assertThat(x308.statusCode()).isEqualTo(308);

        // And server B received no request for them (only the one from GET /start)
        assertThat(b.requests).hasSize(1);
    }

    /** AC-061. */
    @Test
    @DisplayName("a hop from https to http is refused and a hop from http to https is followed")
    void downgradeHopIsRefused() {
        // Given redirects followed up to 5, allowAll, an HTTPS server A trusted by the client's options
        // that redirects to a plain server B counting requests
        RecordingServer plain = support.server((request, body) -> {
            if (request.path().equals("/up")) {
                redirect(request, 302, "https://127.0.0.1:" + request.getHeader("X-Tls-Port") + "/secure");
            } else {
                answer(request, 200, "plain");
            }
        });
        RecordingServer tls = support.tlsServer((request, body) -> {
            if (request.path().equals("/down")) {
                redirect(request, 302, plain.origin() + "/plain");
            } else {
                answer(request, 200, "secure");
            }
        });
        WebClientOptions options = followingUpTo(5).setTrustAll(true).setVerifyHost(false);
        WebClient web = client(EgressPolicy.allowAll(), options).webClient();

        // When the caller sends GET /down to A
        Throwable failure = awaitFailure(web.getAbs(tls.origin() + "/down").send());

        // Then the future fails with SCHEME
        assertDenied(failure, Reason.SCHEME);

        // And server B received no request
        assertThat(plain.requests).isEmpty();
        assertThat(plain.openedConnections.get()).isZero();

        // And a hop from plain HTTP to HTTPS is followed
        HttpResponse<Buffer> up = await(web.getAbs(plain.origin() + "/up")
                .putHeader("X-Tls-Port", String.valueOf(tls.port()))
                .send());
        assertThat(up.statusCode()).isEqualTo(200);
        assertThat(up.bodyAsString()).isEqualTo("secure");
    }

    /** AC-077. */
    @Test
    @DisplayName("a server address, a virtual host and a proxy cannot bypass egress")
    void serverAddressVirtualHostAndProxyCannotBypassEgress() {
        // Given a policy for 127.0.0.1, a 10 s connect timeout, a counting loopback server and a counting
        // loopback listener that stands for the proxy
        AtomicInteger proxyConnections = new AtomicInteger();
        NetServer proxy = support.vertx.createNetServer().connectHandler(socket -> {
            proxyConnections.incrementAndGet();
            socket.close();
        });
        await(proxy.listen(0, "127.0.0.1"));
        support.track(proxy::close);
        RecordingServer server = support.server((request, body) -> answer(request, 200, "ok"));
        String url = server.origin() + "/";
        WebClientOptions options = new WebClientOptions().setConnectTimeout(10_000);
        WebClient web = client(EgressPolicy.allowHosts(List.of("127.0.0.1"), true), options)
                .webClient();
        await(web.getAbs(url).send()); // warms the client up so the timings below measure the refusal only
        int connectionsBefore = server.openedConnections.get();
        int requestsBefore = server.requests.size();

        // When the caller sends the five bypass attempts, in order
        List<Future<HttpResponse<Buffer>>> attempts = List.of(
                web.requestAbs(HttpMethod.GET, SocketAddress.domainSocketAddress("/tmp/vertique-test.sock"), url)
                        .send(),
                web.requestAbs(HttpMethod.GET, SocketAddress.inetSocketAddress(80, "10.0.0.5"), url)
                        .send(),
                web.getAbs(url).virtualHost("evil.test").send(),
                web.getAbs(url).putHeader("Host", "evil.test").send(),
                web.getAbs(url)
                        .proxy(new ProxyOptions().setHost("10.0.0.5").setPort(3128))
                        .send());
        List<Reason> expected = List.of(
                Reason.CONNECT_ADDRESS, Reason.CONNECT_ADDRESS, Reason.VIRTUAL_HOST, Reason.VIRTUAL_HOST, Reason.PROXY);

        // Then each fails with its reason within 500 ms although the connect timeout is 10000 ms
        long started = System.nanoTime();
        for (int i = 0; i < attempts.size(); i++) {
            assertDenied(awaitFailure(attempts.get(i)), expected.get(i));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .as("time until attempt %d failed", i)
                    .isLessThan(FAST_REFUSAL_MS);
        }

        // And neither the server nor the proxy listener accepted a connection for them
        assertThat(server.openedConnections.get()).isEqualTo(connectionsBefore);
        assertThat(server.requests).hasSize(requestsBefore);
        assertThat(proxyConnections.get()).isZero();

        // And a server address whose literal host passes the rules succeeds
        HttpResponse<Buffer> admitted =
                await(web.requestAbs(HttpMethod.GET, SocketAddress.inetSocketAddress(server.port(), "127.0.0.1"), url)
                        .send());
        assertThat(admitted.statusCode()).isEqualTo(200);

        // And allowAll still refuses a domain socket within 500 ms without a connection
        RecordingServer untouched = support.server((request, body) -> answer(request, 200, "ok"));
        WebClient open = client(EgressPolicy.allowAll(), options).webClient();
        long openStarted = System.nanoTime();
        Throwable domain = awaitFailure(open.requestAbs(
                        HttpMethod.GET,
                        SocketAddress.domainSocketAddress("/tmp/vertique-test.sock"),
                        untouched.origin() + "/")
                .send());
        assertDenied(domain, Reason.CONNECT_ADDRESS);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - openStarted))
                .isLessThan(FAST_REFUSAL_MS);
        assertThat(untouched.openedConnections.get()).isZero();

        // And with plain HTTP refused, a non-loopback server address behind a loopback URL host is refused as SCHEME
        // within 500 ms: loopback is judged from the connect address, and nothing is dialed
        RecordingServer plain = support.server((request, body) -> answer(request, 200, "ok"));
        WebClient strict = client(EgressPolicy.allowHosts(List.of("127.0.0.1", "10.0.0.5"), false), options)
                .webClient();
        long strictStarted = System.nanoTime();
        Throwable scheme = awaitFailure(
                strict.requestAbs(HttpMethod.GET, SocketAddress.inetSocketAddress(80, "10.0.0.5"), plain.origin() + "/")
                        .send());
        assertDenied(scheme, Reason.SCHEME);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - strictStarted))
                .isLessThan(FAST_REFUSAL_MS);
        assertThat(plain.openedConnections.get()).isZero();

        // And a loopback connect address behind a non-loopback URL host is allowed over plain HTTP and reaches the
        // loopback server
        WebClient named = client(EgressPolicy.allowHosts(List.of("127.0.0.1", "example.com"), false), options)
                .webClient();
        HttpResponse<Buffer> viaLoopback = await(named.requestAbs(
                        HttpMethod.GET,
                        SocketAddress.inetSocketAddress(plain.port(), "127.0.0.1"),
                        "http://example.com/")
                .send());
        assertThat(viaLoopback.statusCode()).isEqualTo(200);
        assertThat(plain.requests).hasSize(1);
    }

    /** Supporting proof of per-hop checks. */
    @Test
    @DisplayName("every hop is re-checked, and a hop to a denied host is refused before it is dialed")
    void everyHopIsRecheckedAndAnInterceptorChangedUriIsChecked() {
        // Given a policy for 127.0.0.1 and localhost, a chain A (127.0.0.1) -> B (localhost) -> 10.0.0.5
        // (a denied target that is never dialed), redirects followed up to 5 and a 10 s connect timeout
        RecordingServer b = support.server((request, body) -> redirect(request, 302, "http://10.0.0.5:3128/three"));
        RecordingServer a = support.server((request, body) -> redirect(request, 302, localhostOrigin(b) + "/two"));
        WebClientOptions options = followingUpTo(5).setConnectTimeout(10_000);
        WebClient web = client(EgressPolicy.allowHosts(List.of("127.0.0.1", "localhost"), true), options)
                .webClient();

        // When the caller sends GET /one to A
        long started = System.nanoTime();
        Throwable failure = awaitFailure(web.getAbs(a.origin() + "/one").send());

        // Then the first two hops were admitted and sent
        assertThat(a.requestsTo("/one")).hasSize(1);
        assertThat(b.requestsTo("/two")).hasSize(1);

        // And the third hop is refused with HOST without waiting for a connect
        assertDenied(failure, Reason.HOST);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000);
    }
}
