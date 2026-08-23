// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.security.SecurityContextSnapshot;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, bounded facts recorded when an MCP request settles logically.
 *
 * <p>Use the named factories instead of the canonical constructor. They supply the only legal
 * outcome and result-type combinations.
 */
public record McpRequestTerminalEvent(
        Instant startedAt,
        Instant terminalAt,
        McpMethod method,
        String toolName,
        McpOutcome outcome,
        McpErrorType errorType,
        McpResultType resultType,
        int httpStatus,
        @Nullable Integer protocolErrorCode,
        @Nullable McpAuthorizationSummary authorization,
        @Nullable SecurityContextSnapshot security,
        @Nullable CorrelationContextSnapshot correlation) {

    /** Literal used whenever the request has no generated, known tool identity. */
    public static final String UNKNOWN_TOOL_NAME = "UNKNOWN";

    /**
     * Validates the lifecycle facts and their state-dependent invariants.
     *
     * @throws NullPointerException if a required fact is null
     * @throws IllegalArgumentException if a state invariant is violated
     */
    public McpRequestTerminalEvent {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(terminalAt, "terminalAt");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(errorType, "errorType");
        Objects.requireNonNull(resultType, "resultType");
        if (terminalAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("terminalAt must not be before startedAt");
        }
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (method != McpMethod.TOOLS_CALL && !UNKNOWN_TOOL_NAME.equals(toolName)) {
            throw new IllegalArgumentException("non-tool requests must use toolName UNKNOWN");
        }
        // A resolved tool identity is bounded by the published McpToolDescriptor name grammar
        // ([A-Za-z0-9_.-]{1,128}); an unresolved tools/call name never touches a real descriptor, so
        // without this check it would be bounded only by the wire's 20,000,000-char string limit before
        // reaching every lifecycle observer and listener as internal telemetry. Every producer in this
        // module already passes UNKNOWN_TOOL_NAME for an unresolved name (see McpRequestDispatcher's
        // placeholder-descriptor decision point), so this is a defense-in-depth backstop covering every
        // future producer, not merely today's.
        if (!UNKNOWN_TOOL_NAME.equals(toolName) && !McpToolDescriptor.isValidName(toolName)) {
            throw new IllegalArgumentException("toolName must be UNKNOWN or match [A-Za-z0-9_.-]{1,128}");
        }
        validateHttpStatus(httpStatus, outcome);
        validateState(outcome, errorType, resultType, httpStatus, protocolErrorCode);
        if (outcome == McpOutcome.REJECTED && errorType == McpErrorType.AUTHENTICATION && security != null) {
            throw new IllegalArgumentException("authentication rejection must not contain security facts");
        }
    }

    /** Creates a successful terminal event with a completed result. */
    public static McpRequestTerminalEvent success(
            Instant startedAt,
            Instant terminalAt,
            McpMethod method,
            String toolName,
            int httpStatus,
            @Nullable McpAuthorizationSummary authorization,
            @Nullable SecurityContextSnapshot security,
            @Nullable CorrelationContextSnapshot correlation) {
        return new McpRequestTerminalEvent(
                startedAt,
                terminalAt,
                method,
                toolName,
                McpOutcome.SUCCESS,
                McpErrorType.NONE,
                McpResultType.COMPLETE,
                httpStatus,
                null,
                authorization,
                security,
                correlation);
    }

    /** Creates a completed tool-error terminal event. */
    public static McpRequestTerminalEvent toolError(
            Instant startedAt,
            Instant terminalAt,
            McpMethod method,
            String toolName,
            McpErrorType errorType,
            int httpStatus,
            @Nullable McpAuthorizationSummary authorization,
            @Nullable SecurityContextSnapshot security,
            @Nullable CorrelationContextSnapshot correlation) {
        return new McpRequestTerminalEvent(
                startedAt,
                terminalAt,
                method,
                toolName,
                McpOutcome.TOOL_ERROR,
                errorType,
                McpResultType.COMPLETE,
                httpStatus,
                null,
                authorization,
                security,
                correlation);
    }

    /** Creates a rejected terminal event. */
    public static McpRequestTerminalEvent rejected(
            Instant startedAt,
            Instant terminalAt,
            McpMethod method,
            String toolName,
            McpErrorType errorType,
            int httpStatus,
            @Nullable Integer protocolErrorCode,
            @Nullable McpAuthorizationSummary authorization,
            @Nullable SecurityContextSnapshot security,
            @Nullable CorrelationContextSnapshot correlation) {
        return terminal(
                startedAt,
                terminalAt,
                method,
                toolName,
                McpOutcome.REJECTED,
                errorType,
                httpStatus,
                protocolErrorCode,
                authorization,
                security,
                correlation);
    }

    /** Creates a failed terminal event. */
    public static McpRequestTerminalEvent failed(
            Instant startedAt,
            Instant terminalAt,
            McpMethod method,
            String toolName,
            McpErrorType errorType,
            int httpStatus,
            @Nullable Integer protocolErrorCode,
            @Nullable McpAuthorizationSummary authorization,
            @Nullable SecurityContextSnapshot security,
            @Nullable CorrelationContextSnapshot correlation) {
        return terminal(
                startedAt,
                terminalAt,
                method,
                toolName,
                McpOutcome.FAILED,
                errorType,
                httpStatus,
                protocolErrorCode,
                authorization,
                security,
                correlation);
    }

    /** Creates a cancelled terminal event. */
    public static McpRequestTerminalEvent cancelled(
            Instant startedAt,
            Instant terminalAt,
            McpMethod method,
            String toolName,
            McpErrorType errorType,
            int httpStatus,
            @Nullable Integer protocolErrorCode,
            @Nullable McpAuthorizationSummary authorization,
            @Nullable SecurityContextSnapshot security,
            @Nullable CorrelationContextSnapshot correlation) {
        return terminal(
                startedAt,
                terminalAt,
                method,
                toolName,
                McpOutcome.CANCELLED,
                errorType,
                httpStatus,
                protocolErrorCode,
                authorization,
                security,
                correlation);
    }

    private static McpRequestTerminalEvent terminal(
            Instant startedAt,
            Instant terminalAt,
            McpMethod method,
            String toolName,
            McpOutcome outcome,
            McpErrorType errorType,
            int httpStatus,
            @Nullable Integer protocolErrorCode,
            @Nullable McpAuthorizationSummary authorization,
            @Nullable SecurityContextSnapshot security,
            @Nullable CorrelationContextSnapshot correlation) {
        return new McpRequestTerminalEvent(
                startedAt,
                terminalAt,
                method,
                toolName,
                outcome,
                errorType,
                McpResultType.NONE,
                httpStatus,
                protocolErrorCode,
                authorization,
                security,
                correlation);
    }

    private static void validateHttpStatus(int httpStatus, McpOutcome outcome) {
        if (httpStatus == 0
                && (outcome == McpOutcome.REJECTED
                        || outcome == McpOutcome.FAILED
                        || outcome == McpOutcome.CANCELLED)) {
            return;
        }
        if (httpStatus < 100 || httpStatus > 599) {
            throw new IllegalArgumentException("httpStatus must be 0 or a valid HTTP status");
        }
    }

    private static void validateState(
            McpOutcome outcome,
            McpErrorType errorType,
            McpResultType resultType,
            int httpStatus,
            @Nullable Integer protocolErrorCode) {
        switch (outcome) {
            case SUCCESS -> {
                if (errorType != McpErrorType.NONE
                        || resultType != McpResultType.COMPLETE
                        || protocolErrorCode != null
                        || httpStatus / 100 != 2) {
                    throw new IllegalArgumentException(
                            "successful events require a 2xx completed result without errors");
                }
            }
            case TOOL_ERROR -> {
                if (!isToolError(errorType) || resultType != McpResultType.COMPLETE || protocolErrorCode != null) {
                    throw new IllegalArgumentException(
                            "tool errors require a completed input or handler error result without a protocol error");
                }
            }
            case REJECTED, FAILED, CANCELLED -> {
                if (errorType == McpErrorType.NONE || resultType != McpResultType.NONE) {
                    throw new IllegalArgumentException(
                            "rejected, failed, and cancelled events require an error without a result");
                }
            }
        }
    }

    private static boolean isToolError(McpErrorType errorType) {
        return errorType == McpErrorType.INPUT_VALIDATION
                || errorType == McpErrorType.INPUT_PROCESSING
                || errorType == McpErrorType.HANDLER;
    }
}
