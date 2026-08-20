// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.mcp.interceptor.McpTraceContext;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.SecurityContextSnapshot;
import dev.vertique.security.SecurityIdentity;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the legal terminal and completion state combinations used by the MCP server.
 *
 * <p>The terminal and completion tables below are <em>exhaustive</em>: every
 * {@code outcome × errorType × resultType} combination is enumerated, plus every single-dimension
 * mutation of {@code httpStatus} and {@code protocolErrorCode} away from a legal tuple. Each
 * generated row is classified by {@link #isLegalTerminalTuple} — a direct transcription of the
 * frozen lifecycle invariants — and the predicate itself is anchored by literal, hand-pinned
 * known-legal and known-illegal rows so the matrix is never self-referential.
 *
 * <p>Two invariants are deliberately <em>not</em> enforced by the records and therefore not
 * asserted here as rejections. {@code DISCONNECTED}/{@code RESET} require
 * {@code responseCommitted=false} only <em>before the first write</em>, and "a write already
 * happened" is caller knowledge that {@link McpRequestCompletedEvent} does not carry; both boolean
 * values are therefore legal for those transport outcomes. {@code httpStatus=0} is likewise
 * permitted for {@code REJECTED}/{@code FAILED}/{@code CANCELLED} only when no response could be
 * attempted, which is again a fact outside the record.
 */
class McpLifecycleEventTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-20T00:00:00Z");
    private static final Instant TERMINAL_AT = STARTED_AT.plusMillis(1);
    private static final Instant COMPLETED_AT = TERMINAL_AT.plusMillis(1);
    private static final int PROTOCOL_ERROR_CODE = -32_600;

    private static final String VALID_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String VALID_SPAN_ID = "b7ad6b7169203331";

    private static final Set<McpErrorType> TOOL_ERROR_TYPES =
            EnumSet.of(McpErrorType.INPUT_VALIDATION, McpErrorType.INPUT_PROCESSING, McpErrorType.HANDLER);
    private static final Set<McpOutcome> ERROR_OUTCOMES =
            EnumSet.of(McpOutcome.REJECTED, McpOutcome.FAILED, McpOutcome.CANCELLED);

    private static final McpAuthorizationSummary AUTHORIZATION =
            new McpAuthorizationSummary(true, "policy.permitted", "tenant/policy-1", "2026.08.20");
    private static final SecurityContextSnapshot SECURITY = new SecurityContextSnapshot(
            SecurityIdentity.anonymous(),
            new AuthenticationState(DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of()),
            Optional.empty());
    private static final CorrelationContextSnapshot CORRELATION = new CorrelationContextSnapshot(
            new CorrelationIdentifier("request-1", "test"),
            new CorrelationIdentifier("correlation-1", "test"),
            null,
            null,
            List.of(),
            null,
            Map.of());

    // --- Terminal tuple matrix (W3 / TP-010) ---

    @Nested
    @DisplayName("terminal tuple matrix")
    class TerminalTuples {

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.lifecycle.McpLifecycleEventTest#legalTerminalTupleRows")
        @DisplayName("accepts every legal terminal tuple and round-trips every field")
        void shouldAcceptEveryLegalTerminalTuple(String row, TerminalTuple tuple) {
            SecurityContextSnapshot security = securityFor(tuple);

            McpRequestTerminalEvent event = newTerminal(tuple);

            assertThat(event.startedAt()).isEqualTo(STARTED_AT);
            assertThat(event.terminalAt()).isEqualTo(TERMINAL_AT);
            assertThat(event.method()).isEqualTo(McpMethod.SERVER_DISCOVER);
            assertThat(event.toolName()).isEqualTo(McpRequestTerminalEvent.UNKNOWN_TOOL_NAME);
            assertThat(event.outcome()).isEqualTo(tuple.outcome());
            assertThat(event.errorType()).isEqualTo(tuple.errorType());
            assertThat(event.resultType()).isEqualTo(tuple.resultType());
            assertThat(event.httpStatus()).isEqualTo(tuple.httpStatus());
            assertThat(event.protocolErrorCode()).isEqualTo(tuple.protocolErrorCode());
            assertThat(event.authorization()).isEqualTo(AUTHORIZATION);
            assertThat(event.security()).isEqualTo(security);
            assertThat(event.correlation()).isEqualTo(CORRELATION);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.lifecycle.McpLifecycleEventTest#illegalTerminalTupleRows")
        @DisplayName("rejects every illegal terminal tuple before it can be published")
        void shouldRejectEveryIllegalTerminalTuple(String row, TerminalTuple tuple) {
            RecordingObservation recorder = new RecordingObservation();

            assertThatThrownBy(() -> recorder.onTerminal(new McpRequestTerminalObservation(newTerminal(tuple), null)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(recorder.terminals()).isEmpty();
            assertThat(recorder.completions()).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.lifecycle.McpLifecycleEventTest#anchoredTerminalTupleRows")
        @DisplayName("agrees with hand-pinned legality for anchor tuples")
        void shouldAgreeWithPinnedLegality(String row, boolean expectedLegal, TerminalTuple tuple) {
            assertThat(isLegalTerminalTuple(tuple))
                    .as("the invariant predicate must classify %s as legal=%s", row, expectedLegal)
                    .isEqualTo(expectedLegal);
            if (expectedLegal) {
                assertThatCode(() -> newTerminal(tuple)).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> newTerminal(tuple)).isInstanceOf(IllegalArgumentException.class);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.lifecycle.McpLifecycleEventTest#rejectedTerminalFactRows")
        @DisplayName("rejects illegal ordering, tool identity, and authentication facts")
        void shouldRejectIllegalTerminalFacts(String row, ThrowingConstruction construction) {
            RecordingObservation recorder = new RecordingObservation();

            assertThatThrownBy(() -> recorder.onTerminal(new McpRequestTerminalObservation(construction.build(), null)))
                    .isInstanceOf(RuntimeException.class);

            assertThat(recorder.terminals()).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.lifecycle.McpLifecycleEventTest#acceptedTerminalFactRows")
        @DisplayName("accepts legal ordering, tool identity, and authentication facts")
        void shouldAcceptLegalTerminalFacts(String row, ThrowingConstruction construction) {
            assertThatCode(construction::build).doesNotThrowAnyException();
        }
    }

    // --- Completion tuple matrix (W3 / TP-010) ---

    @Nested
    @DisplayName("completion tuple matrix")
    class CompletionTuples {

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.lifecycle.McpLifecycleEventTest#legalCompletionTupleRows")
        @DisplayName("accepts every legal completion tuple and round-trips every field")
        void shouldAcceptEveryLegalCompletionTuple(String row, CompletionTuple tuple) {
            McpRequestTerminalEvent terminal = successTerminal();

            McpRequestCompletedEvent event = new McpRequestCompletedEvent(
                    terminal, tuple.completedAt(), tuple.transportOutcome(), tuple.responseCommitted());

            assertThat(event.terminal()).isSameAs(terminal);
            assertThat(event.completedAt()).isEqualTo(tuple.completedAt());
            assertThat(event.transportOutcome()).isEqualTo(tuple.transportOutcome());
            assertThat(event.responseCommitted()).isEqualTo(tuple.responseCommitted());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.lifecycle.McpLifecycleEventTest#illegalCompletionTupleRows")
        @DisplayName("rejects every illegal completion tuple before it can be published")
        void shouldRejectEveryIllegalCompletionTuple(String row, CompletionTuple tuple) {
            RecordingObservation recorder = new RecordingObservation();

            assertThatThrownBy(() -> recorder.onCompleted(new McpRequestCompletedEvent(
                            successTerminal(),
                            tuple.completedAt(),
                            tuple.transportOutcome(),
                            tuple.responseCommitted())))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(recorder.completions()).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.lifecycle.McpLifecycleEventTest#anchoredCompletionTupleRows")
        @DisplayName("agrees with hand-pinned legality for anchor completion tuples")
        void shouldAgreeWithPinnedCompletionLegality(String row, boolean expectedLegal, CompletionTuple tuple) {
            assertThat(isLegalCompletionTuple(tuple))
                    .as("the invariant predicate must classify %s as legal=%s", row, expectedLegal)
                    .isEqualTo(expectedLegal);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.lifecycle.McpLifecycleEventTest#namedCompletionFactoryRows")
        @DisplayName("named completion factories produce the documented transport tuple")
        void shouldPinNamedCompletionFactories(
                String row,
                McpRequestCompletedEvent event,
                McpTransportOutcome expectedOutcome,
                boolean expectedCommitted) {
            assertThat(event.transportOutcome()).isEqualTo(expectedOutcome);
            assertThat(event.responseCommitted()).isEqualTo(expectedCommitted);
            assertThat(event.completedAt()).isEqualTo(COMPLETED_AT);
        }
    }

    /**
     * Pins the bounded-string invariants the lifecycle contract states for the normalized trace
     * reference and the policy-decision summary: {@code traceState} is at most 512 printable-ASCII
     * (0x20–0x7E) characters, {@code reasonCode} matches {@code [a-z0-9][a-z0-9._-]{0,63}}, and
     * {@code policyId}/{@code policyVersion} are non-blank, control-character-free, and at most 256
     * and 64 characters. Every row pairs an exact boundary value with its adjacent violation.
     */
    @Nested
    @DisplayName("bounded reason, policy, and trace-state values")
    class BoundedValues {

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.lifecycle.McpLifecycleEventTest#acceptedTraceStateRows")
        @DisplayName("accepts trace state at the documented bound")
        void shouldAcceptTraceStateAtBound(String row, String traceState) {
            assertThatCode(() -> new McpTraceContext(VALID_TRACE_ID, VALID_SPAN_ID, true, traceState))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.lifecycle.McpLifecycleEventTest#rejectedTraceStateRows")
        @DisplayName("rejects trace state past the documented bound")
        void shouldRejectTraceStatePastBound(String row, String traceState) {
            assertThatThrownBy(() -> new McpTraceContext(VALID_TRACE_ID, VALID_SPAN_ID, true, traceState))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.lifecycle.McpLifecycleEventTest#acceptedAuthorizationRows")
        @DisplayName("accepts authorization summaries at the documented bounds")
        void shouldAcceptAuthorizationSummaryAtBound(
                String row, String reasonCode, String policyId, String policyVersion) {
            assertThatCode(() -> new McpAuthorizationSummary(false, reasonCode, policyId, policyVersion))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dev.vertique.mcp.lifecycle.McpLifecycleEventTest#rejectedAuthorizationRows")
        @DisplayName("rejects authorization summaries past the documented bounds")
        void shouldRejectAuthorizationSummaryPastBound(
                String row, String reasonCode, String policyId, String policyVersion) {
            assertThatThrownBy(() -> new McpAuthorizationSummary(false, reasonCode, policyId, policyVersion))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // --- Invariant predicates (transcribed from the frozen lifecycle contract) ---

    /**
     * Returns whether {@code tuple} satisfies every frozen terminal invariant: {@code SUCCESS}
     * requires {@code errorType=NONE}, {@code resultType=COMPLETE}, no protocol error and a 2xx
     * status; {@code TOOL_ERROR} requires an input or handler error, {@code resultType=COMPLETE} and
     * no protocol error; {@code REJECTED}/{@code FAILED}/{@code CANCELLED} require a non-{@code NONE}
     * error with no result, and may use {@code httpStatus=0} when no response could be attempted.
     */
    private static boolean isLegalTerminalTuple(TerminalTuple tuple) {
        boolean statusInRange = tuple.httpStatus() >= 100 && tuple.httpStatus() <= 599;
        return switch (tuple.outcome()) {
            case SUCCESS ->
                tuple.errorType() == McpErrorType.NONE
                        && tuple.resultType() == McpResultType.COMPLETE
                        && tuple.protocolErrorCode() == null
                        && statusInRange
                        && tuple.httpStatus() / 100 == 2;
            case TOOL_ERROR ->
                TOOL_ERROR_TYPES.contains(tuple.errorType())
                        && tuple.resultType() == McpResultType.COMPLETE
                        && tuple.protocolErrorCode() == null
                        && statusInRange;
            case REJECTED, FAILED, CANCELLED ->
                tuple.errorType() != McpErrorType.NONE
                        && tuple.resultType() == McpResultType.NONE
                        && (tuple.httpStatus() == 0 || statusInRange);
        };
    }

    /**
     * Returns whether {@code tuple} satisfies the frozen completion invariants: completion never
     * precedes terminal settlement, and {@code WRITTEN} requires a committed response.
     */
    private static boolean isLegalCompletionTuple(CompletionTuple tuple) {
        return !tuple.completedAt().isBefore(TERMINAL_AT)
                && (tuple.transportOutcome() != McpTransportOutcome.WRITTEN || tuple.responseCommitted());
    }

    // --- Row tables: terminal tuples ---

    private static Stream<Arguments> legalTerminalTupleRows() {
        return allTerminalTuples()
                .filter(McpLifecycleEventTest::isLegalTerminalTuple)
                .map(tuple -> Arguments.of(tuple.row(), tuple));
    }

    private static Stream<Arguments> illegalTerminalTupleRows() {
        return allTerminalTuples()
                .filter(tuple -> !isLegalTerminalTuple(tuple))
                .map(tuple -> Arguments.of(tuple.row(), tuple));
    }

    /**
     * Enumerates every {@code outcome × errorType × resultType} combination at each outcome's
     * canonical status, then every single-dimension mutation of {@code httpStatus} and
     * {@code protocolErrorCode} away from that outcome's legal tuple.
     */
    private static Stream<TerminalTuple> allTerminalTuples() {
        return Stream.concat(Stream.concat(enumCrossProduct(), statusDimension()), protocolErrorCodeDimension());
    }

    private static Stream<TerminalTuple> enumCrossProduct() {
        return Arrays.stream(McpOutcome.values()).flatMap(outcome -> Arrays.stream(McpErrorType.values())
                .flatMap(errorType -> Arrays.stream(McpResultType.values())
                        .map(resultType -> new TerminalTuple(
                                outcome + " + " + errorType + " + " + resultType,
                                outcome,
                                errorType,
                                resultType,
                                canonicalStatus(outcome),
                                null))));
    }

    private static Stream<TerminalTuple> statusDimension() {
        List<Integer> statuses = List.of(-1, 0, 99, 100, 200, 204, 299, 300, 400, 404, 500, 599, 600);
        return Arrays.stream(McpOutcome.values()).flatMap(outcome -> statuses.stream()
                .map(status -> new TerminalTuple(
                        outcome + " with only httpStatus mutated to " + status,
                        outcome,
                        legalErrorTypeFor(outcome),
                        legalResultTypeFor(outcome),
                        status,
                        null)));
    }

    private static Stream<TerminalTuple> protocolErrorCodeDimension() {
        return Arrays.stream(McpOutcome.values())
                .map(outcome -> new TerminalTuple(
                        outcome + " with only protocolErrorCode mutated to " + PROTOCOL_ERROR_CODE,
                        outcome,
                        legalErrorTypeFor(outcome),
                        legalResultTypeFor(outcome),
                        canonicalStatus(outcome),
                        PROTOCOL_ERROR_CODE));
    }

    /**
     * Hand-pinned legality verdicts. These rows are literal — they are never produced by
     * {@link #isLegalTerminalTuple} — so they anchor the predicate the generated tables rely on.
     */
    private static Stream<Arguments> anchoredTerminalTupleRows() {
        return Stream.of(
                anchor(
                        "success with a completed 2xx result",
                        true,
                        McpOutcome.SUCCESS,
                        McpErrorType.NONE,
                        McpResultType.COMPLETE,
                        200,
                        null),
                anchor(
                        "success at the top of the 2xx band",
                        true,
                        McpOutcome.SUCCESS,
                        McpErrorType.NONE,
                        McpResultType.COMPLETE,
                        299,
                        null),
                anchor(
                        "tool error from the handler",
                        true,
                        McpOutcome.TOOL_ERROR,
                        McpErrorType.HANDLER,
                        McpResultType.COMPLETE,
                        200,
                        null),
                anchor(
                        "tool error from input validation",
                        true,
                        McpOutcome.TOOL_ERROR,
                        McpErrorType.INPUT_VALIDATION,
                        McpResultType.COMPLETE,
                        400,
                        null),
                anchor(
                        "authorization rejection",
                        true,
                        McpOutcome.REJECTED,
                        McpErrorType.AUTHORIZATION,
                        McpResultType.NONE,
                        403,
                        null),
                anchor(
                        "protocol rejection carrying a JSON-RPC code",
                        true,
                        McpOutcome.REJECTED,
                        McpErrorType.PROTOCOL,
                        McpResultType.NONE,
                        400,
                        PROTOCOL_ERROR_CODE),
                anchor(
                        "internal failure",
                        true,
                        McpOutcome.FAILED,
                        McpErrorType.INTERNAL,
                        McpResultType.NONE,
                        500,
                        null),
                anchor(
                        "transport failure with no response attempted",
                        true,
                        McpOutcome.FAILED,
                        McpErrorType.TRANSPORT,
                        McpResultType.NONE,
                        0,
                        null),
                anchor(
                        "timeout cancellation with no response attempted",
                        true,
                        McpOutcome.CANCELLED,
                        McpErrorType.TIMEOUT,
                        McpResultType.NONE,
                        0,
                        null),
                anchor(
                        "success carrying an error type",
                        false,
                        McpOutcome.SUCCESS,
                        McpErrorType.HANDLER,
                        McpResultType.COMPLETE,
                        200,
                        null),
                anchor(
                        "success without a completed result",
                        false,
                        McpOutcome.SUCCESS,
                        McpErrorType.NONE,
                        McpResultType.NONE,
                        200,
                        null),
                anchor(
                        "success with a non-2xx status",
                        false,
                        McpOutcome.SUCCESS,
                        McpErrorType.NONE,
                        McpResultType.COMPLETE,
                        500,
                        null),
                anchor(
                        "success with a protocol error code",
                        false,
                        McpOutcome.SUCCESS,
                        McpErrorType.NONE,
                        McpResultType.COMPLETE,
                        200,
                        PROTOCOL_ERROR_CODE),
                anchor(
                        "success with no response attempted",
                        false,
                        McpOutcome.SUCCESS,
                        McpErrorType.NONE,
                        McpResultType.COMPLETE,
                        0,
                        null),
                anchor(
                        "tool error from a non-tool error type",
                        false,
                        McpOutcome.TOOL_ERROR,
                        McpErrorType.AUTHORIZATION,
                        McpResultType.COMPLETE,
                        200,
                        null),
                anchor(
                        "tool error without a completed result",
                        false,
                        McpOutcome.TOOL_ERROR,
                        McpErrorType.HANDLER,
                        McpResultType.NONE,
                        200,
                        null),
                anchor(
                        "tool error with a protocol error code",
                        false,
                        McpOutcome.TOOL_ERROR,
                        McpErrorType.HANDLER,
                        McpResultType.COMPLETE,
                        200,
                        PROTOCOL_ERROR_CODE),
                anchor(
                        "tool error with no response attempted",
                        false,
                        McpOutcome.TOOL_ERROR,
                        McpErrorType.HANDLER,
                        McpResultType.COMPLETE,
                        0,
                        null),
                anchor(
                        "rejection without an error type",
                        false,
                        McpOutcome.REJECTED,
                        McpErrorType.NONE,
                        McpResultType.NONE,
                        403,
                        null),
                anchor(
                        "rejection carrying a completed result",
                        false,
                        McpOutcome.REJECTED,
                        McpErrorType.AUTHORIZATION,
                        McpResultType.COMPLETE,
                        403,
                        null),
                anchor(
                        "failure without an error type",
                        false,
                        McpOutcome.FAILED,
                        McpErrorType.NONE,
                        McpResultType.NONE,
                        500,
                        null),
                anchor(
                        "cancellation carrying a completed result",
                        false,
                        McpOutcome.CANCELLED,
                        McpErrorType.TIMEOUT,
                        McpResultType.COMPLETE,
                        0,
                        null),
                anchor(
                        "failure with an out-of-band status",
                        false,
                        McpOutcome.FAILED,
                        McpErrorType.INTERNAL,
                        McpResultType.NONE,
                        600,
                        null));
    }

    private static Stream<Arguments> rejectedTerminalFactRows() {
        return Stream.of(
                Arguments.of("completion ordering: terminalAt before startedAt", (ThrowingConstruction)
                        () -> McpRequestTerminalEvent.success(
                                STARTED_AT,
                                STARTED_AT.minusMillis(1),
                                McpMethod.SERVER_DISCOVER,
                                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                                200,
                                null,
                                null,
                                null)),
                Arguments.of("a non-tool method carrying a named tool", (ThrowingConstruction)
                        () -> McpRequestTerminalEvent.success(
                                STARTED_AT, TERMINAL_AT, McpMethod.SERVER_DISCOVER, "greet", 200, null, null, null)),
                Arguments.of("a blank tool name", (ThrowingConstruction) () -> McpRequestTerminalEvent.success(
                        STARTED_AT, TERMINAL_AT, McpMethod.TOOLS_CALL, "  ", 200, null, null, null)),
                Arguments.of("an authentication rejection carrying security facts", (ThrowingConstruction)
                        () -> McpRequestTerminalEvent.rejected(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.SERVER_DISCOVER,
                                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                                McpErrorType.AUTHENTICATION,
                                401,
                                null,
                                null,
                                SECURITY,
                                null)));
    }

    private static Stream<Arguments> acceptedTerminalFactRows() {
        return Stream.of(
                Arguments.of(
                        "terminalAt equal to startedAt", (ThrowingConstruction) () -> McpRequestTerminalEvent.success(
                                STARTED_AT,
                                STARTED_AT,
                                McpMethod.SERVER_DISCOVER,
                                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                                200,
                                null,
                                null,
                                null)),
                Arguments.of("a tool call carrying a named tool", (ThrowingConstruction)
                        () -> McpRequestTerminalEvent.success(
                                STARTED_AT, TERMINAL_AT, McpMethod.TOOLS_CALL, "greet", 200, null, null, null)),
                Arguments.of("a tool call using the UNKNOWN literal", (ThrowingConstruction)
                        () -> McpRequestTerminalEvent.success(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.TOOLS_CALL,
                                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                                200,
                                null,
                                null,
                                null)),
                Arguments.of("an authentication rejection without security facts", (ThrowingConstruction)
                        () -> McpRequestTerminalEvent.rejected(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.SERVER_DISCOVER,
                                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                                McpErrorType.AUTHENTICATION,
                                401,
                                null,
                                null,
                                null,
                                null)),
                Arguments.of("an authorization rejection carrying security facts", (ThrowingConstruction)
                        () -> McpRequestTerminalEvent.rejected(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.SERVER_DISCOVER,
                                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                                McpErrorType.AUTHORIZATION,
                                403,
                                null,
                                AUTHORIZATION,
                                SECURITY,
                                null)));
    }

    // --- Row tables: completion tuples ---

    private static Stream<Arguments> legalCompletionTupleRows() {
        return allCompletionTuples()
                .filter(McpLifecycleEventTest::isLegalCompletionTuple)
                .map(tuple -> Arguments.of(tuple.row(), tuple));
    }

    private static Stream<Arguments> illegalCompletionTupleRows() {
        return allCompletionTuples()
                .filter(tuple -> !isLegalCompletionTuple(tuple))
                .map(tuple -> Arguments.of(tuple.row(), tuple));
    }

    private static Stream<CompletionTuple> allCompletionTuples() {
        List<Map.Entry<String, Instant>> orderings = List.of(
                Map.entry("completedAt after terminalAt", COMPLETED_AT),
                Map.entry("completedAt equal to terminalAt", TERMINAL_AT),
                Map.entry("completedAt before terminalAt", TERMINAL_AT.minusMillis(1)));
        return Arrays.stream(McpTransportOutcome.values())
                .flatMap(transportOutcome -> Stream.of(true, false).flatMap(committed -> orderings.stream()
                        .map(ordering -> new CompletionTuple(
                                transportOutcome + " + responseCommitted=" + committed + " + " + ordering.getKey(),
                                transportOutcome,
                                committed,
                                ordering.getValue()))));
    }

    /** Hand-pinned completion verdicts anchoring {@link #isLegalCompletionTuple}. */
    private static Stream<Arguments> anchoredCompletionTupleRows() {
        return Stream.of(
                completionAnchor("a committed write", true, McpTransportOutcome.WRITTEN, true, COMPLETED_AT),
                completionAnchor("an uncommitted write", false, McpTransportOutcome.WRITTEN, false, COMPLETED_AT),
                completionAnchor(
                        "a disconnect before the first write",
                        true,
                        McpTransportOutcome.DISCONNECTED,
                        false,
                        COMPLETED_AT),
                completionAnchor(
                        "a reset before the first write", true, McpTransportOutcome.RESET, false, COMPLETED_AT),
                completionAnchor(
                        "a write failure after commit", true, McpTransportOutcome.WRITE_FAILED, true, COMPLETED_AT),
                completionAnchor(
                        "a write failure before commit", true, McpTransportOutcome.WRITE_FAILED, false, COMPLETED_AT),
                completionAnchor(
                        "a write completing before terminal settlement",
                        false,
                        McpTransportOutcome.WRITTEN,
                        true,
                        TERMINAL_AT.minusMillis(1)),
                completionAnchor(
                        "a disconnect completing before terminal settlement",
                        false,
                        McpTransportOutcome.DISCONNECTED,
                        false,
                        TERMINAL_AT.minusMillis(1)));
    }

    private static Stream<Arguments> namedCompletionFactoryRows() {
        McpRequestTerminalEvent terminal = successTerminal();
        return Stream.of(
                Arguments.of(
                        "written",
                        McpRequestCompletedEvent.written(terminal, COMPLETED_AT),
                        McpTransportOutcome.WRITTEN,
                        true),
                Arguments.of(
                        "disconnected before the first write",
                        McpRequestCompletedEvent.disconnected(terminal, COMPLETED_AT, false),
                        McpTransportOutcome.DISCONNECTED,
                        false),
                Arguments.of(
                        "reset before the first write",
                        McpRequestCompletedEvent.reset(terminal, COMPLETED_AT, false),
                        McpTransportOutcome.RESET,
                        false),
                Arguments.of(
                        "write failed after commit",
                        McpRequestCompletedEvent.writeFailed(terminal, COMPLETED_AT, true),
                        McpTransportOutcome.WRITE_FAILED,
                        true));
    }

    // --- Row tables: bounded values (W5) ---

    private static Stream<Arguments> acceptedTraceStateRows() {
        return Stream.of(
                Arguments.of("absent trace state", null),
                Arguments.of("single-character trace state", "a"),
                Arguments.of("trace state at the 512-character bound", "a".repeat(512)),
                Arguments.of("trace state spanning the printable ASCII extremes", "a ~"),
                Arguments.of("trace state with W3C list syntax", "congo=t61rcWkgMzE,rojo=00f067aa0ba902b7"));
    }

    private static Stream<Arguments> rejectedTraceStateRows() {
        return Stream.of(
                Arguments.of("blank trace state", "   "),
                Arguments.of("trace state one character past the 512-character bound", "a".repeat(513)),
                Arguments.of("trace state with a C0 control character", "a\u0001b"),
                Arguments.of("trace state with a tab", "a\tb"),
                Arguments.of("trace state with a newline", "a\nb"),
                Arguments.of("trace state with DEL (0x7F)", "a\u007Fb"),
                Arguments.of("trace state with a non-ASCII character", "aéb"));
    }

    private static Stream<Arguments> acceptedAuthorizationRows() {
        return Stream.of(
                Arguments.of("minimal reason code without policy identity", "a", null, null),
                Arguments.of("reason code using every legal separator", "a.b_c-d0", null, null),
                Arguments.of("reason code at the 64-character bound", "a".repeat(64), null, null),
                Arguments.of("policy id at the 256-character bound", "denied", "p".repeat(256), null),
                Arguments.of("policy version at the 64-character bound", "denied", null, "v".repeat(64)),
                Arguments.of("full policy identity", "policy.denied", "tenant/policy-1", "2026.08.20"));
    }

    private static Stream<Arguments> rejectedAuthorizationRows() {
        return Stream.of(
                Arguments.of("null reason code", null, null, null),
                Arguments.of("blank reason code", "   ", null, null),
                Arguments.of("empty reason code", "", null, null),
                Arguments.of("reason code one character past the 64-character bound", "a".repeat(65), null, null),
                Arguments.of("uppercase reason code", "Denied", null, null),
                Arguments.of("reason code starting with a separator", "-denied", null, null),
                Arguments.of("reason code with an illegal character", "denied!", null, null),
                Arguments.of("reason code with a space", "policy denied", null, null),
                Arguments.of("policy id one character past the 256-character bound", "denied", "p".repeat(257), null),
                Arguments.of("blank policy id", "denied", "   ", null),
                Arguments.of("policy id with a control character", "denied", "poli\u0001cy", null),
                Arguments.of(
                        "policy version one character past the 64-character bound", "denied", null, "v".repeat(65)),
                Arguments.of("blank policy version", "denied", null, "   "),
                Arguments.of("policy version with a control character", "denied", null, "1\u007F0"));
    }

    // --- Fixtures ---

    private static McpRequestTerminalEvent newTerminal(TerminalTuple tuple) {
        return new McpRequestTerminalEvent(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.SERVER_DISCOVER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                tuple.outcome(),
                tuple.errorType(),
                tuple.resultType(),
                tuple.httpStatus(),
                tuple.protocolErrorCode(),
                AUTHORIZATION,
                securityFor(tuple),
                CORRELATION);
    }

    /**
     * Returns the security snapshot legal for {@code tuple}: an authentication rejection must carry
     * none, every other tuple carries the established snapshot.
     */
    @Nullable
    private static SecurityContextSnapshot securityFor(TerminalTuple tuple) {
        boolean authenticationRejection =
                tuple.outcome() == McpOutcome.REJECTED && tuple.errorType() == McpErrorType.AUTHENTICATION;
        return authenticationRejection ? null : SECURITY;
    }

    private static McpRequestTerminalEvent successTerminal() {
        return McpRequestTerminalEvent.success(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.SERVER_DISCOVER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                200,
                null,
                null,
                null);
    }

    private static int canonicalStatus(McpOutcome outcome) {
        return switch (outcome) {
            case SUCCESS, TOOL_ERROR -> 200;
            case REJECTED -> 403;
            case FAILED -> 500;
            case CANCELLED -> 503;
        };
    }

    private static McpErrorType legalErrorTypeFor(McpOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> McpErrorType.NONE;
            case TOOL_ERROR -> McpErrorType.HANDLER;
            case REJECTED, FAILED, CANCELLED -> McpErrorType.INTERNAL;
        };
    }

    private static McpResultType legalResultTypeFor(McpOutcome outcome) {
        return ERROR_OUTCOMES.contains(outcome) ? McpResultType.NONE : McpResultType.COMPLETE;
    }

    private static Arguments anchor(
            String row,
            boolean expectedLegal,
            McpOutcome outcome,
            McpErrorType errorType,
            McpResultType resultType,
            int httpStatus,
            @Nullable Integer protocolErrorCode) {
        return Arguments.of(
                row,
                expectedLegal,
                new TerminalTuple(row, outcome, errorType, resultType, httpStatus, protocolErrorCode));
    }

    private static Arguments completionAnchor(
            String row,
            boolean expectedLegal,
            McpTransportOutcome transportOutcome,
            boolean responseCommitted,
            Instant completedAt) {
        return Arguments.of(
                row, expectedLegal, new CompletionTuple(row, transportOutcome, responseCommitted, completedAt));
    }

    /** One enumerated point in the terminal state space. */
    private record TerminalTuple(
            String row,
            McpOutcome outcome,
            McpErrorType errorType,
            McpResultType resultType,
            int httpStatus,
            @Nullable Integer protocolErrorCode) {}

    /** One enumerated point in the completion state space. */
    private record CompletionTuple(
            String row, McpTransportOutcome transportOutcome, boolean responseCommitted, Instant completedAt) {}

    /** A deferred event construction that may fail validation. */
    @FunctionalInterface
    private interface ThrowingConstruction {
        McpRequestTerminalEvent build();
    }

    /** Records everything a lifecycle observation session is handed. */
    private static final class RecordingObservation implements McpRequestObservation {
        private final List<McpRequestTerminalObservation> terminals = new ArrayList<>();
        private final List<McpRequestCompletedEvent> completions = new ArrayList<>();

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminals.add(observation);
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completions.add(event);
        }

        List<McpRequestTerminalObservation> terminals() {
            return terminals;
        }

        List<McpRequestCompletedEvent> completions() {
            return completions;
        }
    }
}
