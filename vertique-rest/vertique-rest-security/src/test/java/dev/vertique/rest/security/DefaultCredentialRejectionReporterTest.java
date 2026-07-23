// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.events.CredentialAcceptedEvent;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.JwksVerificationSource;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultCredentialRejectionReporter}.
 *
 * <p>Verifies the contract after the reporter was changed to emit {@link CredentialRejectedEvent}
 * directly through {@link SecurityEventEmitter} (rather than stashing for a downstream middleware
 * to drain — that mechanism could never fire because credential rejection is always followed by
 * {@code ctx.fail(...)} which short-circuits the route handler chain):
 *
 * <ul>
 *   <li>A call to {@code report()} assembles a {@link CredentialRejectedEvent} with all supplied
 *       parameters plus the pre-bound {@link RequestOrigin} (from the routing context) and
 *       {@link CorrelationContext} (from the context holder), then emits it.</li>
 *   <li>Multiple sequential {@code report()} calls emit one event each, in order.</li>
 *   <li>Absent {@link RequestOrigin} yields {@link Optional#empty()} in the event.</li>
 *   <li>Absent {@link CorrelationContext} throws {@link IllegalStateException}.</li>
 *   <li>Null arguments to {@code report()} are rejected.</li>
 * </ul>
 *
 * <p>Emission is verified through a real {@link SecurityEventEmitter} wired with a capturing
 * {@link SecurityEventObserver} test stub, so the test exercises the full emit path without a
 * live Vert.x event loop.
 */
class DefaultCredentialRejectionReporterTest {

    // --- Fixtures ---

    private static final JwksVerificationSource JWKS_SOURCE = new JwksVerificationSource(
            Optional.of("https://issuer.example.com"),
            Optional.of("https://issuer.example.com/.well-known/jwks.json"),
            Optional.of("key-id-123"),
            Optional.of("RS256"));

    private static final RequestOrigin SAMPLE_ORIGIN = new RequestOrigin(
            "203.0.113.10", 443, List.of(), 0, false, "203.0.113.10", "https", "api.example.com", Optional.empty());

    /** Capturing observer that records every {@link CredentialRejectedEvent} it receives. */
    private static final class CapturingObserver implements SecurityEventObserver {
        final List<CredentialRejectedEvent> rejections = new ArrayList<>();

        @Override
        public Future<Void> onCredentialRejected(CredentialRejectedEvent event) {
            rejections.add(event);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
            return Future.succeededFuture();
        }
    }

    private CapturingObserver observer;
    private SecurityEventEmitter emitter;

    @BeforeEach
    void setUpEmitter() {
        observer = new CapturingObserver();
        emitter = new SecurityEventEmitter(Set.of(observer));
    }

    private static CorrelationContext stubCorrelation() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        return factory.create(
                new CorrelationIdentifier("req-001", "test"), new CorrelationIdentifier("cor-001", "test"));
    }

    private static RoutingContext stubContext(Map<String, Object> backingMap) {
        RoutingContext ctx = mock(RoutingContext.class);
        when(ctx.get(anyString())).thenAnswer(inv -> backingMap.get(inv.getArgument(0, String.class)));
        when(ctx.put(anyString(), any())).thenAnswer(inv -> {
            backingMap.put(inv.getArgument(0, String.class), inv.getArgument(1));
            return ctx;
        });
        return ctx;
    }

    private static ContextHolder holderWith(CorrelationContext correlation) {
        ContextHolder holder = mock(ContextHolder.class);
        when(holder.current(CorrelationContext.class)).thenReturn(Optional.of(correlation));
        return holder;
    }

    private static ContextHolder emptyHolder() {
        ContextHolder holder = mock(ContextHolder.class);
        when(holder.current(CorrelationContext.class)).thenReturn(Optional.empty());
        return holder;
    }

    // --- Tests ---

    @Nested
    @DisplayName("report() — successful event assembly")
    class ReportSuccessful {

        private CorrelationContext correlation;
        private ContextHolder holder;

        @BeforeEach
        void setup() {
            correlation = stubCorrelation();
            holder = holderWith(correlation);
        }

        @Test
        @DisplayName("emits CredentialRejectedEvent with all fields populated")
        void emitsEventWithAllFields() {
            Map<String, Object> store = new HashMap<>();
            store.put(RequestOrigin.class.getName(), SAMPLE_ORIGIN);
            RoutingContext ctx = stubContext(store);

            DefaultCredentialRejectionReporter reporter = new DefaultCredentialRejectionReporter(holder, emitter);
            reporter.report(
                    ctx,
                    DefaultAuthMethod.jwt(),
                    Optional.of("kid-123"),
                    Optional.of(JWKS_SOURCE),
                    "JWT_EXPIRED",
                    Map.of("decodedHeader", "eyJhbGciOiJSUzI1NiIsImtpZCI6ImtleS1pZC0xMjMifQ"));

            assertEquals(1, observer.rejections.size(), "exactly one event emitted");
            CredentialRejectedEvent event = observer.rejections.get(0);
            assertNotNull(event.occurredAt(), "occurredAt must be non-null");
            assertSame(correlation, event.correlation(), "correlation must be the holder-bound instance");
            assertEquals(Optional.of(SAMPLE_ORIGIN), event.origin(), "origin must match routing context value");
            assertEquals(DefaultAuthMethod.jwt(), event.attemptedMethod(), "attemptedMethod must match");
            assertEquals(Optional.of("kid-123"), event.credentialId(), "credentialId must match");
            assertEquals(Optional.of(JWKS_SOURCE), event.verificationSource(), "verificationSource must match");
            assertEquals("JWT_EXPIRED", event.reasonCode(), "reasonCode must match");
            assertEquals(
                    "eyJhbGciOiJSUzI1NiIsImtpZCI6ImtleS1pZC0xMjMifQ",
                    event.safeAttributes().get("decodedHeader"),
                    "safeAttributes must contain supplied entry");
        }

        @Test
        @DisplayName("absent RequestOrigin yields Optional.empty() in event — report still succeeds")
        void absentOriginYieldsEmpty() {
            RoutingContext ctx = stubContext(new HashMap<>());

            DefaultCredentialRejectionReporter reporter = new DefaultCredentialRejectionReporter(holder, emitter);
            reporter.report(
                    ctx, DefaultAuthMethod.jwt(), Optional.empty(), Optional.empty(), "SIGNATURE_INVALID", Map.of());

            assertEquals(1, observer.rejections.size());
            assertEquals(
                    Optional.empty(), observer.rejections.get(0).origin(), "origin must be empty when not pre-bound");
        }

        @Test
        @DisplayName("null safeAttributes is treated as empty map")
        void nullSafeAttributesTreatedAsEmpty() {
            RoutingContext ctx = stubContext(new HashMap<>());

            DefaultCredentialRejectionReporter reporter = new DefaultCredentialRejectionReporter(holder, emitter);
            reporter.report(ctx, DefaultAuthMethod.jwt(), Optional.empty(), Optional.empty(), "TOKEN_MALFORMED", null);

            assertEquals(1, observer.rejections.size());
            assertTrue(observer.rejections.get(0).safeAttributes().isEmpty(), "safeAttributes must be empty for null");
        }

        @Test
        @DisplayName("occurredAt is non-null and within the current time window")
        void occurredAtIsRecent() {
            RoutingContext ctx = stubContext(new HashMap<>());

            long before = System.currentTimeMillis();
            DefaultCredentialRejectionReporter reporter = new DefaultCredentialRejectionReporter(holder, emitter);
            reporter.report(ctx, DefaultAuthMethod.jwt(), Optional.empty(), Optional.empty(), "REASON", Map.of());
            long after = System.currentTimeMillis();

            long epochMs = observer.rejections.get(0).occurredAt().toEpochMilli();
            assertTrue(epochMs >= before && epochMs <= after + 1000, "occurredAt must be within current time window");
        }

        @Test
        @DisplayName("correlation in event is the same object returned by the holder")
        void correlationIsHolderBoundInstance() {
            RoutingContext ctx = stubContext(new HashMap<>());
            DefaultCredentialRejectionReporter reporter = new DefaultCredentialRejectionReporter(holder, emitter);
            reporter.report(ctx, DefaultAuthMethod.jwt(), Optional.empty(), Optional.empty(), "REASON", Map.of());

            assertSame(correlation, observer.rejections.get(0).correlation(), "correlation must be holder instance");
        }
    }

    @Nested
    @DisplayName("report() — accumulation order")
    class AccumulationOrder {

        @Test
        @DisplayName("multiple report() calls emit in insertion order")
        void multipleReportsEmitInOrder() {
            CorrelationContext correlation = stubCorrelation();
            ContextHolder holder = holderWith(correlation);
            RoutingContext ctx = stubContext(new HashMap<>());

            DefaultCredentialRejectionReporter reporter = new DefaultCredentialRejectionReporter(holder, emitter);
            reporter.report(ctx, DefaultAuthMethod.jwt(), Optional.empty(), Optional.empty(), "FIRST", Map.of());
            reporter.report(ctx, DefaultAuthMethod.jwt(), Optional.empty(), Optional.empty(), "SECOND", Map.of());
            reporter.report(ctx, DefaultAuthMethod.jwt(), Optional.empty(), Optional.empty(), "THIRD", Map.of());

            assertEquals(3, observer.rejections.size(), "three events must be emitted");
            assertEquals("FIRST", observer.rejections.get(0).reasonCode());
            assertEquals("SECOND", observer.rejections.get(1).reasonCode());
            assertEquals("THIRD", observer.rejections.get(2).reasonCode());
        }
    }

    @Nested
    @DisplayName("report() — null argument rejection")
    class NullArguments {

        private DefaultCredentialRejectionReporter reporter() {
            return new DefaultCredentialRejectionReporter(emptyHolder(), emitter);
        }

        private RoutingContext emptyCtx() {
            return stubContext(new HashMap<>());
        }

        @Test
        @DisplayName("report(null ctx, ...) throws NullPointerException with message 'ctx'")
        void reportNullContextThrowsNpe() {
            NullPointerException ex = assertThrows(NullPointerException.class, () -> reporter()
                    .report(null, DefaultAuthMethod.jwt(), Optional.empty(), Optional.empty(), "REASON", Map.of()));
            assertEquals("ctx", ex.getMessage());
        }

        @Test
        @DisplayName("report(ctx, null method, ...) throws NullPointerException with message 'attemptedMethod'")
        void reportNullMethodThrowsNpe() {
            NullPointerException ex = assertThrows(NullPointerException.class, () -> reporter()
                    .report(emptyCtx(), null, Optional.empty(), Optional.empty(), "REASON", Map.of()));
            assertEquals("attemptedMethod", ex.getMessage());
        }

        @Test
        @DisplayName("report with null credentialId throws NullPointerException with message 'credentialId'")
        void reportNullCredentialIdThrowsNpe() {
            NullPointerException ex = assertThrows(NullPointerException.class, () -> reporter()
                    .report(emptyCtx(), DefaultAuthMethod.jwt(), null, Optional.empty(), "REASON", Map.of()));
            assertEquals("credentialId", ex.getMessage());
        }

        @Test
        @DisplayName(
                "report with null verificationSource throws NullPointerException with message 'verificationSource'")
        void reportNullVerificationSourceThrowsNpe() {
            NullPointerException ex = assertThrows(NullPointerException.class, () -> reporter()
                    .report(emptyCtx(), DefaultAuthMethod.jwt(), Optional.empty(), null, "REASON", Map.of()));
            assertEquals("verificationSource", ex.getMessage());
        }

        @Test
        @DisplayName("report with null reasonCode throws NullPointerException with message 'reasonCode'")
        void reportNullReasonCodeThrowsNpe() {
            NullPointerException ex = assertThrows(NullPointerException.class, () -> reporter()
                    .report(emptyCtx(), DefaultAuthMethod.jwt(), Optional.empty(), Optional.empty(), null, Map.of()));
            assertEquals("reasonCode", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("report() — absent CorrelationContext")
    class AbsentCorrelationContext {

        @Test
        @DisplayName("report throws IllegalStateException when CorrelationContext is not bound")
        void reportThrowsWhenCorrelationNotBound() {
            ContextHolder holder = emptyHolder();
            RoutingContext ctx = stubContext(new HashMap<>());
            DefaultCredentialRejectionReporter reporter = new DefaultCredentialRejectionReporter(holder, emitter);

            assertThrows(
                    IllegalStateException.class,
                    () -> reporter.report(
                            ctx, DefaultAuthMethod.jwt(), Optional.empty(), Optional.empty(), "REASON", Map.of()),
                    "must throw IllegalStateException when CorrelationContext is not bound");
            assertTrue(observer.rejections.isEmpty(), "no event must be emitted when correlation is missing");
        }
    }
}
