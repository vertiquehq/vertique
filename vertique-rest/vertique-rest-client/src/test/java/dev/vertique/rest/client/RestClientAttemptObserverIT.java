// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.resilience.BackoffStrategy;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.interceptor.RestClientAttemptCompletion;
import dev.vertique.rest.client.interceptor.RestClientAttemptTarget;
import dev.vertique.rest.client.interceptor.RestClientContextCapturer;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import dev.vertique.rest.client.interceptor.RestClientResponseContext;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration tests for the per-physical-attempt observer hooks on REST client proxies.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@link RestClientInterceptor#onAttemptCompleted} — the app-facing per-attempt observer:
 *       fires once per wire attempt with a stable {@code callId}, ascending
 *       {@code attemptOrdinal}, monotonic {@code durationMs}, and safe-by-type
 *       {@link RestClientAttemptTarget}.
 *   <li>{@link RestClientContextCapturer} SPI — captured context is held by the dispatcher
 *       across retries and recovery, isolated from application interceptors. Tests verify
 *       single-attempt capture, retry reuse, recovery preservation, and app-interceptor isolation.
 *   <li>Interceptor ordering — {@link ExtensionPhase#SYSTEM_FIRST} dominates
 *       {@link ExtensionPhase#APPLICATION} regardless of {@link RestClientInterceptor#priority()}.
 * </ul>
 *
 * <p>WireMock is the HTTP backend; {@link Fault#EMPTY_RESPONSE} drives transport-level retries.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RestClientAttemptObserverIT {

    // --- Zero-delay backoff keeps retry tests fast ---

    /**
     * {@link BackoffStrategy} that always returns zero delay, keeping retry tests fast.
     */
    public static class NoDelayBackoff implements BackoffStrategy {

        /** Creates a new instance with no delay between retries. */
        public NoDelayBackoff() {}

        /**
         * Returns zero for every retry count.
         *
         * @param retryCount the 0-based retry count (unused)
         * @return {@code 0L}
         */
        @Override
        public long delay(int retryCount) {
            return 0L;
        }
    }

    // --- Test client interface ---

    /**
     * Test client exercising single, retried, and recovered calls.
     */
    @RestClient(name = "attempt-observer-client")
    interface ObserverClient {

        /** Single attempt (no retry). */
        @GET
        @Path("/single")
        Future<String> single();

        /** Transport fault on attempt 1, 200 on attempt 2. */
        @GET
        @Path("/retry-then-ok")
        @Retry(maxRetries = 2, backoff = NoDelayBackoff.class)
        Future<String> retryThenOk();

        /** 401 then a recovered 200 (driven by a {@code recoverRequest} interceptor). */
        @GET
        @Path("/recover")
        Future<String> recover();

        /** Absolute {@code @Url} target — exercises the @Url path (no route template). */
        @GET
        Future<String> viaUrl(@Url java.net.URI url);
    }

    // --- Test doubles ---

    /**
     * App-facing per-attempt observer implementing {@link RestClientInterceptor}.
     * Records one {@link Attempt} per physical wire attempt via {@link #onAttemptCompleted}.
     * Does NOT stash any markers in the request context.
     */
    static final class CapturingObserver implements RestClientInterceptor {

        /**
         * Snapshot of one physical attempt as seen by {@link #onAttemptCompleted}.
         *
         * @param callId     stable per-logical-call identifier
         * @param ordinal    ascending attempt ordinal (1-based)
         * @param status     HTTP status code, or {@code null} on transport failure
         * @param errorType  simple class name of the error, or {@code null} on success
         * @param durationMs elapsed time for this attempt in milliseconds
         * @param target     safe-by-type metadata about the physical endpoint
         */
        record Attempt(
                String callId,
                int ordinal,
                Integer status,
                String errorType,
                long durationMs,
                RestClientAttemptTarget target) {}

        /** Thread-safe list of all observed attempts. */
        final List<Attempt> attempts = new CopyOnWriteArrayList<>();

        @Override
        public void onAttemptCompleted(RestClientRequestContext request, RestClientAttemptCompletion completion) {
            attempts.add(new Attempt(
                    completion.callId(),
                    completion.attemptOrdinal(),
                    completion.response() != null ? completion.response().statusCode() : null,
                    completion.error() != null ? completion.error().getClass().getSimpleName() : null,
                    completion.durationMs(),
                    completion.target()));
        }
    }

    /**
     * System-owned {@link RestClientContextCapturer} for tests.
     *
     * <p>Returns a unique per-call token (e.g. {@code "capture-1"}, {@code "capture-2"}) from
     * {@link #captureRequestContext()}, and records a {@link CaptureEvent} per attempt in
     * {@link #events}. Phase is {@link ExtensionPhase#SYSTEM_FIRST} as specified for system capturers.
     */
    static final class TestContextCapturer implements RestClientContextCapturer<String> {

        /**
         * One observed firing of {@link #onAttemptCompleted}.
         *
         * @param capturedToken the token returned by {@link #captureRequestContext()} for this call
         * @param ordinal       the attempt ordinal from the completion
         * @param callId        the call identifier from the completion
         */
        record CaptureEvent(String capturedToken, int ordinal, String callId) {}

        private final AtomicInteger counter = new AtomicInteger(0);

        /** The most recently issued capture token; set in {@link #captureRequestContext()}. */
        volatile String lastIssuedToken = null;

        /** Thread-safe list of all observed capturer firings. */
        final List<CaptureEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public ExtensionPhase phase() {
            return ExtensionPhase.SYSTEM_FIRST;
        }

        @Override
        public String captureRequestContext() {
            String token = "capture-" + counter.incrementAndGet();
            lastIssuedToken = token;
            return token;
        }

        @Override
        public void onAttemptCompleted(
                @Nullable String capturedContext,
                RestClientRequestContext request,
                RestClientAttemptCompletion completion) {
            events.add(new CaptureEvent(capturedContext, completion.attemptOrdinal(), completion.callId()));
        }
    }

    // --- WireMock lifecycle ---

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"))
            .build();

    /**
     * Resets all WireMock stubs and serve-events before each test so that stub registrations and
     * request counts from one test do not bleed into the next. The server itself stays up for the
     * whole class: restarting it per test races stub registration against in-flight dispatch under
     * full-suite load, which surfaces as unexplained 404s.
     */
    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    // --- Client builder helpers ---

    /**
     * Builds an {@link ObserverClient} registered with the given interceptors only.
     *
     * @param vertx        the Vert.x instance
     * @param interceptors zero or more interceptors to register
     * @return a ready-to-use proxy
     */
    private ObserverClient buildClient(Vertx vertx, RestClientInterceptor... interceptors) {
        RestClientBuilder b = new RestClientBuilder(vertx).baseUrl("http://127.0.0.1:" + wireMock.getPort());
        for (RestClientInterceptor i : interceptors) {
            b.register(i);
        }
        return b.build(ObserverClient.class);
    }

    /**
     * Builds an {@link ObserverClient} registered with the given capturer and interceptors.
     *
     * @param vertx     the Vert.x instance
     * @param capturer  the context capturer to register
     * @param interceptors zero or more interceptors to register
     * @return a ready-to-use proxy
     */
    private ObserverClient buildClientWithCapturer(
            Vertx vertx, RestClientContextCapturer<?> capturer, RestClientInterceptor... interceptors) {
        RestClientBuilder b = new RestClientBuilder(vertx)
                .baseUrl("http://127.0.0.1:" + wireMock.getPort())
                .registerCapturer(capturer);
        for (RestClientInterceptor i : interceptors) {
            b.register(i);
        }
        return b.build(ObserverClient.class);
    }

    // =========================================================================
    // Part 1 — CapturingObserver (app-facing onAttemptCompleted)
    // =========================================================================

    @Test
    @DisplayName("single attempt: one firing with ordinal 1, monotonic duration, and safe target metadata")
    void singleAttemptFiresOnce(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/single"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"ok\"")));
        CapturingObserver obs = new CapturingObserver();

        buildClient(vertx, obs)
                .single()
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertThat(obs.attempts).hasSize(1);
                    CapturingObserver.Attempt a = obs.attempts.get(0);
                    assertThat(a.ordinal()).isEqualTo(1);
                    assertThat(a.status()).isEqualTo(200);
                    assertThat(a.errorType()).isNull();
                    assertThat(a.callId()).isNotBlank();
                    assertThat(a.durationMs()).isGreaterThanOrEqualTo(0L);
                    // Safe-by-type target — scheme/host/port/pathTemplate, never the expanded URI.
                    assertThat(a.target().scheme()).isEqualTo("http");
                    assertThat(a.target().host()).isEqualTo("127.0.0.1");
                    assertThat(a.target().port()).isEqualTo(wireMock.getPort());
                    assertThat(a.target().pathTemplate()).isEqualTo("/single");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("@Url method records the actual sent host (not the configured base) with a null path template")
    void urlMethodRecordsActualSentTarget(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/url-target"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"ok\"")));
        CapturingObserver obs = new CapturingObserver();
        // Configure a DIFFERENT base host; the @Url argument points at the real WireMock.
        // The recorded target must reflect the actual @Url host, not the configured base.
        ObserverClient client = new RestClientBuilder(vertx)
                .baseUrl("http://configured-base:9999")
                .register(obs)
                .build(ObserverClient.class);
        java.net.URI target = java.net.URI.create("http://127.0.0.1:" + wireMock.getPort() + "/url-target");

        client.viaUrl(target)
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertThat(obs.attempts).hasSize(1);
                    CapturingObserver.Attempt a = obs.attempts.get(0);
                    assertThat(a.target().scheme()).isEqualTo("http");
                    assertThat(a.target().host()).isEqualTo("127.0.0.1"); // the @Url host, not "configured-base"
                    assertThat(a.target().port()).isEqualTo(wireMock.getPort());
                    assertThat(a.target().pathTemplate()).isNull(); // @Url has no route template
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("retry then success: ordinals 1,2 share one callId; first transport error, second 200")
    void retryThenSuccessFiresPerAttempt(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/retry-then-ok"))
                .inScenario("retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE))
                .willSetStateTo("ok"));
        wireMock.stubFor(get(urlEqualTo("/retry-then-ok"))
                .inScenario("retry")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"ok\"")));
        CapturingObserver obs = new CapturingObserver();

        buildClient(vertx, obs)
                .retryThenOk()
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertThat(obs.attempts).hasSize(2);
                    assertThat(obs.attempts)
                            .extracting(CapturingObserver.Attempt::ordinal)
                            .containsExactly(1, 2);
                    assertThat(obs.attempts)
                            .extracting(CapturingObserver.Attempt::callId)
                            .containsOnly(obs.attempts.get(0).callId());
                    assertThat(obs.attempts.get(0).status()).isNull(); // transport fault → no response
                    assertThat(obs.attempts.get(0).errorType()).isNotNull();
                    assertThat(obs.attempts.get(1).status()).isEqualTo(200);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("recovery re-dispatch: exactly 401,200 / ordinals 1,2 / one callId")
    void recoveryExactStatusesOrdinalsAndCallId(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/recover")).willReturn(aResponse().withStatus(401)));
        wireMock.stubFor(get(urlEqualTo("/recover"))
                .withHeader("Authorization", equalTo("Bearer refreshed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"ok\"")));
        CapturingObserver obs = new CapturingObserver();

        // A recover interceptor that issues a fresh context with a refreshed Authorization header.
        RestClientInterceptor recoverer = new RestClientInterceptor() {
            @Override
            public Future<Void> afterResponse(RestClientRequestContext req, RestClientResponseContext res) {
                return res.statusCode() == 401
                        ? Future.failedFuture(new RestClientResponseException(req, res))
                        : Future.succeededFuture();
            }

            @Override
            public Future<RestClientRequestContext> recoverRequest(
                    RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable err) {
                if (err instanceof RestClientResponseException ex && ex.statusCode() == 401) {
                    MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                            .addAll(req.headers())
                            .set("Authorization", "Bearer refreshed");
                    return Future.succeededFuture(new RestClientRequestContext(
                            req.httpMethod(),
                            req.requestUri(),
                            headers,
                            req.body(),
                            req.clientName(),
                            req.methodName(),
                            Map.of()));
                }
                return Future.failedFuture(err);
            }
        };

        buildClient(vertx, obs, recoverer)
                .recover()
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertThat(obs.attempts).hasSize(2);
                    assertThat(obs.attempts)
                            .extracting(CapturingObserver.Attempt::status)
                            .containsExactly(401, 200);
                    assertThat(obs.attempts)
                            .extracting(CapturingObserver.Attempt::ordinal)
                            .containsExactly(1, 2);
                    assertThat(obs.attempts)
                            .extracting(CapturingObserver.Attempt::callId)
                            .containsOnly(obs.attempts.get(0).callId());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("recovery re-dispatch failure: observer sees 500 (recovery error), not original 401")
    void recoveryReDispatchFailure_propagatesRecoveryError(Vertx vertx, VertxTestContext ctx) {
        // First attempt → 401; recovery re-dispatch → 500 (not recovered).
        wireMock.stubFor(get(urlEqualTo("/recover")).willReturn(aResponse().withStatus(401)));
        wireMock.stubFor(get(urlEqualTo("/recover"))
                .withHeader("Authorization", equalTo("Bearer refreshed"))
                .willReturn(aResponse().withStatus(500)));
        CapturingObserver obs = new CapturingObserver();

        // Interceptor that signals a 401 as an error to trigger recovery; leaves other statuses alone.
        // The dispatcher's handleSuccessResponse automatically converts the 500 into a
        // RestClientResponseException even if afterResponse returns succeededFuture() for it.
        RestClientInterceptor recoverer = new RestClientInterceptor() {
            @Override
            public Future<Void> afterResponse(RestClientRequestContext req, RestClientResponseContext res) {
                return res.statusCode() == 401
                        ? Future.failedFuture(new RestClientResponseException(req, res))
                        : Future.succeededFuture();
            }

            @Override
            public Future<RestClientRequestContext> recoverRequest(
                    RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable err) {
                if (err instanceof RestClientResponseException ex && ex.statusCode() == 401) {
                    MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                            .addAll(req.headers())
                            .set("Authorization", "Bearer refreshed");
                    return Future.succeededFuture(new RestClientRequestContext(
                            req.httpMethod(),
                            req.requestUri(),
                            headers,
                            req.body(),
                            req.clientName(),
                            req.methodName(),
                            Map.of()));
                }
                return Future.failedFuture(err);
            }
        };

        buildClient(vertx, obs, recoverer)
                .recover()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    // The terminal error must be the recovery re-dispatch failure (500),
                    // not the original pre-recovery error (401).
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    assertThat(((RestClientResponseException) err).statusCode()).isEqualTo(500);
                    // Both attempts recorded — ordinals 1 (401) and 2 (500 recovery failure).
                    assertThat(obs.attempts).hasSize(2);
                    assertThat(obs.attempts)
                            .extracting(CapturingObserver.Attempt::status)
                            .containsExactly(401, 500);
                    assertThat(obs.attempts)
                            .extracting(CapturingObserver.Attempt::ordinal)
                            .containsExactly(1, 2);
                    assertThat(obs.attempts)
                            .extracting(CapturingObserver.Attempt::callId)
                            .containsOnly(obs.attempts.get(0).callId());
                    ctx.completeNow();
                })));
    }

    // =========================================================================
    // Part 2 — TestContextCapturer SPI tests
    // =========================================================================

    @Test
    @DisplayName("capturer: single attempt — onAttemptCompleted fires once with the captured token")
    void capturerFiresOnceForSingleAttempt(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/single"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"ok\"")));
        TestContextCapturer capturer = new TestContextCapturer();

        buildClientWithCapturer(vertx, capturer)
                .single()
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertThat(capturer.events).hasSize(1);
                    TestContextCapturer.CaptureEvent ev = capturer.events.get(0);
                    assertThat(ev.capturedToken()).isEqualTo(capturer.lastIssuedToken);
                    assertThat(ev.ordinal()).isEqualTo(1);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("capturer: retry → fires twice (ordinals 1,2) BOTH with the SAME captured token")
    void capturerReusesTokenAcrossRetries(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/retry-then-ok"))
                .inScenario("capturer-retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE))
                .willSetStateTo("ok"));
        wireMock.stubFor(get(urlEqualTo("/retry-then-ok"))
                .inScenario("capturer-retry")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"ok\"")));
        TestContextCapturer capturer = new TestContextCapturer();

        buildClientWithCapturer(vertx, capturer)
                .retryThenOk()
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertThat(capturer.events).hasSize(2);
                    // Both ordinals present
                    assertThat(capturer.events)
                            .extracting(TestContextCapturer.CaptureEvent::ordinal)
                            .containsExactly(1, 2);
                    // captureRequestContext() was called exactly once — same token on both attempts
                    String token = capturer.events.get(0).capturedToken();
                    assertThat(token).isNotNull();
                    assertThat(capturer.events.get(1).capturedToken()).isEqualTo(token);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("capturer: recovery preserves the captured token across both attempts")
    void capturerTokenSurvivesRecovery(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/recover")).willReturn(aResponse().withStatus(401)));
        wireMock.stubFor(get(urlEqualTo("/recover"))
                .withHeader("Authorization", equalTo("Bearer refreshed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"ok\"")));
        TestContextCapturer capturer = new TestContextCapturer();

        // A recover interceptor that returns a FRESH context (dropping all attributes) — the
        // capturer's token must still be present on attempt 2, because the dispatcher holds it,
        // not the request context.
        RestClientInterceptor recoverer = new RestClientInterceptor() {
            @Override
            public Future<Void> afterResponse(RestClientRequestContext req, RestClientResponseContext res) {
                return res.statusCode() == 401
                        ? Future.failedFuture(new RestClientResponseException(req, res))
                        : Future.succeededFuture();
            }

            @Override
            public Future<RestClientRequestContext> recoverRequest(
                    RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable err) {
                if (err instanceof RestClientResponseException ex && ex.statusCode() == 401) {
                    MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                            .addAll(req.headers())
                            .set("Authorization", "Bearer refreshed");
                    // Fresh context — attributes are empty (no user-channel state)
                    return Future.succeededFuture(new RestClientRequestContext(
                            req.httpMethod(),
                            req.requestUri(),
                            headers,
                            req.body(),
                            req.clientName(),
                            req.methodName(),
                            Map.of()));
                }
                return Future.failedFuture(err);
            }
        };

        buildClientWithCapturer(vertx, capturer, recoverer)
                .recover()
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertThat(capturer.events).hasSize(2);
                    // Same token on both attempts — the dispatcher-held capture survived recovery
                    String token = capturer.events.get(0).capturedToken();
                    assertThat(token).isNotNull();
                    assertThat(capturer.events.get(1).capturedToken()).isEqualTo(token);
                    assertThat(capturer.events)
                            .extracting(TestContextCapturer.CaptureEvent::ordinal)
                            .containsExactly(1, 2);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("capturer: app interceptor that wipes user attrs cannot affect the captured token")
    void capturerTokenIsolatedFromAppInterceptors(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/single"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"ok\"")));
        TestContextCapturer capturer = new TestContextCapturer();

        // App interceptor wipes all user attributes — the capturer's token is dispatcher-held
        // (not in the request context), so it must still fire with its captured token.
        RestClientInterceptor userAttrWiper = new RestClientInterceptor() {
            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext reqCtx) {
                return Future.succeededFuture(reqCtx.withAttributes(Map.of()));
            }
        };

        buildClientWithCapturer(vertx, capturer, userAttrWiper)
                .single()
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertThat(capturer.events).hasSize(1);
                    TestContextCapturer.CaptureEvent ev = capturer.events.get(0);
                    // Capturer still received the token; the wiper could not reach it
                    assertThat(ev.capturedToken()).isEqualTo(capturer.lastIssuedToken);
                    assertThat(ev.capturedToken()).isNotNull();
                    ctx.completeNow();
                })));
    }

    // =========================================================================
    // Part 3 — Interceptor ordering: SYSTEM_FIRST phase dominates priority
    // =========================================================================

    @Test
    @DisplayName("SYSTEM_FIRST phase runs before APPLICATION regardless of priority values")
    void systemFirstPhaseRunsBeforeApplication(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/single"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"ok\"")));

        List<String> executionOrder = new CopyOnWriteArrayList<>();

        // SYSTEM_FIRST phase, highest numeric priority within any phase — must still run FIRST
        RestClientInterceptor systemFirst = new RestClientInterceptor() {
            @Override
            public ExtensionPhase phase() {
                return ExtensionPhase.SYSTEM_FIRST;
            }

            @Override
            public int priority() {
                return Integer.MAX_VALUE;
            }

            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext reqCtx) {
                executionOrder.add("system-first");
                return Future.succeededFuture(reqCtx);
            }
        };

        // APPLICATION phase (default), lowest numeric priority — must run AFTER SYSTEM_FIRST
        RestClientInterceptor appLast = new RestClientInterceptor() {
            @Override
            public int priority() {
                return Integer.MIN_VALUE;
            }

            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext reqCtx) {
                executionOrder.add("app-last");
                return Future.succeededFuture(reqCtx);
            }
        };

        new RestClientBuilder(vertx)
                .baseUrl("http://127.0.0.1:" + wireMock.getPort())
                .register(systemFirst)
                .register(appLast)
                .build(ObserverClient.class)
                .single()
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertThat(executionOrder).containsExactly("system-first", "app-last");
                    ctx.completeNow();
                })));
    }
}
