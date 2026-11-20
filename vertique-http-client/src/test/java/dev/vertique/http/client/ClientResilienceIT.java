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
import dev.vertique.resilience.BulkheadConfig;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResilienceDefaults;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResiliencePolicyOverrides;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.RetryBackoff;
import dev.vertique.resilience.RetryOverride;
import dev.vertique.resilience.TimeoutOverride;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.config.CircuitBreakerPolicyConfig;
import dev.vertique.resilience.config.ResiliencePolicyConfig;
import dev.vertique.resilience.config.RetryPolicyConfig;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import dev.vertique.resilience.spi.event.CircuitState;
import dev.vertique.resilience.spi.event.CircuitStateChanged;
import dev.vertique.resilience.spi.event.ExecutionCompleted;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.RetryExhausted;
import dev.vertique.resilience.spi.event.RetryScheduled;
import dev.vertique.resilience.spi.event.TimeoutTriggered;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests of the client's resilience composition: one pipeline execution per call in the
 * pipeline the caller passes, the client's own attempt timer, cancellation and {@code Retry-After}, the
 * breaker accounting and the observation of every retried exchange.
 *
 * <p>The pipelines are built from a {@link Resilience} runtime through its adapter context, the way the
 * REST client builds its own, so that a pipeline can carry a {@code Retry-After} maximum and a breaker
 * classifier. Every test runs against real Vert.x servers on {@code 127.0.0.1} and dynamic ports; elapsed
 * bounds carry generous margins over the configured delays.
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

    /** A policy built the way {@code resilience.policies.<name>} is bound; null leaves a concern out. */
    private static ResiliencePolicyOverrides tier(
            Integer maxRetries, long delayMs, Long retryAfterMaxMs, Integer maxFailures, Long resetTimeoutMs) {
        return new ResiliencePolicyConfig(
                        "tier",
                        null,
                        maxRetries == null
                                ? null
                                : new RetryPolicyConfig(maxRetries, delayMs, 1.0d, null, retryAfterMaxMs),
                        maxFailures == null ? null : new CircuitBreakerPolicyConfig(maxFailures, resetTimeoutMs),
                        null)
                .toOverrides();
    }

    /** The policy completed the way an adapter needs it: no timeout concern, and retry of every failure not aborted. */
    private static ResiliencePolicyOverrides withoutTimeout(String name, ResiliencePolicyOverrides tier) {
        ResiliencePolicyRegistry registry = requested -> tier;
        ResiliencePolicyOverrides policy = registry.layer(
                new ResilienceAnnotations(
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(name)),
                ResiliencePolicyOverrides.none());
        return new ResiliencePolicyOverrides(
                Optional.of(new TimeoutOverride(Optional.of(false), OptionalLong.empty())),
                policy.retry()
                        .map(r -> new RetryOverride(
                                r.enabled(),
                                r.maxRetries(),
                                r.backoff(),
                                r.retryOn(),
                                r.abortOn(),
                                Optional.of((failure, retryCount) -> true),
                                r.retryAfterMaxMs())),
                policy.circuitBreaker(),
                policy.bulkhead());
    }

    private static ResiliencePolicyOverrides retryTier(int maxRetries, long delayMs) {
        return tier(maxRetries, delayMs, null, null, null);
    }

    private static ResiliencePolicyOverrides breakerTier(int maxFailures, long resetTimeoutMs) {
        return tier(null, 0, null, maxFailures, resetTimeoutMs);
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

    /** The pipelines of the current test by policy name, built from the policies passed to the fixtures. */
    private final Map<String, ResiliencePipeline> pipelines = new HashMap<>();

    /** Builds the pipeline of one policy through the public builder, with an optional breaker classifier. */
    private ResiliencePipeline pipeline(
            Resilience runtime, String name, ResiliencePolicyOverrides overrides, Predicate<Throwable> classifier) {
        ResolvedResiliencePolicy resolved = runtime.policyResolver()
                .resolve(ResilienceAnnotations.NONE, withoutTimeout(name, overrides), ResilienceDefaults.none());
        ResiliencePipeline.Builder pipeline = runtime.pipeline(name);
        resolved.retry()
                .ifPresent(config -> pipeline.retry(r -> r.maxRetries(config.maxRetries())
                        .backoff(config.backoff())
                        .retryOn(config.retryOn())
                        .abortOn(config.abortOn())
                        .fallbackPolicy(config.fallbackPolicy())
                        .retryAfterMaxMs(config.retryAfterMaxMs())));
        resolved.circuitBreaker()
                .ifPresent(config -> pipeline.circuitBreaker(cb -> {
                    cb.maxFailures(config.maxFailures()).resetTimeout(Duration.ofMillis(config.resetTimeoutMs()));
                    if (classifier != null) {
                        cb.countsAsFailure(classifier);
                    }
                }));
        resolved.bulkhead()
                .ifPresent(config -> pipeline.bulkhead(b -> {
                    switch (config) {
                        case BulkheadConfig.Reject reject ->
                            b.maxConcurrentCalls(reject.maxConcurrentCalls()).reject();
                        case BulkheadConfig.Queue queue ->
                            b.maxConcurrentCalls(queue.maxConcurrentCalls())
                                    .queue(queue.maxQueueSize(), Duration.ofMillis(queue.queueTimeoutMs()));
                    }
                }));
        return pipeline.build();
    }

    private HttpClientBuilder builder(
            Resilience runtime, RecordingServer server, Map<String, ResiliencePolicyOverrides> policies) {
        return builder(runtime, server, policies, null);
    }

    private HttpClientBuilder builder(
            Resilience runtime,
            RecordingServer server,
            Map<String, ResiliencePolicyOverrides> policies,
            Predicate<Throwable> classifier) {
        policies.forEach((name, overrides) -> pipelines.put(name, pipeline(runtime, name, overrides, classifier)));
        return HttpClientBuilder.create(support.vertx).baseUrl(server.origin());
    }

    private HttpClient build(HttpClientBuilder builder) {
        HttpClient client = builder.build();
        support.track(client::close);
        return client;
    }

    private HttpClient client(
            Resilience runtime,
            RecordingServer server,
            Map<String, ResiliencePolicyOverrides> tiers,
            HttpClientInterceptor... interceptors) {
        return client(runtime, server, tiers, null, interceptors);
    }

    private HttpClient client(
            Resilience runtime,
            RecordingServer server,
            Map<String, ResiliencePolicyOverrides> tiers,
            Predicate<Throwable> classifier,
            HttpClientInterceptor... interceptors) {
        HttpClientBuilder builder = builder(runtime, server, tiers, classifier);
        for (HttpClientInterceptor interceptor : interceptors) {
            builder.register(interceptor);
        }
        return build(builder);
    }

    /** The options of a call that runs in the pipeline built for the policy {@code name}. */
    private CallOptions tierOptions(String name) {
        return CallOptions.defaults().resilience(pipelines.get(name));
    }

    private static Future<HttpResponse<Buffer>> get(HttpClient client, CallOptions options, String path) {
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
        HttpClient client = client(runtime(events), server, tiers("t", retryTier(2, 5000)));
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
        HttpClient failing =
                build(builder(runtime, server, tiers("t", retryTier(3, 10))).credentials(target -> {
                    failingResolutions.incrementAndGet();
                    return Future.failedFuture(new IllegalStateException("vault down"));
                }));
        HttpClient invalid =
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
        HttpClient client =
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

    /** Counts every failure except a 4xx rejection other than 429, which shows that the dependency answered. */
    private static final Predicate<Throwable> AC032_CLASSIFIER = failure -> {
        if (failure instanceof HttpStatusRejectedException rejected) {
            int status = rejected.statusCode();
            return status < 400 || status >= 500 || status == 429;
        }
        return true;
    };

    /** AC-032: an open circuit rejects without sending and a 404 does not count as a breaker failure. */
    @Test
    @DisplayName("an open circuit rejects without sending")
    void openCircuitRejectsWithoutSending() {
        // Given a breaker of maxFailures 2 with a classifier that skips a 4xx other than 429, no retry, an
        // interceptor, and a server answering GET /m with 404 once and then 503
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        AtomicInteger seen = new AtomicInteger();
        RecordingServer server = support.server((request, body) -> request.response()
                .setStatusCode(seen.incrementAndGet() == 1 ? 404 : 503)
                .end());
        Completions interceptor = new Completions();
        HttpClient client =
                client(runtime(events), server, tiers("t", breakerTier(2, 60_000)), AC032_CLASSIFIER, interceptor);

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
        HttpClient client =
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
        HttpClient client = client(
                runtime(new CopyOnWriteArrayList<>()), honored.server(), tiers("t", tier(1, 100, 5_000L, null, null)));

        // When the caller runs GET /wait
        HttpResponse<Buffer> response = await(get(client, tierOptions("t"), "/wait"));

        // Then the second request arrives at least 2000 ms after the first and the call succeeds with 200
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(honored.arrivals()).hasSize(2);
        assertThat(gapMs(honored)).isGreaterThanOrEqualTo(2_000);

        // And with retryAfterMaxMs left at 0 the same 503 is retried within 1000 ms, after the 100 ms backoff
        Timed ignored = retryAfterServer(503, "2");
        HttpClient plain =
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
        HttpClient client = client(runtime(events), limited, tiers("t", tier(2, 100, 1_000L, null, null)));

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
        HttpClient honoring = client(
                runtime(new CopyOnWriteArrayList<>()), within.server(), tiers("t", tier(2, 100, 1_000L, null, null)));
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
        HttpClient retrying = client(runtime, down, tiers("retry", retryTier(2, 10)), retryInterceptor);

        // And a second client with a breaker tier (maxFailures 2, no retry) and its own interceptor and server
        RecordingServer breakerServer = statusServer(503);
        Completions breakerInterceptor = new Completions();
        HttpClient breaking =
                client(runtime, breakerServer, tiers("breaker", breakerTier(2, 60_000)), breakerInterceptor);

        // When GET /down runs on the first client
        Throwable retried = awaitFailure(get(retrying, tierOptions("retry"), "/down"));

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
        assertRejected(awaitFailure(get(breaking, tierOptions("breaker"), "/x")), 503);
        assertRejected(awaitFailure(get(breaking, tierOptions("breaker"), "/x")), 503);
        Throwable open = awaitFailure(get(breaking, tierOptions("breaker"), "/x"));

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
        HttpClient cancelled = client(runtime(events), server, tiers);
        HttpClient closed = client(runtime(events), server, tiers);

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

    private record NeverSent(String kind, HttpClient client, Function<WebClient, Future<?>> attempt, Class<?> type) {}

    /** Refusals before the first hop's request was written end the execution and the breaker treats them as cancels. */
    @Test
    @DisplayName("never-sent failures end without retry and the breaker treats them like cancelled executions")
    void neverSentFailuresEndWithoutRetryAndTheBreakerTreatsThemLikeCancelledExecutions() {
        // Given retry tiers (maxRetries 3) on clients whose first hop is refused: a failing credential source,
        // a denied host and an oversized request body
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        RecordingServer server = statusServer(200);
        Resilience runtime = runtime(events);
        Map<String, ResiliencePolicyOverrides> retryTiers = tiers("t", retryTier(3, 10));
        HttpClient credential = build(builder(runtime, server, retryTiers)
                .credentials(target -> Future.failedFuture(new IllegalStateException("vault down"))));
        HttpClient egress =
                build(builder(runtime, server, retryTiers).egress(EgressPolicy.allowHosts(List.of("127.0.0.1"), true)));
        HttpClient body = build(builder(runtime, server, retryTiers).maxRequestBytes(8));
        List<NeverSent> cases = List.of(
                new NeverSent("credential", credential, web -> web.get("/").send(), HttpCredentialException.class),
                new NeverSent(
                        "egress",
                        egress,
                        web -> web.getAbs("http://localhost:" + server.port() + "/")
                                .send(),
                        HttpEgressDeniedException.class),
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
        HttpClient closedBreaker =
                build(builder(runtime(new CopyOnWriteArrayList<>()), failing, tiers("b", breakerTier(2, 60_000)))
                        .egress(EgressPolicy.allowHosts(List.of("127.0.0.1"), true)));
        assertRejected(awaitFailure(get(closedBreaker, tierOptions("b"), "/fail")), 503);
        assertThat(awaitFailure(closedBreaker.execute(
                        tierOptions("b"), web -> web.getAbs("http://localhost:" + failing.port() + "/")
                                .send())))
                .isExactlyInstanceOf(HttpEgressDeniedException.class);
        assertRejected(awaitFailure(get(closedBreaker, tierOptions("b"), "/fail")), 503);
        assertThat(failing.requests).hasSize(2);

        // And as the half-open trial a never-sent call re-opens the breaker
        List<ResilienceEvent> trialEvents = new CopyOnWriteArrayList<>();
        HttpClient trialBreaker = build(builder(runtime(trialEvents), failing, tiers("h", breakerTier(1, 300)))
                .egress(EgressPolicy.allowHosts(List.of("127.0.0.1"), true)));
        assertRejected(awaitFailure(get(trialBreaker, tierOptions("h"), "/fail")), 503);
        sleep(400);
        assertThat(awaitFailure(trialBreaker.execute(
                        tierOptions("h"), web -> web.getAbs("http://localhost:" + failing.port() + "/")
                                .send())))
                .isExactlyInstanceOf(HttpEgressDeniedException.class);
        assertThat(awaitFailure(get(trialBreaker, tierOptions("h"), "/fail")))
                .isExactlyInstanceOf(CircuitOpenException.class);
    }

    /** A typed refusal after another exchange of the attempt sent a request is an ordinary attempt failure. */
    @Test
    @DisplayName("an egress refusal after the attempt already sent a request reaches the pipeline and is retried")
    void egressRefusalAfterTheAttemptSentARequestIsRetried() {
        // Given a pipeline with maxRetries 3 and an egress policy for 127.0.0.1 only
        RecordingServer server = statusServer(200);
        HttpClient client = build(builder(runtime(new CopyOnWriteArrayList<>()), server, tiers("t", retryTier(3, 10)))
                .egress(EgressPolicy.allowHosts(List.of("127.0.0.1"), true)));

        // When each attempt first sends an allowed request and then one to a denied host
        AtomicInteger attempts = new AtomicInteger();
        Throwable failure = awaitFailure(client.execute(tierOptions("t"), web -> {
            attempts.incrementAndGet();
            return web.get("/").send().compose(sent -> web.getAbs("http://localhost:" + server.port() + "/")
                    .send());
        }));
        sleep(SETTLE_MS);

        // Then the refusal reached the pipeline, which retried every attempt: 4 attempts, 4 allowed requests
        assertThat(failure).isExactlyInstanceOf(HttpEgressDeniedException.class);
        assertThat(attempts.get()).isEqualTo(4);
        assertThat(server.requests).hasSize(4);
    }

    // --- redirect-hop refusal ---

    /** A refusal on a redirect hop comes after a request was sent: an ordinary failure the pipeline retries. */
    @Test
    @DisplayName("a redirect hop refusal is an ordinary attempt failure that the caller's pipeline classifies")
    void redirectHopRefusalIsAnOrdinaryAttemptFailureTheCallersPipelineClassifies() {
        // Given a pipeline with maxRetries 2, an egress policy for 127.0.0.1 only, redirects followed, and a
        // server redirecting to localhost, which is denied
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        RecordingServer target = statusServer(200);
        RecordingServer redirecting = support.server((request, body) -> request.response()
                .setStatusCode(302)
                .putHeader("Location", "http://localhost:" + target.port() + "/")
                .end());
        HttpClient client = build(builder(runtime(events), redirecting, tiers("t", retryTier(2, 10)))
                .egress(EgressPolicy.allowHosts(List.of("127.0.0.1"), true))
                .webClientOptions(
                        new WebClientOptions().setFollowRedirects(true).setMaxRedirects(5)));

        // When the call runs
        Throwable failure = awaitFailure(get(client, tierOptions("t"), "/out"));
        sleep(SETTLE_MS);

        // Then it fails with the egress refusal after the pipeline retried it like any attempt failure:
        // 3 requests reached the redirecting server, because the first hop had been sent
        assertThat(failure).isExactlyInstanceOf(HttpEgressDeniedException.class);
        assertThat(((HttpEgressDeniedException) failure).reason()).isEqualTo(HttpEgressDeniedException.Reason.HOST);
        assertThat(redirecting.requests).hasSize(3);
        assertThat(of(events, RetryScheduled.class)).hasSize(2);

        // And the denied hop was never dialed
        assertThat(target.openedConnections.get()).isZero();
    }

    /** An unreadable redirect {@code Location} is refused by the redirect rules after the first hop was answered. */
    @Test
    @DisplayName("a redirect rule refusal is an ordinary attempt failure that the caller's pipeline retries")
    void redirectRuleRefusalIsAnOrdinaryAttemptFailureThePipelineRetries() {
        // Given a retrying pipeline, redirects followed, and a server redirecting to a Location nobody can read
        RecordingServer redirecting = support.server((request, body) -> request.response()
                .setStatusCode(302)
                .putHeader("Location", "http://[bad host/")
                .end());
        HttpClient client =
                build(builder(runtime(new CopyOnWriteArrayList<>()), redirecting, tiers("t", retryTier(2, 10)))
                        .webClientOptions(
                                new WebClientOptions().setFollowRedirects(true).setMaxRedirects(5)));

        // When the call runs
        Throwable failure = awaitFailure(get(client, tierOptions("t"), "/out"));

        // Then it fails with the redirect refusal after the pipeline retried it: 3 requests were sent
        assertThat(failure).isExactlyInstanceOf(HttpEgressDeniedException.class);
        assertThat(redirecting.requests).hasSize(3);
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
        HttpClient client = client(runtime, server, tiers(), interceptor);
        Function<WebClient, Future<Void>> twoExchanges = web ->
                Future.all(web.get("/one").send(), web.get("/two").send()).<Void>mapEmpty();

        // When the same attempt runs with no resilience and a 250 ms read timeout
        Throwable rawFailure = awaitFailure(client.execute(
                CallOptions.defaults().noResilience().readTimeout(250, TimeUnit.MILLISECONDS), twoExchanges));

        // Then both open exchanges end with a java.util.concurrent.TimeoutException
        assertThat(rawFailure).isExactlyInstanceOf(TimeoutException.class);
        List<Completions.Completed> rawDone = interceptor.awaitCount(2);
        assertThat(rawDone)
                .allSatisfy(c -> assertThat(c.completion().error()).isExactlyInstanceOf(TimeoutException.class));

        // When a prebuilt pipeline's own 200 ms timer fires first (the client's default timer is 30 s)
        ResiliencePipeline prebuilt = runtime.pipeline("prebuilt-own-timer")
                .timeout(timeout -> timeout.duration(Duration.ofMillis(200)))
                .build();
        Throwable ownTimer = awaitFailure(client.execute(CallOptions.defaults().resilience(prebuilt), twoExchanges));

        // Then the call fails with the pipeline's timeout and the open exchanges end with a raw TimeoutException
        assertThat(ownTimer).isExactlyInstanceOf(ResilienceTimeoutException.class);
        List<Completions.Completed> ownDone = interceptor.awaitCount(4);
        assertThat(ownDone.subList(2, 4))
                .allSatisfy(c -> assertThat(c.completion().error()).isExactlyInstanceOf(TimeoutException.class));
    }

    // --- AC-089 ---

    /** AC-089: the client's attempt timeout is a native TimeoutException, with or without a prebuilt pipeline. */
    @Test
    @DisplayName("AC-089: the attempt timeout is a native TimeoutException with no pipeline and with a prebuilt one")
    void theAttemptTimeoutIsANativeTimeoutException() {
        // Given a runtime with a recording observer, a 300 ms builder read timeout, a prebuilt pipeline with a
        // retry concern and no timeout concern, an interceptor and a silent server
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        Resilience runtime = runtime(events);
        RecordingServer server = support.server((request, body) -> {});
        Completions interceptor = new Completions();
        HttpClient client = build(HttpClientBuilder.create(support.vertx)
                .baseUrl(server.origin())
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .register(interceptor));
        ResiliencePipeline prebuilt = runtime.pipeline("prebuilt-89")
                .retry(retry -> retry.maxRetries(1).backoff(RetryBackoff.fixed(10)))
                .build();

        // When the caller runs GET /never with noResilience()
        Throwable raw = awaitFailure(client.execute(
                CallOptions.defaults().noResilience(), web -> web.get("/never").send()));

        // Then it fails with a raw TimeoutException, the interceptor saw it and the observer recorded nothing
        assertThat(raw).isExactlyInstanceOf(TimeoutException.class);
        assertThat(raw.getMessage()).isNotBlank();
        List<Completions.Completed> first = interceptor.awaitCount(1);
        assertThat(first.get(0).completion().error()).isExactlyInstanceOf(TimeoutException.class);
        sleep(SETTLE_MS);
        assertThat(events).isEmpty();

        // When the same call runs in the prebuilt pipeline
        Throwable prebuiltFailure =
                awaitFailure(client.execute(CallOptions.defaults().resilience(prebuilt), web -> web.get("/never")
                        .send()));

        // Then it stays a raw TimeoutException, because a prebuilt pipeline is used as given, and each of its
        // two attempts' exchanges was seen as a TimeoutException
        assertThat(prebuiltFailure).isExactlyInstanceOf(TimeoutException.class);
        List<Completions.Completed> all = interceptor.awaitCount(3);
        assertThat(all.subList(1, 3))
                .allSatisfy(c -> assertThat(c.completion().error()).isExactlyInstanceOf(TimeoutException.class));
        assertThat(of(events, TimeoutTriggered.class)).isEmpty();
    }

    // --- AC-090 ---

    /** AC-090: a prebuilt pipeline's own timeout ends the first attempt's exchange before the next attempt. */
    @Test
    @DisplayName("a prebuilt pipeline's own timeout ends the attempt before the next one")
    void aPrebuiltPipelinesOwnTimeoutEndsTheAttemptBeforeTheNextOne() {
        // Given a client with defaults, a prebuilt pipeline with a 200 ms timeout and one retry, and a hanging server
        RecordingServer server = support.server((request, body) -> {});
        Resilience runtime = runtime(new CopyOnWriteArrayList<>());
        HttpClient client = build(HttpClientBuilder.create(support.vertx).baseUrl(server.origin()));
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
