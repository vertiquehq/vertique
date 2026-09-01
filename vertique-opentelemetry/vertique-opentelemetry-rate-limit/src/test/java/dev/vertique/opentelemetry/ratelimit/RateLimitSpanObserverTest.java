// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.ratelimit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.ratelimit.RateLimitAlgorithmType;
import dev.vertique.ratelimit.RateLimitFailureCode;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitOutcome;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.ratelimit.spi.event.RateLimitDecisionCompleted;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contract tests for the isolated rate-limit OpenTelemetry adapter (contracts/observability.md,
 * "OpenTelemetry adapter").
 */
class RateLimitSpanObserverTest {

    private static final String POLICY = "search-quota";
    private static final String POLICY_REVISION = "r1";
    private static final long BACKEND_LATENCY_NANOS = 2_500_000L;

    private static final List<RateLimitOutcome> OUTCOMES = List.of(
            RateLimitOutcome.PERMITTED,
            RateLimitOutcome.QUOTA_EXCEEDED,
            RateLimitOutcome.BACKEND_FAILURE_OPEN,
            RateLimitOutcome.BACKEND_FAILURE_CLOSED,
            RateLimitOutcome.DISABLED);

    private static final Set<AttributeKey<?>> ALLOWED_ATTRIBUTE_KEYS = Set.of(
            AttributeKey.stringKey("policy"),
            AttributeKey.stringKey("outcome"),
            AttributeKey.stringKey("mode"),
            AttributeKey.longKey("duration_ms"));

    private static final Pattern IP_SHAPED = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private static final String ROW_ATTRIBUTES = "shouldEmitOneSpanPerEventWithFrozenAttributes";
    private static final String ROW_ERROR_STATUS = "shouldSetErrorStatusOnlyForBackendFailureOutcomes";
    private static final String ROW_REDACTION = "shouldNeverSetKeyIdentityOrIpAttribute";

    private static Stream<String> rows() {
        return Stream.of(ROW_ATTRIBUTES, ROW_ERROR_STATUS, ROW_REDACTION);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("enforces the frozen vertique.ratelimit.decision span mapping and error status")
    void shouldEnforceFrozenSpanMappingAndErrorStatus(String row) {
        switch (row) {
            case ROW_ATTRIBUTES -> shouldEmitOneSpanPerEventWithFrozenAttributes();
            case ROW_ERROR_STATUS -> shouldSetErrorStatusOnlyForBackendFailureOutcomes();
            case ROW_REDACTION -> shouldNeverSetKeyIdentityOrIpAttribute();
            default -> fail("unknown row: " + row);
        }
    }

    // --- Row: shouldEmitOneSpanPerEventWithFrozenAttributes ---

    private void shouldEmitOneSpanPerEventWithFrozenAttributes() {
        TracingFixture fixture = newFixture();
        try {
            RateLimitSpanObserver observer = new RateLimitSpanObserver(fixture.tracer());

            for (RateLimitOutcome outcome : OUTCOMES) {
                observer.onEvent(decision(outcome));
            }

            List<SpanData> spans = fixture.exporter().getFinishedSpanItems();
            assertEquals(OUTCOMES.size(), spans.size(), "exactly one span per event");
            for (int i = 0; i < OUTCOMES.size(); i++) {
                SpanData span = spans.get(i);
                RateLimitOutcome outcome = OUTCOMES.get(i);
                assertEquals(RateLimitSpanObserver.SPAN_NAME, span.getName());
                assertEquals(
                        POLICY, span.getAttributes().get(AttributeKey.stringKey("policy")), "policy for " + outcome);
                assertEquals(
                        outcome.name(),
                        span.getAttributes().get(AttributeKey.stringKey("outcome")),
                        "outcome for " + outcome);
                assertEquals(
                        RateLimitMode.LOCAL.name(),
                        span.getAttributes().get(AttributeKey.stringKey("mode")),
                        "mode for " + outcome);
                assertEquals(
                        2L,
                        span.getAttributes().get(AttributeKey.longKey("duration_ms")),
                        "2_500_000ns must convert to 2ms for outcome " + outcome);
            }
        } finally {
            fixture.telemetry().close();
        }
    }

    // --- Row: shouldSetErrorStatusOnlyForBackendFailureOutcomes ---

    private void shouldSetErrorStatusOnlyForBackendFailureOutcomes() {
        TracingFixture fixture = newFixture();
        try {
            RateLimitSpanObserver observer = new RateLimitSpanObserver(fixture.tracer());

            for (RateLimitOutcome outcome : OUTCOMES) {
                observer.onEvent(decision(outcome));
            }

            List<SpanData> spans = fixture.exporter().getFinishedSpanItems();
            assertEquals(OUTCOMES.size(), spans.size());
            for (int i = 0; i < OUTCOMES.size(); i++) {
                SpanData span = spans.get(i);
                RateLimitOutcome outcome = OUTCOMES.get(i);
                StatusCode expected = isBackendFailure(outcome) ? StatusCode.ERROR : StatusCode.UNSET;
                assertEquals(expected, span.getStatus().getStatusCode(), "status code for outcome " + outcome);
            }
        } finally {
            fixture.telemetry().close();
        }
    }

    // --- Row: shouldNeverSetKeyIdentityOrIpAttribute ---

    private void shouldNeverSetKeyIdentityOrIpAttribute() {
        TracingFixture fixture = newFixture();
        try {
            RateLimitSpanObserver observer = new RateLimitSpanObserver(fixture.tracer());

            for (RateLimitOutcome outcome : OUTCOMES) {
                observer.onEvent(decision(outcome));
            }

            List<SpanData> spans = fixture.exporter().getFinishedSpanItems();
            assertFalse(spans.isEmpty(), "fixture must actually record spans for this check to be meaningful");
            for (SpanData span : spans) {
                assertEquals(
                        ALLOWED_ATTRIBUTE_KEYS,
                        span.getAttributes().asMap().keySet(),
                        "unexpected attribute key set on " + span.getName());
                span.getAttributes().forEach((key, value) -> {
                    String stringValue = String.valueOf(value);
                    assertFalse(
                            IP_SHAPED.matcher(stringValue).matches(),
                            "attribute value must never be IP-shaped (" + key + "=" + stringValue + ")");
                    assertFalse(
                            stringValue.contains(":"),
                            "attribute value must never contain ':' — the RateLimitKey canonical-encoding separator ("
                                    + key + "=" + stringValue + ")");
                });
            }
        } finally {
            fixture.telemetry().close();
        }
    }

    // --- TP-002 ---

    @Test
    @DisplayName("a tracer failure never escapes the observer")
    void shouldIsolateTracerFailureFromAdmission() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.spanBuilder(RateLimitSpanObserver.SPAN_NAME)).thenThrow(new AssertionError("tracer failure"));
        RateLimitSpanObserver observer = new RateLimitSpanObserver(tracer);

        assertDoesNotThrow(() -> observer.onEvent(decision(RateLimitOutcome.BACKEND_FAILURE_OPEN)));
    }

    // --- Supporting verification ---

    @Test
    @DisplayName("Dagger contributes exactly one observer to the RateLimitObserver set")
    void contributesRateLimitSpanObserverToTheRateLimitObserverSet() {
        Set<RateLimitObserver> observers =
                DaggerRateLimitSpanObserverTest_TestComponent.create().rateLimitObservers();

        assertEquals(1, observers.size());
        assertInstanceOf(RateLimitSpanObserver.class, observers.iterator().next());
    }

    // --- Shared fixtures ---

    private static TracingFixture newFixture() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk telemetry =
                OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        return new TracingFixture(telemetry, telemetry.getTracer("test"), exporter);
    }

    private record TracingFixture(OpenTelemetrySdk telemetry, Tracer tracer, InMemorySpanExporter exporter) {}

    private static RateLimitDecisionCompleted decision(RateLimitOutcome outcome) {
        return new RateLimitDecisionCompleted(
                POLICY,
                POLICY_REVISION,
                RateLimitMode.LOCAL,
                RateLimitAlgorithmType.TOKEN_BUCKET,
                outcome,
                1L,
                10L,
                OptionalLong.of(9L),
                outcome == RateLimitOutcome.QUOTA_EXCEEDED ? Optional.of(Duration.ofSeconds(1)) : Optional.empty(),
                Optional.of(Duration.ofSeconds(30)),
                failureCodeFor(outcome),
                BACKEND_LATENCY_NANOS);
    }

    private static Optional<RateLimitFailureCode> failureCodeFor(RateLimitOutcome outcome) {
        return switch (outcome) {
            case BACKEND_FAILURE_OPEN -> Optional.of(RateLimitFailureCode.TIMEOUT);
            case BACKEND_FAILURE_CLOSED -> Optional.of(RateLimitFailureCode.UNAVAILABLE);
            default -> Optional.empty();
        };
    }

    private static boolean isBackendFailure(RateLimitOutcome outcome) {
        return outcome == RateLimitOutcome.BACKEND_FAILURE_OPEN || outcome == RateLimitOutcome.BACKEND_FAILURE_CLOSED;
    }

    @Singleton
    @Component(modules = {OpenTelemetryRateLimitModule.class, TestModule.class})
    interface TestComponent {
        Set<RateLimitObserver> rateLimitObservers();
    }

    @Module
    static final class TestModule {

        private TestModule() {}

        @Provides
        @Singleton
        static Tracer tracer() {
            return OpenTelemetry.noop().getTracer("test");
        }
    }
}
