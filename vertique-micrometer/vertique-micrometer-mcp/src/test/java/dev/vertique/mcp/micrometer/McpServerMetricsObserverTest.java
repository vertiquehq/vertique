// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.TraceReference;
import dev.vertique.mcp.lifecycle.McpAuthorizationSummary;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.tool.McpToolDescriptor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the T021 Micrometer MCP observer contract (contract §4.10): the frozen request timer,
 * active gauge, validation-failures counter, and tool-call timer, with bounded low-cardinality
 * dimensions and no payload, identity, or trace-id label.
 *
 * <p>Each row constructs its own {@link SimpleMeterRegistry} and {@link McpServerMetricsObserver} so
 * rows never share registry state.
 *
 * <p>Sensitivity: {@link #UNRESOLVED_TOOL_ATTEMPT} is the tool-name sensitivity target for {@link
 * #DIMENSIONS_BOUNDED_ROW}. Swapping it for a 512-character value must leave every assertion in that
 * row unchanged — {@link #resolveToolName(String)} mirrors production dispatch (see {@link
 * McpRequestTerminalEvent}'s javadoc on its placeholder-descriptor decision point): an unregistered
 * candidate collapses to {@link McpRequestTerminalEvent#UNKNOWN_TOOL_NAME} regardless of its length
 * or shape, so the distinct-dimension count stays bounded while the fallback-dimension count still
 * moves 0→1 on the first unresolved attempt.
 */
class McpServerMetricsObserverTest {

    private static final String EVERY_OUTCOME_ROW = "shouldRecordEveryTerminalOutcome";
    private static final String DIMENSIONS_BOUNDED_ROW = "shouldKeepDimensionsBounded";
    private static final String NO_IDENTITY_ROW = "shouldNeverTagPayloadIdentityOrTraceIds";
    private static final String ACTIVE_GAUGE_ROW = "shouldTrackTheActiveRequestGaugeSymmetrically";

    private static final Instant STARTED_AT = Instant.parse("2026-08-21T00:00:00Z");
    private static final Instant TERMINAL_AT = STARTED_AT.plusMillis(25);
    private static final Instant COMPLETED_AT = TERMINAL_AT.plusMillis(5);

    private static final String KNOWN_TOOL = "hello";

    /** Sensitivity target for {@link #DIMENSIONS_BOUNDED_ROW}; see the class javadoc. */
    private static final String UNRESOLVED_TOOL_ATTEMPT = "unregistered-tool";

    private static Stream<String> rows() {
        return Stream.of(EVERY_OUTCOME_ROW, DIMENSIONS_BOUNDED_ROW, NO_IDENTITY_ROW, ACTIVE_GAUGE_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("T021 observer matrix: every outcome, bounded dimensions, no identity/trace tags, symmetric gauge")
    void shouldEnforceT021ContractMatrix(String row) {
        switch (row) {
            case EVERY_OUTCOME_ROW -> shouldRecordEveryTerminalOutcome();
            case DIMENSIONS_BOUNDED_ROW -> shouldKeepDimensionsBounded();
            case NO_IDENTITY_ROW -> shouldNeverTagPayloadIdentityOrTraceIds();
            case ACTIVE_GAUGE_ROW -> shouldTrackTheActiveRequestGaugeSymmetrically();
            default -> fail("unknown T021 observer row: " + row);
        }
    }

    // --- shouldRecordEveryTerminalOutcome ---

    private void shouldRecordEveryTerminalOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        McpServerMetricsObserver observer = new McpServerMetricsObserver(registry, Optional.empty());

        // Given: one completed request per outcome, all for the registered tool "hello", each
        // wrapped in a different transport-completion shape so `transport.outcome` varies too.
        McpRequestTerminalEvent success = terminal(McpOutcome.SUCCESS, KNOWN_TOOL, McpErrorType.NONE);
        McpRequestTerminalEvent toolError = terminal(McpOutcome.TOOL_ERROR, KNOWN_TOOL, McpErrorType.INPUT_VALIDATION);
        McpRequestTerminalEvent rejected = terminal(McpOutcome.REJECTED, KNOWN_TOOL, McpErrorType.AUTHORIZATION);
        McpRequestTerminalEvent failedEvent = terminal(McpOutcome.FAILED, KNOWN_TOOL, McpErrorType.INTERNAL);
        McpRequestTerminalEvent cancelled = terminal(McpOutcome.CANCELLED, KNOWN_TOOL, McpErrorType.TRANSPORT);

        openAndComplete(observer, success, McpRequestCompletedEvent.written(success, COMPLETED_AT));
        openAndComplete(observer, toolError, McpRequestCompletedEvent.written(toolError, COMPLETED_AT));
        openAndComplete(observer, rejected, McpRequestCompletedEvent.disconnected(rejected, COMPLETED_AT, false));
        openAndComplete(observer, failedEvent, McpRequestCompletedEvent.reset(failedEvent, COMPLETED_AT, false));
        openAndComplete(observer, cancelled, McpRequestCompletedEvent.writeFailed(cancelled, COMPLETED_AT, false));

        // Given (FR-MCP-203): a successful non-tool request whose recognized method class is
        // McpMethod.OTHER — the unrecognized-client-method case the `_OTHER` remap exists for.
        McpRequestTerminalEvent otherMethod = McpRequestTerminalEvent.success(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.OTHER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                200,
                null,
                null,
                null);
        openAndComplete(observer, otherMethod, McpRequestCompletedEvent.written(otherMethod, COMPLETED_AT));

        // DECISIVE: every terminal outcome recorded the exact frozen request-timer name with the
        // exact tag set the contract freezes — not merely that some counter moved.
        assertExactTimer(
                registry,
                McpServerMetricsObserver.REQUESTS_TIMER,
                Tags.of(
                        McpServerMetricsObserver.TAG_METHOD, "TOOLS_CALL",
                        McpServerMetricsObserver.TAG_OUTCOME, "SUCCESS",
                        McpServerMetricsObserver.TAG_ERROR_TYPE, "NONE",
                        McpServerMetricsObserver.TAG_RESULT_TYPE, "COMPLETE",
                        McpServerMetricsObserver.TAG_TRANSPORT_OUTCOME, "WRITTEN"),
                1);
        assertExactTimer(
                registry,
                McpServerMetricsObserver.REQUESTS_TIMER,
                Tags.of(
                        McpServerMetricsObserver.TAG_METHOD, "TOOLS_CALL",
                        McpServerMetricsObserver.TAG_OUTCOME, "TOOL_ERROR",
                        McpServerMetricsObserver.TAG_ERROR_TYPE, "INPUT_VALIDATION",
                        McpServerMetricsObserver.TAG_RESULT_TYPE, "COMPLETE",
                        McpServerMetricsObserver.TAG_TRANSPORT_OUTCOME, "WRITTEN"),
                1);
        assertExactTimer(
                registry,
                McpServerMetricsObserver.REQUESTS_TIMER,
                Tags.of(
                        McpServerMetricsObserver.TAG_METHOD, "TOOLS_CALL",
                        McpServerMetricsObserver.TAG_OUTCOME, "REJECTED",
                        McpServerMetricsObserver.TAG_ERROR_TYPE, "AUTHORIZATION",
                        McpServerMetricsObserver.TAG_RESULT_TYPE, "NONE",
                        McpServerMetricsObserver.TAG_TRANSPORT_OUTCOME, "DISCONNECTED"),
                1);
        assertExactTimer(
                registry,
                McpServerMetricsObserver.REQUESTS_TIMER,
                Tags.of(
                        McpServerMetricsObserver.TAG_METHOD, "TOOLS_CALL",
                        McpServerMetricsObserver.TAG_OUTCOME, "FAILED",
                        McpServerMetricsObserver.TAG_ERROR_TYPE, "INTERNAL",
                        McpServerMetricsObserver.TAG_RESULT_TYPE, "NONE",
                        McpServerMetricsObserver.TAG_TRANSPORT_OUTCOME, "RESET"),
                1);
        assertExactTimer(
                registry,
                McpServerMetricsObserver.REQUESTS_TIMER,
                Tags.of(
                        McpServerMetricsObserver.TAG_METHOD, "TOOLS_CALL",
                        McpServerMetricsObserver.TAG_OUTCOME, "CANCELLED",
                        McpServerMetricsObserver.TAG_ERROR_TYPE, "TRANSPORT",
                        McpServerMetricsObserver.TAG_RESULT_TYPE, "NONE",
                        McpServerMetricsObserver.TAG_TRANSPORT_OUTCOME, "WRITE_FAILED"),
                1);

        // DECISIVE (FR-MCP-203): an unrecognized client-provided method must be tagged with the
        // bounded `_OTHER` literal, never the raw McpMethod.OTHER enum name or a client string.
        assertExactTimer(
                registry,
                McpServerMetricsObserver.REQUESTS_TIMER,
                Tags.of(
                        McpServerMetricsObserver.TAG_METHOD, "_OTHER",
                        McpServerMetricsObserver.TAG_OUTCOME, "SUCCESS",
                        McpServerMetricsObserver.TAG_ERROR_TYPE, "NONE",
                        McpServerMetricsObserver.TAG_RESULT_TYPE, "COMPLETE",
                        McpServerMetricsObserver.TAG_TRANSPORT_OUTCOME, "WRITTEN"),
                1);

        // DECISIVE: every outcome also recorded the frozen per-tool-call timer.
        assertExactTimer(
                registry,
                McpServerMetricsObserver.TOOL_CALLS_TIMER,
                Tags.of(
                        McpServerMetricsObserver.TAG_TOOL, KNOWN_TOOL,
                        McpServerMetricsObserver.TAG_OUTCOME, "SUCCESS",
                        McpServerMetricsObserver.TAG_ERROR_TYPE, "NONE"),
                1);
        assertExactTimer(
                registry,
                McpServerMetricsObserver.TOOL_CALLS_TIMER,
                Tags.of(
                        McpServerMetricsObserver.TAG_TOOL, KNOWN_TOOL,
                        McpServerMetricsObserver.TAG_OUTCOME, "CANCELLED",
                        McpServerMetricsObserver.TAG_ERROR_TYPE, "TRANSPORT"),
                1);

        // DECISIVE: exactly one row used a validation error.type, so exactly one validation-failures
        // counter exists, with the exact frozen tag set, and no other row created one.
        assertThat(registry.find(McpServerMetricsObserver.VALIDATION_FAILURES_COUNTER)
                        .counters())
                .as("exactly one validation-failures counter must exist — the TOOL_ERROR/INPUT_VALIDATION row")
                .hasSize(1);
        assertExactCounter(
                registry,
                McpServerMetricsObserver.VALIDATION_FAILURES_COUNTER,
                Tags.of(
                        McpServerMetricsObserver.TAG_METHOD, "TOOLS_CALL",
                        McpServerMetricsObserver.TAG_TOOL, KNOWN_TOOL,
                        McpServerMetricsObserver.TAG_ERROR_TYPE, "INPUT_VALIDATION"),
                1.0);
    }

    // --- shouldKeepDimensionsBounded ---

    private void shouldKeepDimensionsBounded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        McpServerMetricsObserver observer = new McpServerMetricsObserver(registry, Optional.empty());

        recordToolCall(observer, KNOWN_TOOL);

        assertThat(distinctToolTagValues(registry))
                .as("only the registered tool's own name is a distinct `tool` tag value so far")
                .containsExactly(KNOWN_TOOL);
        assertThat(fallbackToolMeterCount(registry))
                .as("no request has collapsed to the bounded fallback value yet")
                .isZero();

        // Sensitivity target: swap UNRESOLVED_TOOL_ATTEMPT for a 512-character value — see the class
        // javadoc. resolveToolName bounds it to UNKNOWN_TOOL_NAME regardless of length, so every
        // assertion in this row must be unaffected.
        recordToolCall(observer, UNRESOLVED_TOOL_ATTEMPT);

        // DECISIVE: the fallback-dimension count moves 0→1 on the first unresolved attempt, and the
        // distinct-dimension count grows by exactly one bounded value, never the raw attempted name.
        assertThat(distinctToolTagValues(registry))
                .as("an unresolved attempt adds only the bounded fallback value, never the raw attempted name")
                .containsExactlyInAnyOrder(KNOWN_TOOL, McpRequestTerminalEvent.UNKNOWN_TOOL_NAME);
        assertThat(fallbackToolMeterCount(registry))
                .as("the fallback-dimension count must move from 0 to 1 on the first unresolved attempt")
                .isEqualTo(1);

        // Two more distinct unresolved attempts must not grow either count further.
        recordToolCall(observer, "another-unregistered-tool");
        recordToolCall(observer, "yet-another-unregistered-tool");

        // DECISIVE: the distinct-dimension count stays bounded regardless of how many distinct
        // unresolved names are attempted.
        assertThat(distinctToolTagValues(registry))
                .as("further distinct unresolved attempts must not grow the bounded tool-tag value set")
                .containsExactlyInAnyOrder(KNOWN_TOOL, McpRequestTerminalEvent.UNKNOWN_TOOL_NAME);
        assertThat(fallbackToolMeterCount(registry))
                .as("further distinct unresolved attempts must not create additional fallback-tagged meters")
                .isEqualTo(1);
    }

    // --- shouldNeverTagPayloadIdentityOrTraceIds ---

    private void shouldNeverTagPayloadIdentityOrTraceIds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        McpServerMetricsObserver observer = new McpServerMetricsObserver(registry, Optional.empty());

        // Given: a request whose optional facts carry recognizable identity- and trace-shaped
        // literals a regression could plausibly copy into a tag.
        CorrelationIdentifier requestId =
                new CorrelationIdentifier("REQ-9f3d2c1b-8b7a-4e21-b7f0-affd12345678", "X-Request-Id");
        CorrelationIdentifier correlationId =
                new CorrelationIdentifier("CORR-7e1a4f2d-aaaa-bbbb-cccc-dddddddddddd", "X-Correlation-Id");
        TraceReference trace =
                new TraceReference("TRACE-b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7", "SPAN-1122334455667788", "traceparent");
        CorrelationContextSnapshot correlation = new CorrelationContextSnapshot(
                requestId, correlationId, null, trace, List.of(), null, java.util.Map.of());
        McpAuthorizationSummary authorization =
                new McpAuthorizationSummary(true, "policy-permit", "POLICY-alice-secret-id-42", "v3");

        McpRequestTerminalEvent successWithFacts = McpRequestTerminalEvent.success(
                STARTED_AT, TERMINAL_AT, McpMethod.TOOLS_CALL, KNOWN_TOOL, 200, authorization, null, correlation);
        openAndComplete(observer, successWithFacts, McpRequestCompletedEvent.written(successWithFacts, COMPLETED_AT));

        // DECISIVE: enumerate every tag key and value the registry actually received across every
        // meter this observer touched — not whether the observer's code happens to add one.
        Set<String> allTagKeys = new TreeSet<>();
        Set<String> allTagValues = new TreeSet<>();
        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                allTagKeys.add(tag.getKey());
                allTagValues.add(tag.getValue());
            }
        }

        assertThat(allTagKeys)
                .as("only the six frozen bounded dimensions may ever appear as a tag key")
                .isSubsetOf(Set.of(
                        McpServerMetricsObserver.TAG_METHOD,
                        McpServerMetricsObserver.TAG_TOOL,
                        McpServerMetricsObserver.TAG_OUTCOME,
                        McpServerMetricsObserver.TAG_ERROR_TYPE,
                        McpServerMetricsObserver.TAG_RESULT_TYPE,
                        McpServerMetricsObserver.TAG_TRANSPORT_OUTCOME));

        List<String> forbiddenValues = List.of(
                requestId.value(),
                correlationId.value(),
                trace.traceId(),
                trace.spanId(),
                authorization.policyId(),
                authorization.policyVersion());
        for (String forbidden : forbiddenValues) {
            assertThat(allTagValues.stream().anyMatch(value -> value.contains(forbidden)))
                    .as("no recorded tag value may contain the identity/trace literal '%s'", forbidden)
                    .isFalse();
        }
    }

    // --- shouldTrackTheActiveRequestGaugeSymmetrically ---

    private void shouldTrackTheActiveRequestGaugeSymmetrically() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        McpServerMetricsObserver observer = new McpServerMetricsObserver(registry, Optional.empty());

        McpRequestTerminalEvent success = terminal(McpOutcome.SUCCESS, KNOWN_TOOL, McpErrorType.NONE);
        McpRequestTerminalEvent toolError = terminal(McpOutcome.TOOL_ERROR, KNOWN_TOOL, McpErrorType.HANDLER);
        McpRequestTerminalEvent rejected = terminal(McpOutcome.REJECTED, KNOWN_TOOL, McpErrorType.AUTHENTICATION);
        McpRequestTerminalEvent failedEvent = terminal(McpOutcome.FAILED, KNOWN_TOOL, McpErrorType.SERIALIZATION);
        McpRequestTerminalEvent cancelled = terminal(McpOutcome.CANCELLED, KNOWN_TOOL, McpErrorType.TIMEOUT);

        // Given: five requests opened but not yet completed, including failure and cancellation
        // outcomes, not only success.
        McpRequestObservation successSession = observer.open(STARTED_AT);
        McpRequestObservation toolErrorSession = observer.open(STARTED_AT);
        McpRequestObservation rejectedSession = observer.open(STARTED_AT);
        McpRequestObservation failedSession = observer.open(STARTED_AT);
        McpRequestObservation cancelledSession = observer.open(STARTED_AT);

        assertThat(activeGaugeValue(registry))
                .as("the active gauge must count every opened session before any completes")
                .isEqualTo(5.0);

        // DECISIVE: completing a mix of outcomes — including FAILED and CANCELLED, not only
        // SUCCESS — must decrement the gauge symmetrically back to exactly zero.
        cancelledSession.onCompleted(McpRequestCompletedEvent.writeFailed(cancelled, COMPLETED_AT, false));
        assertThat(activeGaugeValue(registry)).isEqualTo(4.0);

        failedSession.onCompleted(McpRequestCompletedEvent.reset(failedEvent, COMPLETED_AT, false));
        assertThat(activeGaugeValue(registry)).isEqualTo(3.0);

        rejectedSession.onCompleted(McpRequestCompletedEvent.disconnected(rejected, COMPLETED_AT, false));
        assertThat(activeGaugeValue(registry)).isEqualTo(2.0);

        toolErrorSession.onCompleted(McpRequestCompletedEvent.written(toolError, COMPLETED_AT));
        assertThat(activeGaugeValue(registry)).isEqualTo(1.0);

        successSession.onCompleted(McpRequestCompletedEvent.written(success, COMPLETED_AT));
        assertThat(activeGaugeValue(registry))
                .as("the active gauge must return to exactly zero after a mix of outcomes settles, "
                        + "not only after every request succeeds")
                .isEqualTo(0.0);
    }

    // --- Fixtures ---

    /**
     * Builds a terminal event for the given outcome, using the given error type where the outcome's
     * named factory accepts one (ignored for {@link McpOutcome#SUCCESS}, which has none).
     */
    private static McpRequestTerminalEvent terminal(McpOutcome outcome, String toolName, McpErrorType errorType) {
        return switch (outcome) {
            case SUCCESS ->
                McpRequestTerminalEvent.success(
                        STARTED_AT, TERMINAL_AT, McpMethod.TOOLS_CALL, toolName, 200, null, null, null);
            case TOOL_ERROR ->
                McpRequestTerminalEvent.toolError(
                        STARTED_AT, TERMINAL_AT, McpMethod.TOOLS_CALL, toolName, errorType, 200, null, null, null);
            case REJECTED ->
                McpRequestTerminalEvent.rejected(
                        STARTED_AT, TERMINAL_AT, McpMethod.TOOLS_CALL, toolName, errorType, 0, null, null, null, null);
            case FAILED ->
                McpRequestTerminalEvent.failed(
                        STARTED_AT, TERMINAL_AT, McpMethod.TOOLS_CALL, toolName, errorType, 0, null, null, null, null);
            case CANCELLED ->
                McpRequestTerminalEvent.cancelled(
                        STARTED_AT, TERMINAL_AT, McpMethod.TOOLS_CALL, toolName, errorType, 0, null, null, null, null);
        };
    }

    /**
     * Resolves a client-requested tool name the way production dispatch does: a registered name
     * passes through unchanged, and everything else — regardless of length or shape — collapses to
     * {@link McpRequestTerminalEvent#UNKNOWN_TOOL_NAME}. Mirrors {@code McpToolDescriptor}'s own
     * grammar check so an over-length candidate is rejected the same way a real unresolved {@code
     * tools/call} name is, without ever attempting to construct a terminal event with it.
     */
    private static String resolveToolName(String requested) {
        return KNOWN_TOOL.equals(requested) && McpToolDescriptor.isValidName(requested)
                ? requested
                : McpRequestTerminalEvent.UNKNOWN_TOOL_NAME;
    }

    private static void recordToolCall(McpServerMetricsObserver observer, String toolNameCandidate) {
        String toolName = resolveToolName(toolNameCandidate);
        McpRequestTerminalEvent terminalEvent = McpRequestTerminalEvent.failed(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.TOOLS_CALL,
                toolName,
                McpErrorType.HANDLER,
                0,
                null,
                null,
                null,
                null);
        openAndComplete(observer, terminalEvent, McpRequestCompletedEvent.written(terminalEvent, COMPLETED_AT));
    }

    private static void openAndComplete(
            McpServerMetricsObserver observer, McpRequestTerminalEvent terminalEvent, McpRequestCompletedEvent event) {
        McpRequestObservation session = observer.open(terminalEvent.startedAt());
        session.onCompleted(event);
    }

    private static Set<String> distinctToolTagValues(MeterRegistry registry) {
        return registry.find(McpServerMetricsObserver.TOOL_CALLS_TIMER).timers().stream()
                .map(timer -> timer.getId().getTag(McpServerMetricsObserver.TAG_TOOL))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static long fallbackToolMeterCount(MeterRegistry registry) {
        return registry.find(McpServerMetricsObserver.TOOL_CALLS_TIMER)
                .tag(McpServerMetricsObserver.TAG_TOOL, McpRequestTerminalEvent.UNKNOWN_TOOL_NAME)
                .timers()
                .size();
    }

    private static double activeGaugeValue(MeterRegistry registry) {
        return registry.find(McpServerMetricsObserver.ACTIVE_GAUGE).gauge().value();
    }

    /** Asserts exactly one timer named {@code name} carries exactly {@code expectedTags} — no more, no fewer. */
    private static void assertExactTimer(MeterRegistry registry, String name, Tags expectedTags, long expectedCount) {
        Set<Tag> expected = asSet(expectedTags);
        List<Timer> matches = registry.find(name).timers().stream()
                .filter(timer -> asSet(timer.getId().getTags()).equals(expected))
                .toList();
        assertThat(matches)
                .as("exactly one timer named '%s' with tag set %s", name, expected)
                .hasSize(1);
        assertThat(matches.get(0).count())
                .as("timer '%s' with tag set %s must have recorded exactly %d sample(s)", name, expected, expectedCount)
                .isEqualTo(expectedCount);
    }

    /** Asserts exactly one counter named {@code name} carries exactly {@code expectedTags} — no more, no fewer. */
    private static void assertExactCounter(
            MeterRegistry registry, String name, Tags expectedTags, double expectedCount) {
        Set<Tag> expected = asSet(expectedTags);
        List<Counter> matches = registry.find(name).counters().stream()
                .filter(counter -> asSet(counter.getId().getTags()).equals(expected))
                .toList();
        assertThat(matches)
                .as("exactly one counter named '%s' with tag set %s", name, expected)
                .hasSize(1);
        assertThat(matches.get(0).count())
                .as(
                        "counter '%s' with tag set %s must have recorded exactly %.0f increment(s)",
                        name, expected, expectedCount)
                .isEqualTo(expectedCount);
    }

    private static Set<Tag> asSet(Iterable<Tag> tags) {
        Set<Tag> set = new HashSet<>();
        tags.forEach(set::add);
        return set;
    }
}
