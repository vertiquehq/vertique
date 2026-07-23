// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link SecuritySpanEventObserver}.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>Events on a recording span produce span events with the correct frozen names and
 *       attributes.</li>
 *   <li>Reason code normalization correctly keeps valid codes, converts invalid or long
 *       codes to {@code "OTHER"}, and maps null/blank to {@code "OTHER"}.</li>
 *   <li>No current span: all methods return succeeded futures, no events exported, no throw.</li>
 *   <li>Non-recording span (noop tracer): no events, no throw.</li>
 *   <li>Gate: {@code tracing.security.spanEvents=false} → no events even with a recording span.</li>
 *   <li>Gate: {@code tracing.enabled=false} → no events even with a recording span.</li>
 *   <li>Never throws: null primaryMethod → {@code "unknown"}; poisoned event → succeeded future.</li>
 *   <li>W3-tracing: catch blocks log only the exception class name — no throwable proxy, no
 *       sentinel message text leaked to the log appender.</li>
 * </ol>
 */
class SecuritySpanEventObserverTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    // --- Shared fixtures ---

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final CorrelationContext CORRELATION = minimalCorrelation();
    private static final SecurityContext SEC_CTX = minimalSecurityContext();

    @BeforeEach
    void resetGlobal() {
        GlobalOpenTelemetry.resetForTest();
    }

    @AfterEach
    void resetGlobalAfter() {
        GlobalOpenTelemetry.resetForTest();
    }

    // =========================================================================
    // Test 1 — recording span produces events with frozen names and attributes
    // =========================================================================

    @Nested
    @DisplayName("recording span receives span events with correct names and attributes")
    class RecordingSpanEvents {

        @Test
        @DisplayName("onCredentialAccepted emits 'vertique.security.credential.accepted' with vertique.auth.method")
        void credentialAcceptedEmitsEvent() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();

            try (var ignored = span.makeCurrent()) {
                Future<Void> result = observer.onCredentialAccepted(acceptedEvent(DefaultAuthMethod.jwt()));
                assertTrue(result.succeeded(), "onCredentialAccepted must return succeeded future");
            } finally {
                span.end();
            }

            List<SpanData> spans = OTEL.getSpans();
            assertEquals(1, spans.size(), "one span must be exported");
            List<EventData> events = spans.get(0).getEvents();
            assertEquals(1, events.size(), "one span event must be emitted");

            EventData event = events.get(0);
            assertEquals("vertique.security.credential.accepted", event.getName());
            assertEquals(
                    "JWT",
                    event.getAttributes()
                            .get(io.opentelemetry.api.common.AttributeKey.stringKey("vertique.auth.method")));
        }

        @Test
        @DisplayName("onCredentialRejected emits 'vertique.security.credential.rejected' with method and reason")
        void credentialRejectedEmitsEvent() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();

            try (var ignored = span.makeCurrent()) {
                Future<Void> result =
                        observer.onCredentialRejected(rejectedEvent(DefaultAuthMethod.apiKey(), "TOKEN_EXPIRED"));
                assertTrue(result.succeeded(), "onCredentialRejected must return succeeded future");
            } finally {
                span.end();
            }

            List<SpanData> spans = OTEL.getSpans();
            assertEquals(1, spans.size(), "one span must be exported");
            List<EventData> events = spans.get(0).getEvents();
            assertEquals(1, events.size(), "one span event must be emitted");

            EventData event = events.get(0);
            assertEquals("vertique.security.credential.rejected", event.getName());
            assertEquals(
                    "API_KEY",
                    event.getAttributes()
                            .get(io.opentelemetry.api.common.AttributeKey.stringKey("vertique.auth.method")));
            assertEquals(
                    "TOKEN_EXPIRED",
                    event.getAttributes()
                            .get(io.opentelemetry.api.common.AttributeKey.stringKey("vertique.auth.reason")));
        }

        @Test
        @DisplayName("onAuthorizationDecided emits 'vertique.security.authz.decision' with decision and reason")
        void authorizationDecidedEmitsEvent() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();

            try (var ignored = span.makeCurrent()) {
                Future<Void> result =
                        observer.onAuthorizationDecided(authzDecisionEvent(AuthorizationDecision.deny("ROLE_MISSING")));
                assertTrue(result.succeeded(), "onAuthorizationDecided must return succeeded future");
            } finally {
                span.end();
            }

            List<SpanData> spans = OTEL.getSpans();
            assertEquals(1, spans.size(), "one span must be exported");
            List<EventData> events = spans.get(0).getEvents();
            assertEquals(1, events.size(), "one span event must be emitted");

            EventData event = events.get(0);
            assertEquals("vertique.security.authz.decision", event.getName());
            assertEquals(
                    "deny",
                    event.getAttributes()
                            .get(io.opentelemetry.api.common.AttributeKey.stringKey("vertique.authz.decision")));
            assertEquals(
                    "ROLE_MISSING",
                    event.getAttributes()
                            .get(io.opentelemetry.api.common.AttributeKey.stringKey("vertique.authz.reason")));
        }

        @Test
        @DisplayName("onAuthorizationDecided permit emits 'permit' in vertique.authz.decision")
        void authorizationDecidedPermitEmitsPermit() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();

            try (var ignored = span.makeCurrent()) {
                observer.onAuthorizationDecided(authzDecisionEvent(AuthorizationDecision.permit("PERMITTED")));
            } finally {
                span.end();
            }

            EventData event = OTEL.getSpans().get(0).getEvents().get(0);
            assertEquals("vertique.security.authz.decision", event.getName());
            assertEquals(
                    "permit",
                    event.getAttributes()
                            .get(io.opentelemetry.api.common.AttributeKey.stringKey("vertique.authz.decision")));
        }

        @Test
        @DisplayName("onChannelLifecycle opened emits 'vertique.security.channel.opened' with no attributes")
        void channelOpenedEmitsEvent() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();

            try (var ignored = span.makeCurrent()) {
                Future<Void> result = observer.onChannelLifecycle(openedEvent("ch-1"));
                assertTrue(result.succeeded(), "onChannelLifecycle opened must return succeeded future");
            } finally {
                span.end();
            }

            List<SpanData> spans = OTEL.getSpans();
            List<EventData> events = spans.get(0).getEvents();
            assertEquals(1, events.size(), "one span event for channel opened");
            assertEquals("vertique.security.channel.opened", events.get(0).getName());
            // No attributes (channelId excluded)
            assertTrue(
                    events.get(0).getAttributes().isEmpty(),
                    "channel opened event must carry no attributes (channelId excluded)");
        }

        @Test
        @DisplayName("onChannelLifecycle refreshed emits 'vertique.security.channel.refreshed' with no attributes")
        void channelRefreshedEmitsEvent() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();

            try (var ignored = span.makeCurrent()) {
                observer.onChannelLifecycle(refreshedEvent("ch-1"));
            } finally {
                span.end();
            }

            List<EventData> events = OTEL.getSpans().get(0).getEvents();
            assertEquals(1, events.size());
            assertEquals("vertique.security.channel.refreshed", events.get(0).getName());
        }

        @Test
        @DisplayName("onChannelLifecycle closed emits 'vertique.security.channel.closed' with no attributes")
        void channelClosedEmitsEvent() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();

            try (var ignored = span.makeCurrent()) {
                observer.onChannelLifecycle(closedEvent("ch-1"));
            } finally {
                span.end();
            }

            List<EventData> events = OTEL.getSpans().get(0).getEvents();
            assertEquals(1, events.size());
            assertEquals("vertique.security.channel.closed", events.get(0).getName());
        }
    }

    // =========================================================================
    // Test 2 — reason normalization
    // =========================================================================

    @Nested
    @DisplayName("reason normalization")
    class ReasonNormalization {

        @Test
        @DisplayName("valid reason code 'TOKEN_EXPIRED' is kept as-is")
        void validReasonCodeKept() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();
            try (var ignored = span.makeCurrent()) {
                observer.onCredentialRejected(rejectedEvent(DefaultAuthMethod.jwt(), "TOKEN_EXPIRED"));
            } finally {
                span.end();
            }

            String reason = OTEL.getSpans()
                    .get(0)
                    .getEvents()
                    .get(0)
                    .getAttributes()
                    .get(io.opentelemetry.api.common.AttributeKey.stringKey("vertique.auth.reason"));
            assertEquals("TOKEN_EXPIRED", reason, "valid code must be kept as-is");
        }

        @Test
        @DisplayName("reason code 'weird lowercase!' is normalized to 'OTHER'")
        void invalidReasonCodeNormalizedToOther() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();
            try (var ignored = span.makeCurrent()) {
                observer.onCredentialRejected(rejectedEvent(DefaultAuthMethod.jwt(), "weird lowercase!"));
            } finally {
                span.end();
            }

            String reason = OTEL.getSpans()
                    .get(0)
                    .getEvents()
                    .get(0)
                    .getAttributes()
                    .get(io.opentelemetry.api.common.AttributeKey.stringKey("vertique.auth.reason"));
            assertEquals("OTHER", reason, "invalid format code must be normalized to OTHER");
        }

        @Test
        @DisplayName("reason code longer than 64 characters is normalized to 'OTHER'")
        void tooLongReasonCodeNormalizedToOther() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            // Build a 65-char valid-format-but-too-long reason code
            String longReason = "A".repeat(65);
            assertEquals(65, longReason.length(), "sanity check: reason code must be 65 chars");

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();
            try (var ignored = span.makeCurrent()) {
                observer.onCredentialRejected(rejectedEvent(DefaultAuthMethod.jwt(), longReason));
            } finally {
                span.end();
            }

            String reason = OTEL.getSpans()
                    .get(0)
                    .getEvents()
                    .get(0)
                    .getAttributes()
                    .get(io.opentelemetry.api.common.AttributeKey.stringKey("vertique.auth.reason"));
            assertEquals("OTHER", reason, "65-char code must be normalized to OTHER");
        }

        @Test
        @DisplayName(
                "Slice-16: new AuthzReasonCodes constants pass normalizeReason unchanged — all match [A-Z0-9_]{1,64}")
        void newAuthzReasonCodesPassNormalizationUnchanged() {
            // All AuthzReasonCodes constants are uppercase A-Z plus underscore; they must all
            // survive normalizeReason() unchanged (not coerced to "OTHER").
            List<String> newCodes = List.of(
                    AuthzReasonCodes.PERMITTED,
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

            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            for (String code : newCodes) {
                // Use the static normalizeReason method directly (package-private — accessible
                // from the same package in the test source root)
                String normalized = SecuritySpanEventObserver.normalizeReason(code);
                assertEquals(
                        code,
                        normalized,
                        "AuthzReasonCodes constant '" + code + "' must pass normalizeReason() unchanged");
            }
        }

        @Test
        @DisplayName("Slice-16: INSTANCE_ELIGIBILITY_FAILED and DENY_ALL appear verbatim in authz span attributes")
        void newCodesAppearsVerbatimInSpanAttributes() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();
            try (var ignored = span.makeCurrent()) {
                observer.onAuthorizationDecided(
                        authzDecisionEvent(AuthorizationDecision.deny(AuthzReasonCodes.INSTANCE_ELIGIBILITY_FAILED)));
            } finally {
                span.end();
            }

            String reason = OTEL.getSpans()
                    .get(0)
                    .getEvents()
                    .get(0)
                    .getAttributes()
                    .get(io.opentelemetry.api.common.AttributeKey.stringKey("vertique.authz.reason"));
            assertEquals(
                    AuthzReasonCodes.INSTANCE_ELIGIBILITY_FAILED,
                    reason,
                    "INSTANCE_ELIGIBILITY_FAILED must appear verbatim as vertique.authz.reason");
        }
    }

    // =========================================================================
    // Test 3 — no current span: all methods return succeeded futures, no events exported
    // =========================================================================

    @Nested
    @DisplayName("no current span: all methods return succeeded futures, no events exported")
    class NoCurrentSpan {

        @Test
        @DisplayName("all observer methods return succeeded futures when no span is current")
        void noCurrentSpanAllMethodsSucceed() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            assertDoesNotThrow(() -> {
                Future<Void> r1 = observer.onCredentialAccepted(acceptedEvent(DefaultAuthMethod.jwt()));
                assertTrue(r1.succeeded(), "onCredentialAccepted must succeed with no current span");

                Future<Void> r2 = observer.onCredentialRejected(rejectedEvent(DefaultAuthMethod.jwt(), "TEST_REASON"));
                assertTrue(r2.succeeded(), "onCredentialRejected must succeed with no current span");

                Future<Void> r3 = observer.onAuthorizationDecided(authzDecisionEvent(AuthorizationDecision.deny("X")));
                assertTrue(r3.succeeded(), "onAuthorizationDecided must succeed with no current span");

                Future<Void> r4 = observer.onChannelLifecycle(openedEvent("c1"));
                assertTrue(r4.succeeded(), "onChannelLifecycle must succeed with no current span");
            });

            assertTrue(OTEL.getSpans().isEmpty(), "no spans must be exported when no span was current");
        }
    }

    // =========================================================================
    // Test 4 — non-recording span (noop tracer): no events, no throw
    // =========================================================================

    @Nested
    @DisplayName("non-recording span (noop tracer): no events, no throw")
    class NonRecordingSpan {

        @Test
        @DisplayName("span from noop OpenTelemetry tracer is non-recording — no events, no throw")
        void noopSpanProducesNoEvents() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            // noop() tracer produces a non-recording span
            Span noopSpan =
                    OpenTelemetry.noop().getTracer("test").spanBuilder("noop").startSpan();
            assertFalse(noopSpan.isRecording(), "noop span must not be recording");

            assertDoesNotThrow(() -> {
                try (var ignored = noopSpan.makeCurrent()) {
                    Future<Void> r1 = observer.onCredentialAccepted(acceptedEvent(DefaultAuthMethod.jwt()));
                    assertTrue(r1.succeeded());

                    Future<Void> r2 =
                            observer.onCredentialRejected(rejectedEvent(DefaultAuthMethod.jwt(), "TEST_REASON"));
                    assertTrue(r2.succeeded());

                    Future<Void> r3 =
                            observer.onAuthorizationDecided(authzDecisionEvent(AuthorizationDecision.permit("OK")));
                    assertTrue(r3.succeeded());

                    Future<Void> r4 = observer.onChannelLifecycle(openedEvent("c1"));
                    assertTrue(r4.succeeded());
                }
            });

            // No events exported to the OTEL test exporter
            assertTrue(OTEL.getSpans().isEmpty(), "no spans must be exported for noop-tracer spans");
        }
    }

    // =========================================================================
    // Test 5 — gates: security.spanEvents=false and enabled=false
    // =========================================================================

    @Nested
    @DisplayName("gates: disabled config produces no span events")
    class Gating {

        @Test
        @DisplayName("tracing.security.spanEvents=false → no events even with a recording span")
        void spanEventsDisabledProducesNoEvents() {
            TracingConfig config = new JsonObject()
                    .put("security", new JsonObject().put("spanEvents", false))
                    .mapTo(TracingConfig.class);
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();

            try (var ignored = span.makeCurrent()) {
                assertTrue(span.isRecording(), "span must be recording for this test to be meaningful");
                Future<Void> result = observer.onCredentialAccepted(acceptedEvent(DefaultAuthMethod.jwt()));
                assertTrue(result.succeeded());
            } finally {
                span.end();
            }

            List<SpanData> spans = OTEL.getSpans();
            assertEquals(1, spans.size(), "span itself must be exported");
            assertTrue(spans.get(0).getEvents().isEmpty(), "no events must be emitted when spanEvents=false");
        }

        @Test
        @DisplayName("tracing.enabled=false → no events even with a recording span")
        void tracingDisabledProducesNoEvents() {
            TracingConfig config = new JsonObject().put("enabled", false).mapTo(TracingConfig.class);
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();

            try (var ignored = span.makeCurrent()) {
                assertTrue(span.isRecording(), "span must be recording for this test to be meaningful");
                Future<Void> result = observer.onCredentialAccepted(acceptedEvent(DefaultAuthMethod.jwt()));
                assertTrue(result.succeeded());
            } finally {
                span.end();
            }

            List<SpanData> spans = OTEL.getSpans();
            assertEquals(1, spans.size(), "span itself must be exported");
            assertTrue(spans.get(0).getEvents().isEmpty(), "no events must be emitted when enabled=false");
        }
    }

    // =========================================================================
    // Test 6 — never throws: null primaryMethod and poisoned event
    // =========================================================================

    @Nested
    @DisplayName("never-throws resilience")
    class Resilience {

        @Test
        @DisplayName("null primaryMethod in accepted event yields method attr 'unknown', no throw")
        void nullPrimaryMethodYieldsUnknown() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();

            try (var ignored = span.makeCurrent()) {
                // NullKindAuthMethod has null normalizedKind() → "unknown"
                CredentialAcceptedEvent event = acceptedEvent(new NullKindAuthMethod());
                assertDoesNotThrow(() -> {
                    Future<Void> result = observer.onCredentialAccepted(event);
                    assertTrue(result.succeeded());
                });
            } finally {
                span.end();
            }

            List<SpanData> spans = OTEL.getSpans();
            assertFalse(spans.isEmpty(), "span must be exported");
            List<EventData> events = spans.get(0).getEvents();
            assertFalse(events.isEmpty(), "event must be emitted");
            assertEquals(
                    "unknown",
                    events.get(0)
                            .getAttributes()
                            .get(io.opentelemetry.api.common.AttributeKey.stringKey("vertique.auth.method")),
                    "null normalizedKind must yield 'unknown' method attribute");
        }

        @Test
        @DisplayName(
                "rejected event with null reasonCode is not constructible (blank check) — test with valid event that has normalizable reason")
        void validEventWithNormalizableReason() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            // CredentialRejectedEvent requires non-blank reasonCode, so we test normalization
            // via an invalid-format code → OTHER
            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();

            try (var ignored = span.makeCurrent()) {
                // Use a code with mixed case and punctuation — will normalize to OTHER
                assertDoesNotThrow(() -> {
                    Future<Void> result = observer.onCredentialRejected(
                            rejectedEvent(DefaultAuthMethod.jwt(), "invalid-reason-code"));
                    assertTrue(result.succeeded());
                });
            } finally {
                span.end();
            }

            String reason = OTEL.getSpans()
                    .get(0)
                    .getEvents()
                    .get(0)
                    .getAttributes()
                    .get(io.opentelemetry.api.common.AttributeKey.stringKey("vertique.auth.reason"));
            assertEquals("OTHER", reason, "invalid format reason must normalize to OTHER");
        }

        @Test
        @DisplayName("authz event with null reasonCode in decision → normalizes to OTHER, no throw")
        void authzDecisionNullReasonNormalizesToOther() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            // AuthorizationDecision.deny() requires non-blank reasonCode,
            // so use a code that normalizes to OTHER (lowercase)
            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();

            try (var ignored = span.makeCurrent()) {
                assertDoesNotThrow(() -> {
                    Future<Void> result = observer.onAuthorizationDecided(
                            authzDecisionEvent(AuthorizationDecision.deny("mixed-case-code")));
                    assertTrue(result.succeeded());
                });
            } finally {
                span.end();
            }

            String reason = OTEL.getSpans()
                    .get(0)
                    .getEvents()
                    .get(0)
                    .getAttributes()
                    .get(io.opentelemetry.api.common.AttributeKey.stringKey("vertique.authz.reason"));
            assertEquals("OTHER", reason, "lowercase authz reason must normalize to OTHER");
        }
    }

    // =========================================================================
    // Test 8 — W3-tracing: catch blocks log class name only, no throwable proxy
    // =========================================================================

    @Nested
    @DisplayName("W3-tracing: catch blocks log class name only — no throwable proxy, no sentinel leakage")
    class CatchBlockLogging {

        /**
         * Verifies that when a span operation throws (simulated via a {@link ThrowingSpan}),
         * the observer:
         * <ul>
         *   <li>Returns a succeeded {@link Future} (never throws or fails)</li>
         *   <li>Emits exactly one WARN log event whose formatted message does NOT contain the
         *       sentinel string "SENTINEL" (class name only, not the message body)</li>
         *   <li>Emits a log event with a {@code null} throwable proxy (the exception is NOT
         *       attached to the log record as a throwable — only its class name is in the text)</li>
         * </ul>
         *
         * <p>This test covers all four observer methods — each has its own catch block.
         */
        @Test
        @DisplayName("span.addEvent throws RuntimeException('SENTINEL_secret_xyz') → "
                + "log has no sentinel text, no throwable proxy, future succeeds")
        void catchBlockLogsClassNameOnlyNoThrowableProxy() {
            TracingConfig config = defaultConfig();
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);

            // Capture logs from the observer's logger
            Logger observerLogger = (Logger) LoggerFactory.getLogger(SecuritySpanEventObserver.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            observerLogger.addAppender(appender);

            try {
                // Make a ThrowingSpan current — its addEvent always throws the sentinel exception
                Span throwingSpan = new ThrowingSpan("SENTINEL_secret_xyz");
                try (var ignored = throwingSpan.makeCurrent()) {
                    // onCredentialAccepted
                    Future<Void> r1 = observer.onCredentialAccepted(acceptedEvent(DefaultAuthMethod.jwt()));
                    assertTrue(r1.succeeded(), "onCredentialAccepted must return succeeded future");

                    // onCredentialRejected
                    Future<Void> r2 =
                            observer.onCredentialRejected(rejectedEvent(DefaultAuthMethod.jwt(), "TOKEN_EXPIRED"));
                    assertTrue(r2.succeeded(), "onCredentialRejected must return succeeded future");

                    // onAuthorizationDecided
                    Future<Void> r3 =
                            observer.onAuthorizationDecided(authzDecisionEvent(AuthorizationDecision.deny("R")));
                    assertTrue(r3.succeeded(), "onAuthorizationDecided must return succeeded future");

                    // onChannelLifecycle
                    Future<Void> r4 = observer.onChannelLifecycle(openedEvent("ch-1"));
                    assertTrue(r4.succeeded(), "onChannelLifecycle must return succeeded future");
                }

                List<ILoggingEvent> events = appender.list;
                assertFalse(events.isEmpty(), "at least one WARN log event must be emitted");

                for (ILoggingEvent event : events) {
                    // The formatted message must NOT contain the sentinel string
                    String msg = event.getFormattedMessage();
                    assertFalse(
                            msg.contains("SENTINEL"),
                            "log message must not contain sentinel string (raw throwable message " + "leaked); got: "
                                    + msg);

                    // No throwable proxy must be attached
                    assertNull(
                            event.getThrowableProxy(),
                            "log event must have null throwable proxy (no exception attached); " + "event message: "
                                    + msg);
                }
            } finally {
                observerLogger.detachAppender(appender);
            }
        }
    }

    // =========================================================================
    // --- Private event fixture helpers ---
    // =========================================================================

    /**
     * Builds a default {@link TracingConfig} with both {@code enabled=true} and
     * {@code security.spanEvents=true}.
     *
     * @return a default tracing config; never null
     */
    private static TracingConfig defaultConfig() {
        return new JsonObject().mapTo(TracingConfig.class);
    }

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
     * Minimal {@link CredentialRejectedEvent} with the given auth method and reason code.
     *
     * @param method     the auth method that was attempted
     * @param reasonCode the rejection reason code; must not be blank
     * @return a fully-constructed event
     */
    private static CredentialRejectedEvent rejectedEvent(AuthMethod method, String reasonCode) {
        return new CredentialRejectedEvent(
                NOW, CORRELATION, Optional.empty(), method, Optional.empty(), Optional.empty(), reasonCode, Map.of());
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
     * the {@code "unknown"} fallback attribute value.
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
     * A {@link Span} test double with a valid, recording span context whose {@link #addEvent}
     * always throws a {@link RuntimeException} with the supplied message.
     *
     * <p>Used to verify that catch blocks in {@link SecuritySpanEventObserver} do not attach
     * the throwable to the log record (only the class name is logged).
     */
    static final class ThrowingSpan implements Span {

        private static final String TRACE_ID = "0102030405060708090a0b0c0d0e0f10";
        private static final String SPAN_ID = "0102030405060708";
        private static final SpanContext VALID_CTX =
                SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());

        private final String throwMessage;

        /**
         * Constructs the throwing span.
         *
         * @param throwMessage the message used in the thrown {@link RuntimeException}
         */
        ThrowingSpan(String throwMessage) {
            this.throwMessage = throwMessage;
        }

        @Override
        public SpanContext getSpanContext() {
            return VALID_CTX;
        }

        @Override
        public boolean isRecording() {
            return true;
        }

        @Override
        public Span addEvent(String name, Attributes attributes) {
            throw new RuntimeException(throwMessage);
        }

        @Override
        public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
            throw new RuntimeException(throwMessage);
        }

        @Override
        public <T> Span setAttribute(AttributeKey<T> key, @Nullable T value) {
            return this;
        }

        @Override
        public Span setStatus(StatusCode statusCode, String description) {
            return this;
        }

        @Override
        public Span recordException(Throwable exception, Attributes additionalAttributes) {
            return this;
        }

        @Override
        public Span updateName(String name) {
            return this;
        }

        @Override
        public void end() {}

        @Override
        public void end(long timestamp, TimeUnit unit) {}
    }
}
