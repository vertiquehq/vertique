// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitCondition;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.exception.HttpClientClosedException;
import dev.vertique.http.client.exception.HttpCredentialException;
import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpExchangeCancelledException;
import dev.vertique.http.client.exception.HttpStatusRejectedException;
import dev.vertique.http.client.interceptor.HttpClientAttemptCompletion;
import dev.vertique.http.client.interceptor.HttpClientInterceptor;
import dev.vertique.http.client.interceptor.HttpClientRequestContext;
import dev.vertique.http.client.interceptor.HttpClientResponseHead;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResiliencePolicyOverrides;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.RetryBackoff;
import dev.vertique.resilience.RetryOverride;
import dev.vertique.resilience.config.CircuitBreakerPolicyConfig;
import dev.vertique.resilience.config.ResiliencePolicyConfig;
import dev.vertique.resilience.config.RetryPolicyConfig;
import dev.vertique.resilience.config.TimeoutPolicyConfig;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import dev.vertique.resilience.spi.event.AttemptCompleted;
import dev.vertique.resilience.spi.event.CircuitState;
import dev.vertique.resilience.spi.event.CircuitStateChanged;
import dev.vertique.resilience.spi.event.ExecutionCompleted;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.ResilienceOutcomeCategory;
import dev.vertique.resilience.spi.event.RetryExhausted;
import dev.vertique.resilience.spi.event.RetryScheduled;
import dev.vertique.resilience.spi.event.TimeoutTriggered;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClosedException;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * Integration tests of the client's resilience composition: one pipeline execution per call, the
 * client's own attempt timer reported as a resilience timeout inside a tier, cancellation and
 * {@code Retry-After}, the default retry classification, the breaker accounting and the observation
 * of every retried exchange.
 *
 * <p>Tiers are named policies handed to a client through a package-private registry seam on the
 * builder, next to the {@link Resilience} runtime passed to {@code create(vertx, resilience)}. Every
 * test runs against real Vert.x servers on {@code 127.0.0.1} and dynamic ports; elapsed bounds carry
 * generous margins over the configured delays.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ClientResilienceIT {

    /** Settle time after the last expected notification, to show that no further one arrives. */
    private static final long SETTLE_MS = 300;

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

    // --- fixtures: tiers ---

    /** A named tier built the way {@code resilience.policies.<name>} is bound; null leaves a concern out. */
    private static ResiliencePolicyOverrides tier(
            Integer maxRetries,
            long delayMs,
            Long retryAfterMaxMs,
            Long timeoutMs,
            Integer maxFailures,
            Long resetTimeoutMs) {
        return new ResiliencePolicyConfig(
                        "tier",
                        timeoutMs == null ? null : new TimeoutPolicyConfig(timeoutMs),
                        maxRetries == null
                                ? null
                                : new RetryPolicyConfig(maxRetries, delayMs, 1.0d, null, retryAfterMaxMs),
                        maxFailures == null ? null : new CircuitBreakerPolicyConfig(maxFailures, resetTimeoutMs),
                        null)
                .toOverrides();
    }

    private static ResiliencePolicyOverrides retryTier(int maxRetries, long delayMs) {
        return tier(maxRetries, delayMs, null, null, null, null);
    }

    private static ResiliencePolicyOverrides breakerTier(int maxFailures, long resetTimeoutMs) {
        return tier(null, 0, null, null, maxFailures, resetTimeoutMs);
    }

    private static ResiliencePolicyOverrides withRetryOn(
            ResiliencePolicyOverrides base, Set<Class<? extends Throwable>> retryOn) {
        RetryOverride r = base.retry().orElseThrow();
        return new ResiliencePolicyOverrides(
                base.timeout(),
                Optional.of(new RetryOverride(
                        r.enabled(),
                        r.maxRetries(),
                        r.backoff(),
                        Optional.of(retryOn),
                        r.abortOn(),
                        r.fallbackPolicy(),
                        r.retryAfterMaxMs())),
                base.circuitBreaker(),
                base.bulkhead());
    }

    private static ResiliencePolicyOverrides withAbortOn(
            ResiliencePolicyOverrides base, Set<Class<? extends Throwable>> abortOn) {
        RetryOverride r = base.retry().orElseThrow();
        return new ResiliencePolicyOverrides(
                base.timeout(),
                Optional.of(new RetryOverride(
                        r.enabled(),
                        r.maxRetries(),
                        r.backoff(),
                        r.retryOn(),
                        Optional.of(abortOn),
                        r.fallbackPolicy(),
                        r.retryAfterMaxMs())),
                base.circuitBreaker(),
                base.bulkhead());
    }

    private static Map<String, ResiliencePolicyOverrides> tiers(Object... namesAndTiers) {
        Map<String, ResiliencePolicyOverrides> map = new HashMap<>();
        for (int i = 0; i < namesAndTiers.length; i += 2) {
            map.put((String) namesAndTiers[i], (ResiliencePolicyOverrides) namesAndTiers[i + 1]);
        }
        return map;
    }

    // --- fixtures: runtime, clients, servers ---

    /** A runtime that records every resilience event into {@code events}. */
    private Resilience runtime(List<ResilienceEvent> events) {
        Resilience resilience = Resilience.create(support.vertx, Set.of(events::add));
        support.track(resilience::close);
        return resilience;
    }

    private VertiqueHttpClientBuilder builder(
            Resilience runtime, RecordingServer server, Map<String, ResiliencePolicyOverrides> tiers) {
        ResiliencePolicyRegistry registry = name -> {
            ResiliencePolicyOverrides overrides = tiers.get(name);
            if (overrides == null) {
                throw new IllegalArgumentException("resilience.policies." + name + " is not defined");
            }
            return overrides;
        };
        VertiqueHttpClientBuilder builder = VertiqueHttpClientBuilder.create(support.vertx, runtime)
                .baseUrl(server.origin())
                .policyRegistry(registry);
        support.track(builder::close);
        return builder;
    }

    private VertiqueHttpClient build(VertiqueHttpClientBuilder builder) {
        VertiqueHttpClient client = builder.build();
        support.track(client::close);
        return client;
    }

    private VertiqueHttpClient client(
            Resilience runtime,
            RecordingServer server,
            Map<String, ResiliencePolicyOverrides> tiers,
            HttpClientInterceptor... interceptors) {
        VertiqueHttpClientBuilder builder = builder(runtime, server, tiers);
        for (HttpClientInterceptor interceptor : interceptors) {
            builder.register(interceptor);
        }
        return build(builder);
    }

    private static CallOptions tierOptions(String tier) {
        return CallOptions.defaults().resiliencePolicy(tier);
    }

    private static Future<HttpResponse<Buffer>> get(VertiqueHttpClient client, CallOptions options, String path) {
        return client.execute(options, web -> web.get(path).send().expecting(HttpExpectations.success()));
    }

    /** Records every completed exchange with the head and completion the interceptor saw. */
    static final class Completions implements HttpClientInterceptor {

        record Completed(HttpClientResponseHead response, HttpClientAttemptCompletion completion) {}

        final List<Completed> completed = new CopyOnWriteArrayList<>();

        @Override
        public void onAttemptCompleted(
                HttpClientRequestContext request,
                HttpClientResponseHead response,
                HttpClientAttemptCompletion completion) {
            completed.add(new Completed(response, completion));
        }

        List<Completed> awaitCount(int count) {
            assertThat(awaitCondition(() -> completed.size() >= count, HttpClientItSupport.WAIT_MS))
                    .as("completions reached %d, saw %d", count, completed.size())
                    .isTrue();
            return List.copyOf(completed);
        }
    }

    private static long count(RecordingServer server, String target) {
        return server.requests.stream().filter(r -> r.target().equals(target)).count();
    }

    private static <E> List<E> of(List<ResilienceEvent> events, Class<E> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<ResilienceEvent> since(List<ResilienceEvent> events, int mark) {
        return new ArrayList<>(events).subList(mark, events.size());
    }

    private static void awaitExecutionsCompleted(List<ResilienceEvent> events, int count) {
        assertThat(awaitCondition(
                        () -> of(events, ExecutionCompleted.class).size() >= count, HttpClientItSupport.WAIT_MS))
                .as("ExecutionCompleted events")
                .isTrue();
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    private static boolean serverReceived(RecordingServer server, int total) {
        return awaitCondition(() -> server.requests.size() >= total, HttpClientItSupport.WAIT_MS);
    }

    private static void assertRejected(Throwable failure, int status) {
        assertThat(failure).isExactlyInstanceOf(HttpStatusRejectedException.class);
        assertThat(((HttpStatusRejectedException) failure).statusCode()).isEqualTo(status);
    }

    /** A server answering one fixed status for every request. */
    private RecordingServer statusServer(int status) {
        return support.server(
                (request, body) -> request.response().setStatusCode(status).end());
    }

    // --- AC-017 ---

    /** AC-017: cancelling during the backoff fails the call at once and no further attempt starts. */
    @Test
    @DisplayName("cancelling during the backoff fails the call at once and starts no attempt")
    void cancelDuringBackoffFailsTheCallAtOnceAndStartsNoAttempt() {
        // Given a tier with maxRetries 2 and a 5000 ms backoff, a server answering 503 and a signal
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        RecordingServer server = statusServer(503);
        VertiqueHttpClient client = client(runtime(events), server, tiers("t", retryTier(2, 5000)));
        CancellationSignal signal = CancellationSignal.create();

        // When the call runs and the signal is cancelled 200 ms after the first response, while it waits
        Future<HttpResponse<Buffer>> call = get(client, tierOptions("t").cancellation(signal), "/down");
        assertThat(serverReceived(server, 1)).isTrue();
        sleep(200);
        long cancelledAt = System.nanoTime();
        signal.cancel();

        // Then the future fails with HttpExchangeCancelledException long before the 5000 ms backoff
        Throwable failure = awaitFailure(call);
        assertThat(millisSince(cancelledAt)).isLessThan(1_500);
        assertThat(failure).isExactlyInstanceOf(HttpExchangeCancelledException.class);

        // And the server received exactly 1 request and no attempt starts during the following 6000 ms
        sleep(6_000);
        assertThat(server.requests).hasSize(1);
    }

    // --- AC-022 ---

    /** AC-022: a credential source that fails or names an invalid header sends nothing and is not retried. */
    @Test
    @DisplayName("a failing credential source sends nothing and is not retried")
    void failingCredentialSourceSendsNothingAndIsNotRetried() {
        // Given a retry tier of maxRetries 3, a failing source and one naming Content-Length, and a server
        RecordingServer server = statusServer(200);
        Resilience runtime = runtime(new CopyOnWriteArrayList<>());
        AtomicInteger failingResolutions = new AtomicInteger();
        AtomicInteger invalidResolutions = new AtomicInteger();
        VertiqueHttpClient failing =
                build(builder(runtime, server, tiers("t", retryTier(3, 10))).credentials(target -> {
                    failingResolutions.incrementAndGet();
                    return Future.failedFuture(new IllegalStateException("vault down"));
                }));
        VertiqueHttpClient invalid =
                build(builder(runtime, server, tiers("t", retryTier(3, 10))).credentials(target -> {
                    invalidResolutions.incrementAndGet();
                    return Future.succeededFuture(Map.of("Content-Length", "5"));
                }));

        // When the caller runs each client's call in the tier
        Throwable failingFailure = awaitFailure(
                failing.execute(tierOptions("t"), web -> web.get("/").send()));
        Throwable invalidFailure = awaitFailure(
                invalid.execute(tierOptions("t"), web -> web.get("/").send()));
        sleep(SETTLE_MS);

        // Then each fails with HttpCredentialException after exactly 1 resolution, so it is not retried
        assertThat(failingFailure).isExactlyInstanceOf(HttpCredentialException.class);
        assertThat(invalidFailure).isExactlyInstanceOf(HttpCredentialException.class);
        assertThat(failingResolutions.get()).isEqualTo(1);
        assertThat(invalidResolutions.get()).isEqualTo(1);

        // And the server received 0 requests
        assertThat(server.requests).isEmpty();

        // And the cause is null, the cause type is named and "vault down" appears nowhere
        assertThat(failingFailure.getCause()).isNull();
        assertThat(((HttpCredentialException) failingFailure).causeType())
                .isEqualTo(Optional.of("IllegalStateException"));
        assertThat(String.valueOf(failingFailure.getMessage())).doesNotContain("vault down");
        assertThat(failingFailure.toString()).doesNotContain("vault down");
    }

    // --- AC-031 ---

    /** AC-031: a retry recovers a 503 and each exchange is observed with its own ordinal. */
    @Test
    @DisplayName("a retry recovers a 503 and each attempt is observed")
    void retryRecoversA503AndEachAttemptIsObserved() {
        // Given a retry tier, a recording interceptor and a server answering 503 and then 200 "ok"
        AtomicInteger seen = new AtomicInteger();
        RecordingServer server = support.server((request, body) -> {
            if (seen.incrementAndGet() == 1) {
                request.response().setStatusCode(503).end();
            } else {
                request.response().end("ok");
            }
        });
        Completions interceptor = new Completions();
        VertiqueHttpClient client =
                client(runtime(new CopyOnWriteArrayList<>()), server, tiers("t", retryTier(2, 10)), interceptor);

        // When the caller runs GET /flaky mapped to its body
        Future<String> call = client.execute(tierOptions("t"), web -> web.get("/flaky")
                .send()
                .expecting(HttpExpectations.success())
                .map(HttpResponse::bodyAsString));

        // Then the future succeeds with "ok" and the server received exactly 2 requests
        assertThat(await(call)).isEqualTo("ok");
        assertThat(server.requests).hasSize(2);

        // And onAttemptCompleted ran twice with one call id, ordinals 1 and 2
        List<Completions.Completed> done = interceptor.awaitCount(2);
        sleep(SETTLE_MS);
        assertThat(interceptor.completed).hasSize(2);
        Completions.Completed first = done.get(0);
        Completions.Completed second = done.get(1);
        assertThat(first.completion().callId()).isEqualTo(second.completion().callId());
        assertThat(first.completion().attemptOrdinal()).isEqualTo(1);
        assertThat(first.completion().statusCode()).isEqualTo(503);
        assertThat(first.response()).isNotNull();
        assertThat(first.response().statusCode()).isEqualTo(503);
        assertRejected(first.completion().error(), 503);
        assertThat(second.completion().attemptOrdinal()).isEqualTo(2);
        assertThat(second.completion().statusCode()).isEqualTo(200);
        assertThat(second.response()).isNotNull();
        assertThat(second.response().statusCode()).isEqualTo(200);
        assertThat(second.completion().error()).isNull();
    }

    // --- AC-032 ---

    /** AC-032: an open circuit rejects without sending and a 404 does not count as a breaker failure. */
    @Test
    @DisplayName("an open circuit rejects without sending")
    void openCircuitRejectsWithoutSending() {
        // Given a breaker tier of maxFailures 2 and no retry, an interceptor, and a server answering
        // GET /m with 404 once and then 503
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        AtomicInteger seen = new AtomicInteger();
        RecordingServer server = support.server((request, body) -> request.response()
                .setStatusCode(seen.incrementAndGet() == 1 ? 404 : 503)
                .end());
        Completions interceptor = new Completions();
        VertiqueHttpClient client = client(runtime(events), server, tiers("t", breakerTier(2, 60_000)), interceptor);

        // When the caller runs three calls (404, 503, 503) and then one more while the circuit is open
        Throwable first = awaitFailure(get(client, tierOptions("t"), "/m"));
        Throwable second = awaitFailure(get(client, tierOptions("t"), "/m"));
        Throwable third = awaitFailure(get(client, tierOptions("t"), "/m"));
        Throwable rejected = awaitFailure(get(client, tierOptions("t"), "/m"));

        // Then the last future fails with CircuitOpenException exactly as the runtime raised it
        assertRejected(first, 404);
        assertRejected(second, 503);
        assertRejected(third, 503);
        assertThat(rejected).isExactlyInstanceOf(CircuitOpenException.class);

        // And the server received no request for that call and the interceptor was not notified for it
        assertThat(server.requests).hasSize(3);
        interceptor.awaitCount(3);
        sleep(SETTLE_MS);
        assertThat(interceptor.completed).hasSize(3);

        // And the 404 was not counted: the third call was admitted and the circuit opened after the two 503s
        List<CircuitStateChanged> changes = of(events, CircuitStateChanged.class);
        assertThat(changes).extracting(CircuitStateChanged::newState).containsExactly(CircuitState.OPEN);
    }

    // --- AC-033 ---

    /** AC-033: a POST is not retried after a timeout or a 502; every method retries a 503. */
    @Test
    @DisplayName("a POST is not retried after a timeout or a 502")
    void postIsNotRetriedAfterATimeoutOrA502() {
        // Given a retry tier, a 200 ms attempt timeout and a server with /slow silent, /bad 502 and
        // POST /busy 503 once and then 200
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        AtomicInteger busy = new AtomicInteger();
        RecordingServer server = support.server((request, body) -> {
            switch (request.method().name() + " " + request.uri()) {
                case "POST /slow", "GET /slow" -> {}
                case "POST /orders" -> request.response().setStatusCode(200).end();
                case "GET /status" -> request.response().setStatusCode(503).end();
                case "POST /redirect" ->
                    request.response()
                            .setStatusCode(303)
                            .putHeader("Location", "/next")
                            .end();
                case "GET /next" -> request.response().setStatusCode(503).end();
                case "POST /slowpost" ->
                    support.vertx.setTimer(1_000, id -> request.response().end());
                case "GET /status2" ->
                    support.vertx.setTimer(
                            100, id -> request.response().setStatusCode(503).end());
                case "POST /bad", "GET /bad" ->
                    request.response().setStatusCode(502).end();
                case "POST /busy" ->
                    request.response()
                            .setStatusCode(busy.incrementAndGet() == 1 ? 503 : 200)
                            .end();
                default -> request.response().setStatusCode(404).end();
            }
        });
        VertiqueHttpClient client = client(runtime(events), server, tiers("t", retryTier(2, 10)));
        CallOptions options = tierOptions("t").readTimeout(200, TimeUnit.MILLISECONDS);
        Buffer body = Buffer.buffer("abcd");

        // When the caller sends POST /slow
        int mark = events.size();
        Throwable postSlow = awaitFailure(client.execute(
                options, web -> web.post("/slow").sendBuffer(body).expecting(HttpExpectations.success())));
        awaitExecutionsCompleted(events, 1);
        List<TimeoutTriggered> postTimeouts = of(since(events, mark), TimeoutTriggered.class);

        // Then it fails with a ResilienceTimeoutException after exactly 1 request and 1 timeout event
        assertThat(postSlow).isExactlyInstanceOf(ResilienceTimeoutException.class);
        assertThat(count(server, "POST /slow")).isEqualTo(1);
        assertThat(postTimeouts).extracting(TimeoutTriggered::timeoutMs).containsExactly(200L);

        // When the caller sends GET /slow
        mark = events.size();
        Throwable getSlow = awaitFailure(get(client, options, "/slow"));
        awaitExecutionsCompleted(events, 2);
        List<TimeoutTriggered> getTimeouts = of(since(events, mark), TimeoutTriggered.class);

        // Then it fails the same way after exactly 3 requests and 3 timeout events
        assertThat(getSlow).isExactlyInstanceOf(ResilienceTimeoutException.class);
        assertThat(count(server, "GET /slow")).isEqualTo(3);
        assertThat(getTimeouts).extracting(TimeoutTriggered::timeoutMs).containsExactly(200L, 200L, 200L);

        // And POST /bad fails with a 502 after 1 request and GET /bad after 3
        Throwable postBad = awaitFailure(client.execute(
                options, web -> web.post("/bad").sendBuffer(body).expecting(HttpExpectations.success())));
        Throwable getBad = awaitFailure(get(client, options, "/bad"));
        assertRejected(postBad, 502);
        assertRejected(getBad, 502);
        assertThat(count(server, "POST /bad")).isEqualTo(1);
        assertThat(count(server, "GET /bad")).isEqualTo(3);

        // And POST /busy is retried because a 503 is retried for every method: 2 requests, then success
        HttpResponse<Buffer> busyResponse = await(client.execute(
                options, web -> web.post("/busy").sendBuffer(body).expecting(HttpExpectations.success())));
        assertThat(busyResponse.statusCode()).isEqualTo(200);
        assertThat(count(server, "POST /busy")).isEqualTo(2);

        // And an attempt that already sent a POST is not replayed when a later exchange of the attempt fails
        // with a status or a connection failure that is otherwise retried: exactly one POST arrives
        Throwable statusAfterPost = awaitFailure(client.execute(options, web -> web.post("/orders")
                .send()
                .compose(r -> web.get("/status").send().expecting(HttpExpectations.success()))));
        assertRejected(statusAfterPost, 503);
        sleep(SETTLE_MS);
        assertThat(count(server, "POST /orders")).isEqualTo(1);
        assertThat(count(server, "GET /status")).isEqualTo(1);
        int closedPort = unusedPort();
        Throwable connectAfterPost = awaitFailure(
                client.execute(options, web -> web.post("/orders").send().compose(r -> web.getAbs(
                                "http://127.0.0.1:" + closedPort + "/status")
                        .send())));
        assertThat(connectAfterPost).isInstanceOf(ConnectException.class);
        sleep(SETTLE_MS);
        assertThat(count(server, "POST /orders")).isEqualTo(2);

        // And a POST answered by a 303 is not resent when the followed GET fails with a retried 503
        VertiqueHttpClient following = build(builder(runtime(events), server, tiers("t", retryTier(2, 10)))
                .webClientOptions(
                        new WebClientOptions().setFollowRedirects(true).setMaxRedirects(5)));
        Throwable afterRedirect = awaitFailure(
                following.execute(options, web -> web.post("/redirect").send().expecting(HttpExpectations.success())));
        assertRejected(afterRedirect, 503);
        sleep(SETTLE_MS);
        assertThat(count(server, "POST /redirect")).isEqualTo(1);

        // And a written POST still in flight blocks the retry when a parallel GET fails with a retried 503
        Throwable parallel = awaitFailure(client.execute(options, web -> Future.all(
                        web.post("/slowpost").send(), web.get("/status2").send().expecting(HttpExpectations.success()))
                .<Void>mapEmpty()));
        assertRejected(parallel, 503);
        sleep(SETTLE_MS * 2);
        assertThat(count(server, "POST /slowpost")).isEqualTo(1);
    }

    // --- AC-035 ---

    /** AC-035: a cancelled half-open trial re-opens the breaker; a cancel while closed resets the count. */
    @Test
    @DisplayName("a cancelled half-open trial re-opens the breaker")
    void cancelledHalfOpenTrialReopensTheBreaker() {
        // Given two identical breaker tiers (maxFailures 2, resetTimeoutMs 300, no retry) and a server
        // with /fail 503, /hang never answering and /ok 200
        RecordingServer server = support.server((request, body) -> {
            switch (request.uri()) {
                case "/fail" -> request.response().setStatusCode(503).end();
                case "/hang" -> {}
                default -> request.response().end("ok");
            }
        });
        ResiliencePolicyOverrides breaker = breakerTier(2, 300);
        VertiqueHttpClient client =
                client(runtime(new CopyOnWriteArrayList<>()), server, tiers("t", breaker, "closed", breaker));

        // When two failing calls open the circuit and 400 ms pass, so the breaker is half-open
        assertRejected(awaitFailure(get(client, tierOptions("t"), "/fail")), 503);
        assertRejected(awaitFailure(get(client, tierOptions("t"), "/fail")), 503);
        sleep(400);

        // And the single trial GET /hang is cancelled 100 ms after the server received it
        CancellationSignal signal = CancellationSignal.create();
        Future<HttpResponse<Buffer>> trial = get(client, tierOptions("t").cancellation(signal), "/hang");
        assertThat(serverReceived(server, 3)).isTrue();
        sleep(100);
        long cancelledAt = System.nanoTime();
        signal.cancel();

        // Then the trial fails with HttpExchangeCancelledException promptly
        Throwable trialFailure = awaitFailure(trial);
        assertThat(millisSince(cancelledAt)).isLessThan(1_500);
        assertThat(trialFailure).isExactlyInstanceOf(HttpExchangeCancelledException.class);

        // And a GET /ok at once fails with CircuitOpenException and never reaches the server
        Throwable immediately = awaitFailure(get(client, tierOptions("t"), "/ok"));
        assertThat(immediately).isExactlyInstanceOf(CircuitOpenException.class);
        assertThat(count(server, "GET /ok")).isZero();

        // And after the reset timeout a GET /ok is admitted as the next trial and succeeds
        sleep(400);
        assertThat(await(get(client, tierOptions("t"), "/ok")).statusCode()).isEqualTo(200);

        // And a cancel while the breaker is closed resets the count: fail, cancelled hang, fail stays closed
        assertRejected(awaitFailure(get(client, tierOptions("closed"), "/fail")), 503);
        CancellationSignal closedSignal = CancellationSignal.create();
        Future<HttpResponse<Buffer>> hang = get(client, tierOptions("closed").cancellation(closedSignal), "/hang");
        assertThat(serverReceived(server, 6)).isTrue();
        sleep(100);
        closedSignal.cancel();
        assertThat(awaitFailure(hang)).isExactlyInstanceOf(HttpExchangeCancelledException.class);
        assertRejected(awaitFailure(get(client, tierOptions("closed"), "/fail")), 503);
        assertThat(await(get(client, tierOptions("closed"), "/ok")).statusCode())
                .isEqualTo(200);
    }

    // --- AC-037 and AC-038 ---

    /** Arrival times of the requests a server received, in nanoseconds. */
    private record Timed(RecordingServer server, List<Long> arrivals) {}

    /** A server answering {@code status} with {@code Retry-After: retryAfter} once and then 200. */
    private Timed retryAfterServer(int status, String retryAfter) {
        List<Long> arrivals = new CopyOnWriteArrayList<>();
        RecordingServer server = support.server((request, body) -> {
            arrivals.add(System.nanoTime());
            if (arrivals.size() == 1) {
                request.response()
                        .setStatusCode(status)
                        .putHeader("Retry-After", retryAfter)
                        .end();
            } else {
                request.response().end("ok");
            }
        });
        return new Timed(server, arrivals);
    }

    private static long gapMs(Timed timed) {
        return TimeUnit.NANOSECONDS.toMillis(
                timed.arrivals().get(1) - timed.arrivals().get(0));
    }

    /** AC-037: Retry-After lengthens the delay beyond the backoff, only when the tier opts in. */
    @Test
    @DisplayName("a retry waits for Retry-After instead of the shorter backoff")
    void retryWaitsForRetryAfterInsteadOfTheShorterBackoff() {
        // Given a tier with a 100 ms backoff, maxRetries 1 and retryAfterMaxMs 5000, and a 503 with Retry-After 2
        Timed honored = retryAfterServer(503, "2");
        VertiqueHttpClient client = client(
                runtime(new CopyOnWriteArrayList<>()),
                honored.server(),
                tiers("t", tier(1, 100, 5_000L, null, null, null)));

        // When the caller runs GET /wait
        HttpResponse<Buffer> response = await(get(client, tierOptions("t"), "/wait"));

        // Then the second request arrives at least 2000 ms after the first and the call succeeds with 200
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(honored.arrivals()).hasSize(2);
        assertThat(gapMs(honored)).isGreaterThanOrEqualTo(2_000);

        // And with retryAfterMaxMs left at 0 the same 503 is retried within 1000 ms, after the 100 ms backoff
        Timed ignored = retryAfterServer(503, "2");
        VertiqueHttpClient plain =
                client(runtime(new CopyOnWriteArrayList<>()), ignored.server(), tiers("t", retryTier(1, 100)));
        assertThat(await(get(plain, tierOptions("t"), "/wait")).statusCode()).isEqualTo(200);
        assertThat(ignored.arrivals()).hasSize(2);
        assertThat(gapMs(ignored)).isLessThan(1_000);
    }

    /** AC-038: a hint above the tier's maximum stops retrying; a hint within it is honored. */
    @Test
    @DisplayName("Retry-After above the maximum stops retrying")
    void retryAfterAboveTheMaximumStopsRetrying() {
        // Given a tier with maxRetries 2, a 100 ms backoff and retryAfterMaxMs 1000, and a 429 with Retry-After 30
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        RecordingServer limited = support.server((request, body) -> request.response()
                .setStatusCode(429)
                .putHeader("Retry-After", "30")
                .end());
        VertiqueHttpClient client =
                client(runtime(events), limited, tiers("t", tier(2, 100, 1_000L, null, null, null)));

        // When the caller runs GET /limited
        Throwable failure = awaitFailure(get(client, tierOptions("t"), "/limited"));

        // Then it fails with a 429 whose retryAfter is 30 seconds, after exactly 1 request
        assertRejected(failure, 429);
        assertThat(((HttpStatusRejectedException) failure).retryAfter()).isEqualTo(Optional.of(Duration.ofSeconds(30)));
        assertThat(limited.requests).hasSize(1);

        // And no second request arrives during the following 1500 ms, one RetryExhausted and no RetryScheduled
        sleep(1_500);
        assertThat(limited.requests).hasSize(1);
        assertThat(of(events, RetryExhausted.class)).hasSize(1);
        assertThat(of(events, RetryScheduled.class)).isEmpty();

        // And with Retry-After 1 the retry is attempted at least 1000 ms after the first request
        Timed within = retryAfterServer(429, "1");
        VertiqueHttpClient honoring = client(
                runtime(new CopyOnWriteArrayList<>()),
                within.server(),
                tiers("t", tier(2, 100, 1_000L, null, null, null)));
        assertThat(await(get(honoring, tierOptions("t"), "/limited")).statusCode())
                .isEqualTo(200);
        assertThat(within.arrivals()).hasSize(2);
        assertThat(gapMs(within)).isGreaterThanOrEqualTo(1_000);
    }

    // --- AC-042 ---

    /** AC-042: retried exchanges share a call id, ordinals are gap-free and an open circuit notifies nothing. */
    @Test
    @DisplayName("retried exchanges share a call id and an open circuit notifies nothing")
    void retriedExchangesShareACallIdAndAnOpenCircuitNotifiesNothing() {
        // Given a client with a retry tier and an interceptor against a server answering 503 every time
        RecordingServer down = statusServer(503);
        Completions retryInterceptor = new Completions();
        Resilience runtime = runtime(new CopyOnWriteArrayList<>());
        VertiqueHttpClient retrying = client(runtime, down, tiers("t", retryTier(2, 10)), retryInterceptor);

        // And a second client with a breaker tier (maxFailures 2, no retry) and its own interceptor and server
        RecordingServer breakerServer = statusServer(503);
        Completions breakerInterceptor = new Completions();
        VertiqueHttpClient breaking =
                client(runtime, breakerServer, tiers("t", breakerTier(2, 60_000)), breakerInterceptor);

        // When GET /down runs on the first client
        Throwable retried = awaitFailure(get(retrying, tierOptions("t"), "/down"));

        // Then it fails with a 503 and the interceptor saw 3 exchanges, one call id, ordinals 1, 2, 3
        assertRejected(retried, 503);
        List<Completions.Completed> done = retryInterceptor.awaitCount(3);
        sleep(SETTLE_MS);
        assertThat(retryInterceptor.completed).hasSize(3);
        assertThat(done)
                .extracting(c -> c.completion().callId())
                .containsOnly(done.get(0).completion().callId());
        assertThat(done).extracting(c -> c.completion().attemptOrdinal()).containsExactly(1, 2, 3);

        // When two failing calls and then one more run on the second client while its circuit is open
        assertRejected(awaitFailure(get(breaking, tierOptions("t"), "/x")), 503);
        assertRejected(awaitFailure(get(breaking, tierOptions("t"), "/x")), 503);
        Throwable open = awaitFailure(get(breaking, tierOptions("t"), "/x"));

        // Then the last call fails with CircuitOpenException, nothing was sent for it and only 2 were notified
        assertThat(open).isExactlyInstanceOf(CircuitOpenException.class);
        assertThat(breakerServer.requests).hasSize(2);
        breakerInterceptor.awaitCount(2);
        sleep(SETTLE_MS);
        assertThat(breakerInterceptor.completed).hasSize(2);
    }

    // --- cancellation and close ---

    /** Cancel and close always abort, even when the tier's retryOn names every throwable. */
    @Test
    @DisplayName("cancel and close are always aborted whatever retryOn says")
    void cancelAndCloseAreAlwaysAbortedWhateverRetryOnSays() {
        // Given a tier with maxRetries 3, a 10 ms backoff and retryOn naming Throwable
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        RecordingServer server = support.server((request, body) -> {});
        Map<String, ResiliencePolicyOverrides> tiers =
                tiers("all", withRetryOn(retryTier(3, 10), Set.of(Throwable.class)));
        VertiqueHttpClient cancelled = client(runtime(events), server, tiers);
        VertiqueHttpClient closed = client(runtime(events), server, tiers);

        // When a call hanging on the server is cancelled
        CancellationSignal signal = CancellationSignal.create();
        Future<HttpResponse<Buffer>> cancelCall =
                get(cancelled, tierOptions("all").cancellation(signal), "/a");
        assertThat(serverReceived(server, 1)).isTrue();
        signal.cancel();

        // Then it fails at once with HttpExchangeCancelledException and no attempt follows
        assertThat(awaitFailure(cancelCall)).isExactlyInstanceOf(HttpExchangeCancelledException.class);
        sleep(SETTLE_MS * 2);
        assertThat(server.requestsTo("/a")).hasSize(1);

        // When a call hanging on the server is closed with the client
        Future<HttpResponse<Buffer>> closeCall = get(closed, tierOptions("all"), "/b");
        assertThat(awaitCondition(() -> !server.requestsTo("/b").isEmpty(), HttpClientItSupport.WAIT_MS))
                .isTrue();
        await(closed.close());

        // Then it fails at once with HttpClientClosedException and no attempt follows
        assertThat(awaitFailure(closeCall)).isExactlyInstanceOf(HttpClientClosedException.class);
        sleep(SETTLE_MS * 2);
        assertThat(server.requestsTo("/b")).hasSize(1);
        assertThat(of(events, RetryScheduled.class)).isEmpty();
    }

    // --- classification and breaker accounting ---

    private record NeverSent(
            String kind, VertiqueHttpClient client, Function<WebClient, Future<?>> attempt, Class<?> type) {}

    /** Refusals before the first hop's request was written end the execution and the breaker treats them as cancels. */
    @Test
    @DisplayName("never-sent failures end without retry and the breaker treats them like cancelled executions")
    void neverSentFailuresEndWithoutRetryAndTheBreakerTreatsThemLikeCancelledExecutions() {
        // Given retry tiers (maxRetries 3) on clients whose first hop is refused: a failing credential source,
        // a denied host, an invalid header and an oversized request body
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        RecordingServer server = statusServer(200);
        Resilience runtime = runtime(events);
        Map<String, ResiliencePolicyOverrides> retryTiers = tiers("t", retryTier(3, 10));
        VertiqueHttpClient credential = build(builder(runtime, server, retryTiers)
                .credentials(target -> Future.failedFuture(new IllegalStateException("vault down"))));
        VertiqueHttpClient egress =
                build(builder(runtime, server, retryTiers).egress(EgressPolicy.allowHosts(List.of("127.0.0.1"), true)));
        VertiqueHttpClient header = build(builder(runtime, server, retryTiers));
        VertiqueHttpClient body = build(builder(runtime, server, retryTiers).maxRequestBytes(8));
        List<NeverSent> cases = List.of(
                new NeverSent("credential", credential, web -> web.get("/").send(), HttpCredentialException.class),
                new NeverSent(
                        "egress",
                        egress,
                        web -> web.getAbs("http://localhost:" + server.port() + "/")
                                .send(),
                        HttpEgressDeniedException.class),
                new NeverSent(
                        "header",
                        header,
                        web -> web.get("/").putHeader("X-A\nB", "a").send(),
                        IllegalArgumentException.class),
                new NeverSent(
                        "body",
                        body,
                        web -> web.post("/").sendBuffer(Buffer.buffer(new byte[16])),
                        HttpBodyLimitException.class));

        for (NeverSent refusal : cases) {
            // When the call runs in the tier
            AtomicInteger attempts = new AtomicInteger();
            Throwable failure = awaitFailure(refusal.client().execute(tierOptions("t"), web -> {
                attempts.incrementAndGet();
                return refusal.attempt().apply(web);
            }));
            sleep(SETTLE_MS);

            // Then it fails with the refusal after exactly 1 attempt and nothing was sent
            assertThat(failure).as(refusal.kind()).isExactlyInstanceOf(refusal.type());
            assertThat(attempts.get()).as(refusal.kind()).isEqualTo(1);
            assertThat(server.requests).as(refusal.kind()).isEmpty();
        }
        assertThat(of(events, RetryScheduled.class)).isEmpty();

        // And on a closed breaker a never-sent call resets the failure count: 503, refusal, 503 stays closed
        RecordingServer failing = statusServer(503);
        VertiqueHttpClient closedBreaker =
                client(runtime(new CopyOnWriteArrayList<>()), failing, tiers("b", breakerTier(2, 60_000)));
        assertRejected(awaitFailure(get(closedBreaker, tierOptions("b"), "/fail")), 503);
        assertThat(awaitFailure(closedBreaker.execute(
                        tierOptions("b"),
                        web -> web.get("/").putHeader("X-A\nB", "a").send())))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertRejected(awaitFailure(get(closedBreaker, tierOptions("b"), "/fail")), 503);
        assertThat(failing.requests).hasSize(2);

        // And as the half-open trial a never-sent call re-opens the breaker
        List<ResilienceEvent> trialEvents = new CopyOnWriteArrayList<>();
        VertiqueHttpClient trialBreaker = client(runtime(trialEvents), failing, tiers("h", breakerTier(1, 300)));
        assertRejected(awaitFailure(get(trialBreaker, tierOptions("h"), "/fail")), 503);
        sleep(400);
        assertThat(awaitFailure(trialBreaker.execute(
                        tierOptions("h"),
                        web -> web.get("/").putHeader("X-A\nB", "a").send())))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThat(awaitFailure(get(trialBreaker, tierOptions("h"), "/fail")))
                .isExactlyInstanceOf(CircuitOpenException.class);
    }

    /** The breaker counts every failure except a 4xx other than 429 and a body-limit failure. */
    @Test
    @DisplayName("the breaker counts failures except a 4xx other than 429 and a body limit")
    void breakerCountsFailuresExceptA4xxOtherThan429AndABodyLimit() {
        // Given breaker tiers of maxFailures 2 with no retry, a 10-byte response bound and a server
        RecordingServer server = support.server((request, body) -> {
            switch (request.uri()) {
                case "/fail" -> request.response().setStatusCode(503).end();
                case "/missing" -> request.response().setStatusCode(404).end();
                case "/limited" -> request.response().setStatusCode(429).end();
                case "/big" -> request.response().end(Buffer.buffer(new byte[100]));
                default -> request.response().end("ok");
            }
        });
        ResiliencePolicyOverrides breaker = breakerTier(2, 60_000);
        VertiqueHttpClient client = build(builder(
                        runtime(new CopyOnWriteArrayList<>()),
                        server,
                        tiers("fourxx", breaker, "limited", breaker, "bodyLimit", breaker))
                .maxResponseBytes(10));

        // When a 404 sits between two 503s, the 404 resets the consecutive count and the circuit stays closed
        assertRejected(awaitFailure(get(client, tierOptions("fourxx"), "/fail")), 503);
        assertRejected(awaitFailure(get(client, tierOptions("fourxx"), "/missing")), 404);
        assertRejected(awaitFailure(get(client, tierOptions("fourxx"), "/fail")), 503);
        assertThat(await(get(client, tierOptions("fourxx"), "/ok")).statusCode())
                .isEqualTo(200);

        // And two 429s count and open the circuit
        assertRejected(awaitFailure(get(client, tierOptions("limited"), "/limited")), 429);
        assertRejected(awaitFailure(get(client, tierOptions("limited"), "/limited")), 429);
        assertThat(awaitFailure(get(client, tierOptions("limited"), "/ok")))
                .isExactlyInstanceOf(CircuitOpenException.class);

        // And a body-limit failure between two 503s does not count, so the circuit stays closed
        assertRejected(awaitFailure(get(client, tierOptions("bodyLimit"), "/fail")), 503);
        Throwable limit = awaitFailure(get(client, tierOptions("bodyLimit"), "/big"));
        assertThat(limit).isExactlyInstanceOf(HttpBodyLimitException.class);
        assertRejected(awaitFailure(get(client, tierOptions("bodyLimit"), "/fail")), 503);
        assertThat(await(get(client, tierOptions("bodyLimit"), "/ok")).statusCode())
                .isEqualTo(200);
    }

    /** An idempotent-only failure on a non-idempotent exchange is not retried and surfaces unchanged. */
    @Test
    @DisplayName(
            "a non-idempotent exchange failing with an idempotent-only failure is not retried and the caller sees the original failure")
    void nonIdempotentExchangeFailingWithAnIdempotentOnlyFailureIsNotRetriedAndTheCallerSeesTheOriginalFailure() {
        // Given a retry tier, a tier naming HttpStatusRejectedException in retryOn, and a server answering
        // /bad with 502 and closing the connection for /reset before any head
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        RecordingServer server = support.server((request, body) -> {
            if (request.uri().equals("/reset")) {
                request.connection().close();
            } else {
                request.response().setStatusCode(502).end();
            }
        });
        VertiqueHttpClient client = client(
                runtime(events),
                server,
                tiers(
                        "t",
                        retryTier(2, 10),
                        "named",
                        withRetryOn(retryTier(2, 10), Set.of(HttpStatusRejectedException.class))));
        Buffer payload = Buffer.buffer("abcd");

        // When POST /bad runs in the default tier
        Throwable bad = awaitFailure(client.execute(
                tierOptions("t"), web -> web.post("/bad").sendBuffer(payload).expecting(HttpExpectations.success())));

        // Then the caller sees the original 502 rejection after exactly 1 request
        assertRejected(bad, 502);
        assertThat(bad.getCause()).isNull();
        assertThat(count(server, "POST /bad")).isEqualTo(1);
        awaitExecutionsCompleted(events, 1);
        assertThat(of(events, ExecutionCompleted.class).get(0).outcome())
                .isEqualTo(ResilienceOutcomeCategory.APPLICATION_FAILURE);
        assertThat(of(events, RetryScheduled.class)).isEmpty();

        // And a reset before the head: POST /reset fails with the original HttpClosedException after 1 request
        Throwable postReset = awaitFailure(
                client.execute(tierOptions("t"), web -> web.post("/reset").sendBuffer(payload)));
        assertThat(postReset).isInstanceOf(HttpClosedException.class);
        assertThat(postReset).isNotInstanceOf(NonRetryableFailure.class);
        assertThat(postReset.getClass().getName()).startsWith("io.vertx.");
        assertThat(count(server, "POST /reset")).isEqualTo(1);

        // And the same reset on GET is retried: 3 requests
        Throwable getReset = awaitFailure(
                client.execute(tierOptions("t"), web -> web.get("/reset").send()));
        assertThat(getReset).isInstanceOf(HttpClosedException.class);
        assertThat(count(server, "GET /reset")).isEqualTo(3);

        // And a tier naming the retryable type gets exactly what it names: POST /bad is retried, 3 requests
        Throwable named = awaitFailure(client.execute(
                tierOptions("named"),
                web -> web.post("/bad").sendBuffer(payload).expecting(HttpExpectations.success())));
        assertRejected(named, 502);
        assertThat(count(server, "POST /bad")).isEqualTo(4);
    }

    /** The tier timeout becomes the attempt timeout; a prebuilt pipeline is used as given. */
    @Test
    @DisplayName("the tier timeout becomes the attempt timeout and a prebuilt pipeline is used as given")
    void tierTimeoutBecomesTheAttemptTimeoutAndAPrebuiltPipelineIsUsedAsGiven() {
        // Given a tier with a 250 ms timeout concern and no retry, a recording runtime and a silent server
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        RecordingServer server = support.server((request, body) -> {});
        Resilience runtime = runtime(events);
        VertiqueHttpClient client = client(runtime, server, tiers("t", tier(null, 0, null, 250L, null, null)));

        // When the call runs in the tier
        Throwable failure = awaitFailure(
                client.execute(tierOptions("t"), web -> web.get("/never").send()));

        // Then it fails with a ResilienceTimeoutException of 250 ms with its event, from one pipeline timer
        assertThat(failure).isExactlyInstanceOf(ResilienceTimeoutException.class);
        assertThat(((ResilienceTimeoutException) failure).timeoutMs()).isEqualTo(250);
        awaitExecutionsCompleted(events, 1);
        sleep(SETTLE_MS);
        assertThat(of(events, TimeoutTriggered.class))
                .extracting(TimeoutTriggered::timeoutMs)
                .containsExactly(250L);
        assertThat(of(events, ExecutionCompleted.class))
                .extracting(ExecutionCompleted::outcome)
                .containsExactly(ResilienceOutcomeCategory.TIMEOUT);

        // When a prebuilt pipeline with a retry concern and no timeout concern runs with a 250 ms read timeout
        ResiliencePipeline prebuilt = runtime.pipeline("prebuilt")
                .retry(retry -> retry.maxRetries(1).backoff(RetryBackoff.fixed(10)))
                .build();
        int mark = events.size();
        long before = count(server, "GET /prebuilt");
        Throwable raw = awaitFailure(client.execute(
                CallOptions.defaults().resilience(prebuilt).readTimeout(250, TimeUnit.MILLISECONDS),
                web -> web.get("/prebuilt").send()));

        // Then the timeout stays raw, the pipeline's own retry ran (2 requests) and no TimeoutTriggered arose
        assertThat(raw).isExactlyInstanceOf(TimeoutException.class);
        assertThat(count(server, "GET /prebuilt") - before).isEqualTo(2);
        sleep(SETTLE_MS);
        assertThat(of(since(events, mark), TimeoutTriggered.class)).isEmpty();
    }

    // --- AC-085 ---

    /** AC-085: a tier timeout beats the builder default and a call option beats the tier. */
    @Test
    @DisplayName("a tier timeout overrides the builder default and a call option overrides the tier")
    void aTierTimeoutOverridesTheBuilderDefaultAndACallOptionOverridesTheTier() {
        // Given a client built with readTimeout 30 s, a tier with a 200 ms timeout and no retry, a silent server
        RecordingServer server = support.server((request, body) -> {});
        VertiqueHttpClient client = build(builder(
                        runtime(new CopyOnWriteArrayList<>()),
                        server,
                        tiers("t", tier(null, 0, null, 200L, null, null)))
                .readTimeout(30, TimeUnit.SECONDS));

        // When the call runs in the tier
        long start = System.nanoTime();
        Throwable first = awaitFailure(
                client.execute(tierOptions("t"), web -> web.get("/never").send()));
        long firstMs = millisSince(start);

        // Then it fails with a 200 ms ResilienceTimeoutException within 200 ms plus 500 ms
        assertThat(first).isExactlyInstanceOf(ResilienceTimeoutException.class);
        assertThat(((ResilienceTimeoutException) first).timeoutMs()).isEqualTo(200);
        assertThat(firstMs).isBetween(200L, 700L);

        // And the server observes the connection closed
        assertThat(awaitCondition(() -> server.closedConnections.get() >= 1, HttpClientItSupport.WAIT_MS))
                .isTrue();

        // When the same call carries a 500 ms read timeout
        start = System.nanoTime();
        Throwable second = awaitFailure(
                client.execute(tierOptions("t").readTimeout(500, TimeUnit.MILLISECONDS), web -> web.get("/never")
                        .send()));
        long secondMs = millisSince(start);

        // Then the call option wins over the tier: a 500 ms ResilienceTimeoutException within 500 ms plus 500 ms
        assertThat(second).isExactlyInstanceOf(ResilienceTimeoutException.class);
        assertThat(((ResilienceTimeoutException) second).timeoutMs()).isEqualTo(500);
        assertThat(secondMs).isBetween(500L, 1_000L);
    }

    // --- redirect-hop refusal ---

    /** A refusal on a redirect hop is an ordinary failure: no retry and not counted by the breaker. */
    @Test
    @DisplayName("a redirect hop refusal is an ordinary failure, not retried and not counted by the breaker")
    void redirectHopRefusalIsAnOrdinaryFailureNotRetriedAndNotCountedByTheBreaker() {
        // Given a tier with maxRetries 2 and a breaker of maxFailures 2, an egress policy for 127.0.0.1 only,
        // redirects followed, and a server redirecting to localhost, which is denied
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        RecordingServer target = statusServer(200);
        RecordingServer redirecting = support.server((request, body) -> request.response()
                .setStatusCode(302)
                .putHeader("Location", "http://localhost:" + target.port() + "/")
                .end());
        VertiqueHttpClient client =
                build(builder(runtime(events), redirecting, tiers("t", tier(2, 10, null, null, 2, 60_000L)))
                        .egress(EgressPolicy.allowHosts(List.of("127.0.0.1"), true))
                        .webClientOptions(
                                new WebClientOptions().setFollowRedirects(true).setMaxRedirects(5)));

        // When the call runs four times
        List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            failures.add(awaitFailure(get(client, tierOptions("t"), "/out")));
        }

        // Then every call fails with the egress refusal, never CircuitOpenException, and none is retried
        assertThat(failures).allSatisfy(f -> assertThat(f).isExactlyInstanceOf(HttpEgressDeniedException.class));
        assertThat(((HttpEgressDeniedException) failures.get(3)).reason())
                .isEqualTo(HttpEgressDeniedException.Reason.HOST);
        assertThat(redirecting.requests).hasSize(4);
        assertThat(of(events, RetryScheduled.class)).isEmpty();

        // And the denied hop was never dialed
        assertThat(target.openedConnections.get()).isZero();
        assertThat(of(events, CircuitStateChanged.class))
                .extracting(CircuitStateChanged::newState)
                .doesNotContain(CircuitState.OPEN);

        // And as the half-open trial a redirect-hop refusal is recorded like a 4xx success and closes the breaker
        RecordingServer trialRedirect = support.server((request, body) -> {
            if (request.uri().equals("/fail")) {
                request.response().setStatusCode(503).end();
            } else {
                request.response()
                        .setStatusCode(302)
                        .putHeader("Location", "http://localhost:" + target.port() + "/")
                        .end();
            }
        });
        List<ResilienceEvent> trialEvents = new CopyOnWriteArrayList<>();
        VertiqueHttpClient trialClient =
                build(builder(runtime(trialEvents), trialRedirect, tiers("h", breakerTier(1, 300)))
                        .egress(EgressPolicy.allowHosts(List.of("127.0.0.1"), true))
                        .webClientOptions(
                                new WebClientOptions().setFollowRedirects(true).setMaxRedirects(5)));
        assertRejected(awaitFailure(get(trialClient, tierOptions("h"), "/fail")), 503);
        sleep(400);
        Throwable trial = awaitFailure(get(trialClient, tierOptions("h"), "/out"));
        assertThat(trial).isExactlyInstanceOf(HttpEgressDeniedException.class);
        // the next call reaches the server instead of finding an open circuit, and its failure opens it again
        assertRejected(awaitFailure(get(trialClient, tierOptions("h"), "/fail")), 503);
        assertThat(of(trialEvents, CircuitStateChanged.class))
                .extracting(CircuitStateChanged::newState)
                .containsSubsequence(CircuitState.OPEN, CircuitState.HALF_OPEN, CircuitState.CLOSED, CircuitState.OPEN);
    }

    // --- timeout reset cause ---

    /** An execution ended by a timeout resets its open exchanges with the timeout failure as the cause. */
    @Test
    @DisplayName("an execution ended by a timeout resets open exchanges with the timeout failure as cause")
    void anExecutionEndedByATimeoutResetsOpenExchangesWithTheTimeoutFailureAsCause() {
        // Given a recording interceptor, a silent server and an attempt that opens two exchanges at once
        RecordingServer server = support.server((request, body) -> {});
        Completions interceptor = new Completions();
        Resilience runtime = runtime(new CopyOnWriteArrayList<>());
        VertiqueHttpClient client =
                client(runtime, server, tiers("t", tier(null, 0, null, 250L, null, null)), interceptor);
        Function<WebClient, Future<Void>> twoExchanges = web ->
                Future.all(web.get("/one").send(), web.get("/two").send()).<Void>mapEmpty();

        // When the call runs in a tier with a 250 ms timeout
        Throwable tierFailure = awaitFailure(client.execute(tierOptions("t"), twoExchanges));

        // Then both open exchanges end with the client's ResilienceTimeoutException
        assertThat(tierFailure).isExactlyInstanceOf(ResilienceTimeoutException.class);
        List<Completions.Completed> tierDone = interceptor.awaitCount(2);
        assertThat(tierDone).allSatisfy(c -> {
            assertThat(c.completion().error()).isExactlyInstanceOf(ResilienceTimeoutException.class);
            assertThat(((ResilienceTimeoutException) c.completion().error()).timeoutMs())
                    .isEqualTo(250);
        });

        // When the same attempt runs with no resilience and a 250 ms read timeout
        Throwable rawFailure = awaitFailure(client.execute(
                CallOptions.defaults().noResilience().readTimeout(250, TimeUnit.MILLISECONDS), twoExchanges));

        // Then both open exchanges end with a java.util.concurrent.TimeoutException
        assertThat(rawFailure).isExactlyInstanceOf(TimeoutException.class);
        List<Completions.Completed> rawDone = interceptor.awaitCount(4);
        assertThat(rawDone.subList(2, 4))
                .allSatisfy(c -> assertThat(c.completion().error()).isExactlyInstanceOf(TimeoutException.class));

        // When a prebuilt pipeline's own 200 ms timer fires first (the client's default timer is 30 s)
        ResiliencePipeline prebuilt = runtime.pipeline("prebuilt-own-timer")
                .timeout(timeout -> timeout.duration(Duration.ofMillis(200)))
                .build();
        Throwable ownTimer = awaitFailure(client.execute(CallOptions.defaults().resilience(prebuilt), twoExchanges));

        // Then the call fails with the pipeline's timeout and the open exchanges end with a raw TimeoutException
        assertThat(ownTimer).isExactlyInstanceOf(ResilienceTimeoutException.class);
        List<Completions.Completed> ownDone = interceptor.awaitCount(6);
        assertThat(ownDone.subList(4, 6))
                .allSatisfy(c -> assertThat(c.completion().error()).isExactlyInstanceOf(TimeoutException.class));
    }

    // --- default classification: 401 and 403 ---

    /** The default classification retries neither 401, 403 nor 500. */
    @Test
    @DisplayName("401 and 403 are not retried by the default classification")
    void status401And403AreNotRetriedByTheDefaultClassification() {
        // Given a retry tier of maxRetries 2 and a server answering 401, 403 and 500 on their paths
        RecordingServer server = support.server((request, body) -> {
            switch (request.uri()) {
                case "/unauthorized" -> request.response().setStatusCode(401).end();
                case "/forbidden" -> request.response().setStatusCode(403).end();
                default -> request.response().setStatusCode(500).end();
            }
        });
        VertiqueHttpClient client = client(runtime(new CopyOnWriteArrayList<>()), server, tiers("t", retryTier(2, 10)));

        // When GET runs on each path
        Throwable unauthorized = awaitFailure(get(client, tierOptions("t"), "/unauthorized"));
        Throwable forbidden = awaitFailure(get(client, tierOptions("t"), "/forbidden"));
        Throwable error = awaitFailure(get(client, tierOptions("t"), "/error"));
        sleep(SETTLE_MS);

        // Then each fails with its status after exactly 1 request
        assertRejected(unauthorized, 401);
        assertRejected(forbidden, 403);
        assertRejected(error, 500);
        assertThat(server.requestsTo("/unauthorized")).hasSize(1);
        assertThat(server.requestsTo("/forbidden")).hasSize(1);
        assertThat(server.requestsTo("/error")).hasSize(1);
    }

    // --- AC-089 ---

    /** AC-089: a tier attempt timeout is a resilience timeout with its event; other calls stay raw. */
    @Test
    @DisplayName("a tier attempt timeout is a resilience timeout with its event and other calls stay raw")
    void aTierAttemptTimeoutIsAResilienceTimeoutWithItsEventAndOtherCallsStayRaw() {
        // Given a runtime with a recording observer, a 300 ms builder read timeout, tier t (retry 1, no
        // timeout concern), tier a (t plus abortOn ResilienceTimeoutException), an interceptor and a silent server
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        Resilience runtime = runtime(events);
        RecordingServer server = support.server((request, body) -> {});
        ResiliencePolicyOverrides t = retryTier(1, 10);
        Completions interceptor = new Completions();
        VertiqueHttpClient client = build(
                builder(runtime, server, tiers("t", t, "a", withAbortOn(t, Set.of(ResilienceTimeoutException.class))))
                        .readTimeout(300, TimeUnit.MILLISECONDS)
                        .register(interceptor));

        // When the caller runs GET /never in tier t
        Throwable failure = awaitFailure(
                client.execute(tierOptions("t"), web -> web.get("/never").send()));
        awaitExecutionsCompleted(events, 1);

        // Then it fails with a 300 ms ResilienceTimeoutException keyed by the pipeline, after exactly 2 requests
        assertThat(failure).isExactlyInstanceOf(ResilienceTimeoutException.class);
        ResilienceTimeoutException timeout = (ResilienceTimeoutException) failure;
        assertThat(timeout.timeoutMs()).isEqualTo(300);
        assertThat(server.requests).hasSize(2);

        // And the observer recorded 2 TimeoutTriggered(300), 2 AttemptCompleted(TIMEOUT), ExecutionCompleted(TIMEOUT)
        sleep(SETTLE_MS);
        assertThat(of(events, TimeoutTriggered.class))
                .extracting(TimeoutTriggered::timeoutMs)
                .containsExactly(300L, 300L);
        assertThat(of(events, TimeoutTriggered.class))
                .extracting(TimeoutTriggered::operationKey)
                .containsOnly(timeout.operationKey());
        assertThat(of(events, AttemptCompleted.class))
                .extracting(AttemptCompleted::outcome)
                .containsExactly(ResilienceOutcomeCategory.TIMEOUT, ResilienceOutcomeCategory.TIMEOUT);
        assertThat(of(events, ExecutionCompleted.class))
                .extracting(ExecutionCompleted::outcome)
                .containsExactly(ResilienceOutcomeCategory.TIMEOUT);

        // And onAttemptCompleted saw that ResilienceTimeoutException for each exchange
        List<Completions.Completed> done = interceptor.awaitCount(2);
        assertThat(done).allSatisfy(c -> assertThat(c.completion().error())
                .isExactlyInstanceOf(ResilienceTimeoutException.class));
        assertThat(done.get(1).completion().error()).isSameAs(failure);

        // And the same call in tier a fails after exactly 1 request, because abortOn matches the type
        int requestsBefore = server.requests.size();
        Throwable aborted = awaitFailure(
                client.execute(tierOptions("a"), web -> web.get("/never").send()));
        assertThat(aborted).isExactlyInstanceOf(ResilienceTimeoutException.class);
        assertThat(server.requests.size() - requestsBefore).isEqualTo(1);

        // And with noResilience() it fails with a raw TimeoutException and the observer recorded nothing for it
        awaitExecutionsCompleted(events, 2);
        int mark = events.size();
        Throwable raw = awaitFailure(client.execute(
                CallOptions.defaults().noResilience(), web -> web.get("/never").send()));
        assertThat(raw).isExactlyInstanceOf(TimeoutException.class);
        sleep(SETTLE_MS);
        assertThat(since(events, mark)).isEmpty();

        // And with a prebuilt pipeline that has a retry concern and no timeout concern it stays raw
        ResiliencePipeline prebuilt = runtime.pipeline("prebuilt-89")
                .retry(retry -> retry.maxRetries(1).backoff(RetryBackoff.fixed(10)))
                .build();
        Throwable prebuiltFailure =
                awaitFailure(client.execute(CallOptions.defaults().resilience(prebuilt), web -> web.get("/never")
                        .send()));
        assertThat(prebuiltFailure).isExactlyInstanceOf(TimeoutException.class);
    }

    // --- overlapping POST timeouts ---

    /** Overlapping POST timeouts on one tier are each refused retry, whichever other call settles first. */
    @Test
    @DisplayName("overlapping POST timeouts on one tier are each refused retry when another call settles first")
    void overlappingPostTimeoutsOnOneTierAreEachRefusedRetryWhenAnotherCallSettlesFirst() {
        // Given a retry tier shared by all calls, a 300 ms attempt timeout and a server that answers only /fast
        RecordingServer server = support.server((request, body) -> {
            if (request.uri().equals("/fast")) {
                request.response().end("ok");
            }
        });
        VertiqueHttpClient client = client(runtime(new CopyOnWriteArrayList<>()), server, tiers("t", retryTier(2, 10)));
        CallOptions options = tierOptions("t").readTimeout(300, TimeUnit.MILLISECONDS);
        Buffer payload = Buffer.buffer("abcd");
        int posts = 6;

        // When six POSTs to distinct silent paths, one silent GET and fast calls overlap, started 15 ms apart
        List<Future<HttpResponse<Buffer>>> postCalls = new ArrayList<>();
        List<Future<HttpResponse<Buffer>>> others = new ArrayList<>();
        for (int i = 0; i < posts; i++) {
            String path = "/slow-" + i;
            postCalls.add(client.execute(
                    options, web -> web.post(path).sendBuffer(payload).expecting(HttpExpectations.success())));
            others.add(get(client, options, "/fast"));
            if (i == 2) {
                others.add(get(client, options, "/slow-get"));
            }
            sleep(15);
        }

        // Then every POST fails with a ResilienceTimeoutException after exactly 1 request
        for (Future<HttpResponse<Buffer>> call : postCalls) {
            assertThat(awaitFailure(call)).isExactlyInstanceOf(ResilienceTimeoutException.class);
        }
        for (int i = 0; i < posts; i++) {
            assertThat(count(server, "POST /slow-" + i)).as("POST /slow-%d", i).isEqualTo(1);
        }

        // And the overlapping idempotent GET is retried as usual: 3 requests, and the fast calls succeeded
        assertThat(awaitFailure(others.get(3))).isExactlyInstanceOf(ResilienceTimeoutException.class);
        assertThat(count(server, "GET /slow-get")).isEqualTo(3);
        for (int i = 0; i < others.size(); i++) {
            if (i != 3) {
                assertThat(await(others.get(i)).statusCode()).isEqualTo(200);
            }
        }

        // And once every call settled, the client's registry of non-retryable timeouts is empty
        assertThat(awaitCondition(
                        () -> ((DefaultVertiqueHttpClient) client).nonRetryableTimeoutCount() == 0,
                        HttpClientItSupport.WAIT_MS))
                .as("non-retryable timeout registry drained")
                .isTrue();
    }

    // --- AC-090 ---

    /** AC-090: a prebuilt pipeline's own timeout ends the first attempt's exchange before the next attempt. */
    @Test
    @DisplayName("a prebuilt pipeline's own timeout ends the attempt before the next one")
    void aPrebuiltPipelinesOwnTimeoutEndsTheAttemptBeforeTheNextOne() {
        // Given a client with defaults, a prebuilt pipeline with a 200 ms timeout and one retry, and a hanging server
        RecordingServer server = support.server((request, body) -> {});
        Resilience runtime = runtime(new CopyOnWriteArrayList<>());
        VertiqueHttpClient client =
                build(VertiqueHttpClientBuilder.create(support.vertx, runtime).baseUrl(server.origin()));
        ResiliencePipeline pipeline = runtime.pipeline("prebuilt-90")
                .timeout(timeout -> timeout.duration(Duration.ofMillis(200)))
                .retry(retry -> retry.maxRetries(1).backoff(RetryBackoff.fixed(10)))
                .build();
        AtomicReference<WebClient> firstWeb = new AtomicReference<>();

        // When the call runs and the second attempt's request arrives
        Future<HttpResponse<Buffer>> call =
                client.execute(CallOptions.defaults().resilience(pipeline), web -> {
                    firstWeb.compareAndSet(null, web);
                    return web.get("/hang").send();
                });
        assertThat(serverReceived(server, 2)).isTrue();

        // Then the first attempt's connection is closed within 100 ms of that arrival, before the call settled
        assertThat(awaitCondition(() -> server.closedConnections.get() >= 1, 100))
                .isTrue();
        assertThat(call.isComplete()).isFalse();

        // And a request on the first attempt's WebClient now fails with IllegalStateException and reaches no server
        Throwable stale = failureOf(() -> firstWeb.get().get("/after").send());
        assertThat(stale).isInstanceOf(IllegalStateException.class);
        assertThat(server.requestsTo("/after")).isEmpty();

        // And the call fails with the pipeline's ResilienceTimeoutException after exactly 2 requests
        Throwable failure = awaitFailure(call);
        assertThat(failure).isExactlyInstanceOf(ResilienceTimeoutException.class);
        assertThat(((ResilienceTimeoutException) failure).operationKey()).isEqualTo(pipeline.operationKey());
        assertThat(server.requestsTo("/hang")).hasSize(2);
        assertThat(server.requests).hasSize(2);
    }

    /** The failure of an action that may throw synchronously or fail its future. */
    private static Throwable failureOf(Supplier<Future<?>> action) {
        try {
            return awaitFailure(action.get());
        } catch (IllegalStateException e) {
            return e;
        }
    }
}
