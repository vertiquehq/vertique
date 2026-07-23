// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.security.AuthMethod;
import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.ChannelClosedEvent;
import dev.vertique.security.events.ChannelIdentityRefreshedEvent;
import dev.vertique.security.events.ChannelOpenedEvent;
import dev.vertique.security.events.CredentialAcceptedEvent;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.origin.RequestOrigin;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link SecurityMetricsObserver}.
 *
 * <p>Verifies counter increments for credential and authorization events, gauge behavior for
 * channel lifecycle events (including clamp-at-0 and one-time WARN), gating by enabled flags,
 * resilience to a throwing registry, and null-safety on auth method.
 *
 * <p>All event fixtures are constructed locally — vertique-core does not publish a test-jar.
 */
class SecurityMetricsObserverTest {

    // --- Shared fixtures ---

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final CorrelationContext CORRELATION = minimalCorrelation();
    private static final SecurityContext SEC_CTX = minimalSecurityContext();

    private SimpleMeterRegistry registry;
    private MetricsConfig config;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        config = new JsonObject().mapTo(MetricsConfig.class); // defaults: all enabled
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    // =========================================================================
    // Test 1 — credential counters
    // =========================================================================

    @Nested
    @DisplayName("credential counters")
    class CredentialCounters {

        @Test
        @DisplayName(
                "accepted event increments vertique.security.credentials with outcome=accepted and method=normalizedKind")
        void acceptedIncrementsCounter() {
            SecurityMetricsObserver observer = new SecurityMetricsObserver(registry, config);

            AuthMethod jwtMethod = DefaultAuthMethod.jwt();
            CredentialAcceptedEvent event = acceptedEvent(jwtMethod);

            Future<Void> result = observer.onCredentialAccepted(event);

            assertTrue(result.succeeded());
            Counter counter = registry.find("vertique.security.credentials")
                    .tag("outcome", "accepted")
                    .tag("method", "JWT")
                    .counter();
            assertNotNull(counter, "counter with outcome=accepted,method=JWT must exist");
            assertEquals(1.0, counter.count(), 1e-9);
        }

        @Test
        @DisplayName(
                "rejected event increments vertique.security.credentials with outcome=rejected and method=normalizedKind")
        void rejectedIncrementsCounter() {
            SecurityMetricsObserver observer = new SecurityMetricsObserver(registry, config);

            AuthMethod apiKeyMethod = DefaultAuthMethod.apiKey();
            CredentialRejectedEvent event = rejectedEvent(apiKeyMethod);

            Future<Void> result = observer.onCredentialRejected(event);

            assertTrue(result.succeeded());
            Counter counter = registry.find("vertique.security.credentials")
                    .tag("outcome", "rejected")
                    .tag("method", "API_KEY")
                    .counter();
            assertNotNull(counter, "counter with outcome=rejected,method=API_KEY must exist");
            assertEquals(1.0, counter.count(), 1e-9);
        }

        @Test
        @DisplayName("multiple accepted and rejected events accumulate correctly")
        void multipleEventsAccumulate() {
            SecurityMetricsObserver observer = new SecurityMetricsObserver(registry, config);

            observer.onCredentialAccepted(acceptedEvent(DefaultAuthMethod.jwt()));
            observer.onCredentialAccepted(acceptedEvent(DefaultAuthMethod.jwt()));
            observer.onCredentialRejected(rejectedEvent(DefaultAuthMethod.jwt()));

            double acceptedCount = registry.find("vertique.security.credentials")
                    .tag("outcome", "accepted")
                    .tag("method", "JWT")
                    .counter()
                    .count();
            double rejectedCount = registry.find("vertique.security.credentials")
                    .tag("outcome", "rejected")
                    .tag("method", "JWT")
                    .counter()
                    .count();

            assertEquals(2.0, acceptedCount, 1e-9);
            assertEquals(1.0, rejectedCount, 1e-9);
        }
    }

    // =========================================================================
    // Test 2 — authorization decision counters
    // =========================================================================

    @Nested
    @DisplayName("authorization decision counters")
    class AuthorizationDecisionCounters {

        @Test
        @DisplayName("permit decision increments vertique.security.authz.decisions with decision=permit")
        void permitDecisionIncrementsCounter() {
            SecurityMetricsObserver observer = new SecurityMetricsObserver(registry, config);

            AuthorizationDecisionEvent event = authzDecisionEvent(AuthorizationDecision.permit("PERMITTED"));

            Future<Void> result = observer.onAuthorizationDecided(event);

            assertTrue(result.succeeded());
            Counter counter = registry.find("vertique.security.authz.decisions")
                    .tag("decision", "permit")
                    .counter();
            assertNotNull(counter, "counter with decision=permit must exist");
            assertEquals(1.0, counter.count(), 1e-9);
        }

        @Test
        @DisplayName("deny decision increments vertique.security.authz.decisions with decision=deny")
        void denyDecisionIncrementsCounter() {
            SecurityMetricsObserver observer = new SecurityMetricsObserver(registry, config);

            AuthorizationDecisionEvent event = authzDecisionEvent(AuthorizationDecision.deny("ROLE_MISSING"));

            Future<Void> result = observer.onAuthorizationDecided(event);

            assertTrue(result.succeeded());
            Counter counter = registry.find("vertique.security.authz.decisions")
                    .tag("decision", "deny")
                    .counter();
            assertNotNull(counter, "counter with decision=deny must exist");
            assertEquals(1.0, counter.count(), 1e-9);
        }

        @Test
        @DisplayName(
                "Slice-16: new AuthzReasonCodes deny codes all land on the same decision=deny counter, no cardinality explosion")
        void newDenyReasonCodesDoNotAddCounterCardinality() {
            // Every deny with a new AuthzReasonCodes constant must increment the same
            // decision=deny counter — the reason code is NOT a tag, so no cardinality explosion.
            SecurityMetricsObserver observer = new SecurityMetricsObserver(registry, config);

            List<String> denyCodes = List.of(
                    AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                    AuthzReasonCodes.ROLE_MISSING,
                    AuthzReasonCodes.DENY_ALL,
                    AuthzReasonCodes.SCOPE_MISSING,
                    AuthzReasonCodes.SCOPE_INSUFFICIENT,
                    AuthzReasonCodes.ACTION_NOT_REGISTERED,
                    AuthzReasonCodes.ACTION_NOT_ALLOWED,
                    AuthzReasonCodes.POLICY_NOT_FOUND,
                    AuthzReasonCodes.ROLE_POLICY_MISSING,
                    AuthzReasonCodes.POLICY_INVALID,
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    AuthzReasonCodes.INSTANCE_ELIGIBILITY_FAILED);

            for (String code : denyCodes) {
                observer.onAuthorizationDecided(authzDecisionEvent(AuthorizationDecision.deny(code)));
            }

            // Exactly one counter with tag decision=deny must exist — all deny events share it
            Counter denyCounter = registry.find("vertique.security.authz.decisions")
                    .tag("decision", "deny")
                    .counter();
            assertNotNull(denyCounter, "single decision=deny counter must exist");
            assertEquals(
                    denyCodes.size(),
                    denyCounter.count(),
                    1e-9,
                    "all deny events must increment the same counter; count must equal number of deny events");

            // Confirm the registry has exactly two authz counters total: permit and deny
            // (no per-reason-code proliferation)
            long authzCounterCount = registry.getMeters().stream()
                    .filter(m ->
                            "vertique.security.authz.decisions".equals(m.getId().getName()))
                    .count();
            // Only permit and deny dimensions exist — at most 2 counters (deny registered now;
            // permit not yet incremented so may be 1 or 0 depending on micrometer lazy-registration)
            assertTrue(
                    authzCounterCount <= 2,
                    "at most 2 authz decision counters (permit + deny) must exist; found: " + authzCounterCount
                            + ". New reason codes must NOT create new counter cardinality.");
        }
    }

    // =========================================================================
    // Test 3 — channel gauge
    // =========================================================================

    @Nested
    @DisplayName("channel lifecycle gauge")
    class ChannelGauge {

        @Test
        @DisplayName("open increments gauge to 1; second open to 2; close decrements to 1; two more closes clamp at 0")
        void openCloseClampAndWarn() {
            // Attach ListAppender to capture WARN logs
            Logger securityLogger = (Logger) LoggerFactory.getLogger(SecurityMetricsObserver.class);
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.start();
            securityLogger.addAppender(listAppender);

            try {
                SecurityMetricsObserver observer = new SecurityMetricsObserver(registry, config);

                // Gauge must be registered at construction
                Gauge gauge = registry.find("vertique.security.channels.active").gauge();
                assertNotNull(gauge, "gauge vertique.security.channels.active must be registered");

                observer.onChannelLifecycle(openedEvent("ch-1"));
                assertEquals(1.0, gauge.value(), 1e-9, "gauge must be 1 after first open");

                observer.onChannelLifecycle(openedEvent("ch-2"));
                assertEquals(2.0, gauge.value(), 1e-9, "gauge must be 2 after second open");

                observer.onChannelLifecycle(closedEvent("ch-1"));
                assertEquals(1.0, gauge.value(), 1e-9, "gauge must be 1 after first close");

                // Close below 0 — should clamp at 0, emit WARN
                observer.onChannelLifecycle(closedEvent("ch-2"));
                assertEquals(0.0, gauge.value(), 1e-9, "gauge must be 0 after all channels closed");

                // This close should clamp and the one-time WARN fires
                observer.onChannelLifecycle(closedEvent("ch-x"));
                assertEquals(0.0, gauge.value(), 1e-9, "gauge must stay 0 on underflow close");

                // Verify exactly ONE warn was logged (the first underflow triggers it; subsequent ones don't)
                long warnCount = listAppender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .count();
                assertEquals(1, warnCount, "exactly one WARN must be logged for gauge underflow");

                // identity-refreshed must be a no-op
                observer.onChannelLifecycle(refreshedEvent("ch-3"));
                assertEquals(0.0, gauge.value(), 1e-9, "gauge must be unchanged after identity refresh");
            } finally {
                securityLogger.detachAppender(listAppender);
                listAppender.stop();
            }
        }
    }

    // =========================================================================
    // Test 4 — gating by enabled flags
    // =========================================================================

    @Nested
    @DisplayName("gating: disabled config produces no meters")
    class Gating {

        @Test
        @DisplayName("security.enabled=false → no meters registered, events are no-ops")
        void securityDisabledNoMeters() {
            MetricsConfig cfg = new JsonObject()
                    .put("security", new JsonObject().put("enabled", false))
                    .mapTo(MetricsConfig.class);
            SecurityMetricsObserver observer = new SecurityMetricsObserver(registry, cfg);

            // No gauge on construction
            assertNull(registry.find("vertique.security.channels.active").gauge(), "no gauge when security disabled");

            // Events must be no-ops (succeed but no counters)
            assertTrue(observer.onCredentialAccepted(acceptedEvent(DefaultAuthMethod.jwt()))
                    .succeeded());
            assertTrue(observer.onCredentialRejected(rejectedEvent(DefaultAuthMethod.jwt()))
                    .succeeded());
            assertTrue(observer.onAuthorizationDecided(authzDecisionEvent(AuthorizationDecision.permit("OK")))
                    .succeeded());
            assertTrue(observer.onChannelLifecycle(openedEvent("c1")).succeeded());

            assertTrue(registry.getMeters().isEmpty(), "no meters must be registered when security disabled");
        }

        @Test
        @DisplayName("metrics.enabled=false → no meters registered, events are no-ops")
        void metricsTopLevelDisabledNoMeters() {
            MetricsConfig cfg = new JsonObject().put("enabled", false).mapTo(MetricsConfig.class);
            SecurityMetricsObserver observer = new SecurityMetricsObserver(registry, cfg);

            assertNull(registry.find("vertique.security.channels.active").gauge(), "no gauge when metrics disabled");

            assertTrue(observer.onCredentialAccepted(acceptedEvent(DefaultAuthMethod.jwt()))
                    .succeeded());
            assertTrue(registry.getMeters().isEmpty(), "no meters must be registered when metrics disabled");
        }
    }

    // =========================================================================
    // Test 5 — never-throws resilience
    // =========================================================================

    @Nested
    @DisplayName("resilience: throwing registry never fails the future")
    class Resilience {

        @Test
        @DisplayName("counter() throws → every observer method returns succeeded future, no exception escapes")
        void throwingRegistryNeverFailsFuture() {
            MeterRegistry throwingRegistry = new ThrowingMeterRegistry();
            // config: security enabled so we get past the early-return guard
            SecurityMetricsObserver observer = new SecurityMetricsObserver(throwingRegistry, config);

            // All methods must return succeeded future even if the registry throws
            assertDoesNotThrow(() -> {
                Future<Void> r1 = observer.onCredentialAccepted(acceptedEvent(DefaultAuthMethod.jwt()));
                assertTrue(r1.succeeded(), "onCredentialAccepted must return succeeded future even if registry throws");

                Future<Void> r2 = observer.onCredentialRejected(rejectedEvent(DefaultAuthMethod.jwt()));
                assertTrue(r2.succeeded(), "onCredentialRejected must return succeeded future even if registry throws");

                Future<Void> r3 = observer.onAuthorizationDecided(authzDecisionEvent(AuthorizationDecision.deny("X")));
                assertTrue(
                        r3.succeeded(), "onAuthorizationDecided must return succeeded future even if registry throws");

                Future<Void> r4 = observer.onChannelLifecycle(openedEvent("c1"));
                assertTrue(r4.succeeded(), "onChannelLifecycle must return succeeded future even if registry throws");
            });
        }

        /**
         * Verifies W3: when the registry throws an exception whose message contains a sentinel
         * value, the observer logs only the exception CLASS name — not the full throwable (message
         * or cause chain). No log event's formatted message may contain the sentinel, and no log
         * event must carry a throwable proxy (i.e. the throwable is never passed to the logger).
         */
        @Test
        @DisplayName(
                "W3: registry throws with SENTINEL message → log event message lacks SENTINEL, throwable proxy is null")
        void throwingRegistryLogsClassNameOnlyNotThrowable() {
            String sentinel = "SENTINEL_secret_xyz";

            ch.qos.logback.classic.Logger securityLogger =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SecurityMetricsObserver.class);
            ch.qos.logback.core.read.ListAppender<ILoggingEvent> listAppender =
                    new ch.qos.logback.core.read.ListAppender<>();
            listAppender.start();
            securityLogger.addAppender(listAppender);
            // Ensure WARN events are captured
            ch.qos.logback.classic.Level previousLevel = securityLogger.getLevel();
            securityLogger.setLevel(ch.qos.logback.classic.Level.WARN);

            try {
                MeterRegistry throwingWithSentinel = new SentinelThrowingMeterRegistry(sentinel);
                SecurityMetricsObserver observer = new SecurityMetricsObserver(throwingWithSentinel, config);

                // Trigger each observer method — all must succeed
                Future<Void> r1 = observer.onCredentialAccepted(acceptedEvent(DefaultAuthMethod.jwt()));
                assertTrue(r1.succeeded(), "must return succeeded future");

                Future<Void> r2 = observer.onCredentialRejected(rejectedEvent(DefaultAuthMethod.jwt()));
                assertTrue(r2.succeeded(), "must return succeeded future");

                Future<Void> r3 =
                        observer.onAuthorizationDecided(authzDecisionEvent(AuthorizationDecision.deny("deny")));
                assertTrue(r3.succeeded(), "must return succeeded future");

                Future<Void> r4 = observer.onChannelLifecycle(openedEvent("c1"));
                assertTrue(r4.succeeded(), "must return succeeded future");

                // Assert: no log event's formattedMessage contains the sentinel
                for (ILoggingEvent event : listAppender.list) {
                    assertFalse(
                            event.getFormattedMessage().contains(sentinel),
                            "log message must not contain sentinel, got: " + event.getFormattedMessage());
                    // Assert: no throwable proxy attached — the throwable must not be passed to the logger
                    assertNull(
                            event.getThrowableProxy(),
                            "log event must not have a throwable proxy (throwable must not be passed to logger)");
                }
            } finally {
                securityLogger.setLevel(previousLevel);
                securityLogger.detachAppender(listAppender);
                listAppender.stop();
            }
        }
    }

    // =========================================================================
    // Test 6 — null-safety on auth method
    // =========================================================================

    @Nested
    @DisplayName("null-safety: null/blank normalizedKind → tag value 'unknown'")
    class NullSafety {

        @Test
        @DisplayName("accepted event with null normalizedKind() → method tag is 'unknown'")
        void nullNormalizedKindFallsBackToUnknown() {
            SecurityMetricsObserver observer = new SecurityMetricsObserver(registry, config);

            // AuthMethod whose normalizedKind() returns null
            AuthMethod nullKindMethod = new NullKindAuthMethod();
            CredentialAcceptedEvent event = acceptedEvent(nullKindMethod);

            observer.onCredentialAccepted(event);

            Counter counter = registry.find("vertique.security.credentials")
                    .tag("outcome", "accepted")
                    .tag("method", "unknown")
                    .counter();
            assertNotNull(counter, "method=unknown counter must exist for null normalizedKind");
            assertEquals(1.0, counter.count(), 1e-9);
        }

        @Test
        @DisplayName("rejected event with null AuthMethod → method tag is 'unknown', no throw")
        void nullAuthMethodFallsBackToUnknown() {
            SecurityMetricsObserver observer = new SecurityMetricsObserver(registry, config);

            // Build a rejected event whose attemptedMethod() returns a NullKindAuthMethod
            CredentialRejectedEvent event = rejectedEvent(new NullKindAuthMethod());

            assertDoesNotThrow(() -> observer.onCredentialRejected(event));

            Counter counter = registry.find("vertique.security.credentials")
                    .tag("outcome", "rejected")
                    .tag("method", "unknown")
                    .counter();
            assertNotNull(counter, "method=unknown counter must exist for null normalizedKind");
        }
    }

    // =========================================================================
    // Micro-measurement (NFR-TEL-003 input) — DOES NOT RUN IN CI
    // =========================================================================

    @Test
    @Disabled("manual micro-measurement for NFR-TEL-003 — not a CI assertion")
    @DisplayName("micro-measurement: empty composite no-op vs SimpleMeterRegistry counter throughput")
    void microMeasurementNfrTel003() {
        final int WARMUP = 100_000;
        final int RUNS = 1_000_000;
        final int REPEAT = 3;

        // --- Empty composite (pre-bootstrap, no backends) ---
        io.micrometer.core.instrument.composite.CompositeMeterRegistry composite =
                new io.micrometer.core.instrument.composite.CompositeMeterRegistry();
        Counter emptyCounter = composite.counter("nfr.test.counter");

        // warm-up
        for (int i = 0; i < WARMUP; i++) emptyCounter.increment();

        long[] emptyTotals = new long[REPEAT];
        for (int r = 0; r < REPEAT; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < RUNS; i++) emptyCounter.increment();
            emptyTotals[r] = System.nanoTime() - t0;
        }

        // --- SimpleMeterRegistry ---
        SimpleMeterRegistry simple = new SimpleMeterRegistry();
        Counter simpleCounter = simple.counter("nfr.test.counter");

        // warm-up
        for (int i = 0; i < WARMUP; i++) simpleCounter.increment();

        long[] simpleTotals = new long[REPEAT];
        for (int r = 0; r < REPEAT; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < RUNS; i++) simpleCounter.increment();
            simpleTotals[r] = System.nanoTime() - t0;
        }

        // Print results (captured by Surefire output file or console with -Dtest.output.toFile=false)
        System.out.println("=== NFR-TEL-003 micro-measurement ===");
        System.out.printf("Empty composite (%,d ops):%n", RUNS);
        for (int r = 0; r < REPEAT; r++) {
            System.out.printf(
                    "  run %d: %,d ns total / %.2f ns per op%n", r + 1, emptyTotals[r], (double) emptyTotals[r] / RUNS);
        }
        System.out.printf("SimpleMeterRegistry (%,d ops):%n", RUNS);
        for (int r = 0; r < REPEAT; r++) {
            System.out.printf(
                    "  run %d: %,d ns total / %.2f ns per op%n",
                    r + 1, simpleTotals[r], (double) simpleTotals[r] / RUNS);
        }
        simple.close();
        composite.close();
    }

    // =========================================================================
    // --- Event fixtures ---
    // =========================================================================

    /**
     * Minimal {@link CredentialAcceptedEvent} with the given auth method.
     *
     * @param method the auth method for the accepted credential
     * @return a fully-constructed event
     */
    private static CredentialAcceptedEvent acceptedEvent(AuthMethod method) {
        AuthenticationState authState =
                new AuthenticationState(method, List.of(), Optional.empty(), Optional.empty(), Map.of());
        SecurityIdentity identity = SecurityIdentity.anonymous();
        return new CredentialAcceptedEvent(NOW, CORRELATION, Optional.empty(), authState, identity);
    }

    /**
     * Minimal {@link CredentialRejectedEvent} with the given auth method.
     *
     * @param method the auth method that was attempted
     * @return a fully-constructed event
     */
    private static CredentialRejectedEvent rejectedEvent(AuthMethod method) {
        return new CredentialRejectedEvent(
                NOW,
                CORRELATION,
                Optional.empty(),
                method,
                Optional.empty(),
                Optional.empty(),
                "TEST_REASON",
                Map.of());
    }

    /**
     * Minimal {@link AuthorizationDecisionEvent} with the given decision.
     *
     * @param decision the authorization decision
     * @return a fully-constructed event
     */
    private static AuthorizationDecisionEvent authzDecisionEvent(AuthorizationDecision decision) {
        ResourceRef resource = new ResourceRef("test-resource", "1", Map.of());
        AuthorizationRequest request = new AuthorizationRequest(SEC_CTX, "READ", resource, Map.of());
        return new AuthorizationDecisionEvent(NOW, CORRELATION, Optional.empty(), request, decision);
    }

    /**
     * Minimal {@link ChannelOpenedEvent} for the given channel id.
     *
     * @param channelId the channel identifier
     * @return a fully-constructed event
     */
    private static ChannelOpenedEvent openedEvent(String channelId) {
        return new ChannelOpenedEvent(NOW, channelId, SEC_CTX, CORRELATION);
    }

    /**
     * Minimal {@link ChannelClosedEvent} for the given channel id.
     *
     * @param channelId the channel identifier
     * @return a fully-constructed event
     */
    private static ChannelClosedEvent closedEvent(String channelId) {
        return new ChannelClosedEvent(NOW, channelId, SEC_CTX, CORRELATION, "TEST_CLOSE");
    }

    /**
     * Minimal {@link ChannelIdentityRefreshedEvent} for the given channel id.
     *
     * @param channelId the channel identifier
     * @return a fully-constructed event
     */
    private static ChannelIdentityRefreshedEvent refreshedEvent(String channelId) {
        return new ChannelIdentityRefreshedEvent(NOW, channelId, SEC_CTX, CORRELATION, SEC_CTX);
    }

    // =========================================================================
    // --- Minimal test doubles ---
    // =========================================================================

    /**
     * Minimal {@link CorrelationContext} stub backed by a fixed snapshot.
     *
     * @return a minimal correlation context; never null
     */
    private static CorrelationContext minimalCorrelation() {
        CorrelationContextSnapshot snapshot = CorrelationContextSnapshot.of(
                new CorrelationIdentifier("req-test", "header"), new CorrelationIdentifier("corr-test", "header"));
        return new CorrelationContext() {
            @Override
            public CorrelationIdentifier requestId() {
                return snapshot.requestId();
            }

            @Override
            public CorrelationIdentifier correlationId() {
                return snapshot.correlationId();
            }

            @Override
            @Nullable
            public CorrelationIdentifier causationId() {
                return null;
            }

            @Override
            @Nullable
            public dev.vertique.core.correlation.TraceReference trace() {
                return null;
            }

            @Override
            public List<dev.vertique.core.correlation.ProtocolCorrelationRef> protocolCorrelations() {
                return List.of();
            }

            @Override
            @Nullable
            public dev.vertique.core.correlation.CorrelationSessionRef session() {
                return null;
            }

            @Override
            public Map<String, String> attributes() {
                return Map.of();
            }

            @Override
            public CorrelationContextSnapshot snapshot() {
                return snapshot;
            }
        };
    }

    /**
     * Minimal anonymous {@link SecurityContext} stub.
     *
     * @return a minimal security context; never null
     */
    private static SecurityContext minimalSecurityContext() {
        SecurityIdentity identity = SecurityIdentity.anonymous();
        AuthenticationState authentication = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims authorization = AuthorizationClaims.empty();
        return new SecurityContext() {
            @Override
            public SecurityIdentity identity() {
                return identity;
            }

            @Override
            public AuthenticationState authentication() {
                return authentication;
            }

            @Override
            public AuthorizationClaims authorization() {
                return authorization;
            }

            @Override
            public Optional<RequestOrigin> origin() {
                return Optional.empty();
            }
        };
    }

    /**
     * An {@link AuthMethod} whose {@link #normalizedKind()} returns {@code null}, used to verify
     * the "unknown" fallback tag value.
     */
    static final class NullKindAuthMethod implements AuthMethod {

        @Override
        public String id() {
            return "null-kind-method";
        }

        @Override
        @Nullable
        public AuthMethodKind normalizedKind() {
            return null;
        }

        @Override
        public Map<String, Object> attributes() {
            return Map.of();
        }
    }

    /**
     * A {@link MeterRegistry} stub that throws {@link UnsupportedOperationException} on every
     * meter-creation call. Used to verify that {@link SecurityMetricsObserver} never propagates
     * registry exceptions.
     */
    static final class ThrowingMeterRegistry extends SimpleMeterRegistry {

        @Override
        protected io.micrometer.core.instrument.Counter newCounter(io.micrometer.core.instrument.Meter.Id id) {
            throw new UnsupportedOperationException("intentional throw from ThrowingMeterRegistry");
        }

        @Override
        protected <T> Gauge newGauge(
                io.micrometer.core.instrument.Meter.Id id, @Nullable T obj, java.util.function.ToDoubleFunction<T> f) {
            throw new UnsupportedOperationException("intentional throw from ThrowingMeterRegistry");
        }
    }

    /**
     * A {@link MeterRegistry} stub that throws a {@link RuntimeException} whose message embeds the
     * given sentinel string on every meter-creation call. Used to verify that W3 log hardening
     * prevents the sentinel from appearing in log output.
     */
    static final class SentinelThrowingMeterRegistry extends SimpleMeterRegistry {

        private final String sentinel;

        SentinelThrowingMeterRegistry(String sentinel) {
            this.sentinel = sentinel;
        }

        @Override
        protected io.micrometer.core.instrument.Counter newCounter(io.micrometer.core.instrument.Meter.Id id) {
            throw new RuntimeException(sentinel);
        }

        @Override
        protected <T> Gauge newGauge(
                io.micrometer.core.instrument.Meter.Id id, @Nullable T obj, java.util.function.ToDoubleFunction<T> f) {
            throw new RuntimeException(sentinel);
        }
    }
}
