// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import java.util.Locale;

/**
 * INTERNAL: the one place that turns a failure into the value stored in a command's {@code last_error}.
 *
 * <p>The stored value is {@code <REASON_CODE>: <message>}. The reason code comes from the fixed {@link Reason} set and
 * the message is composed here from the command kind, the step cap, the attempt counts and the instance status, which
 * are framework facts. Raw exception text, exception class names, stack traces, SQL and payload values are never part
 * of it: the full exception goes to the log only. The value is cut to {@link #MAX_LENGTH} characters, the size of the
 * column, and every interpolated token is reduced to a short identifier first.
 *
 * <p>Every writer of {@code last_error} (the dispositions, the dead-letter and ended-instance branches of the claim)
 * builds its value here.
 */
public final class CommandErrors {

    /** The most characters a stored value holds. */
    public static final int MAX_LENGTH = 4000;

    private static final int MAX_TOKEN_LENGTH = 40;

    /** The fixed set of reason codes. */
    public enum Reason {
        /** A transient failure; the command was rescheduled without spending an attempt. */
        TRANSIENT_FAILURE,
        /** A failure that a retry of the same command repeats. */
        DETERMINISTIC_FAILURE,
        /** A segment executed more nodes than the step cap. */
        STEP_LIMIT,
        /** The command spent every attempt it was allowed. */
        ATTEMPTS_EXHAUSTED,
        /** The command's instance had ended when the command was claimed. */
        INSTANCE_ENDED
    }

    private CommandErrors() {
        // static helpers only
    }

    /**
     * The value for a command rescheduled after a transient failure.
     *
     * @param kind the command kind
     * @return the stored value
     */
    public static String transientFailure(String kind) {
        return format(
                Reason.TRANSIENT_FAILURE,
                token(kind) + " command failed with a transient error and was rescheduled; its attempt was not spent");
    }

    /**
     * The value for a command whose segment failed in a way a retry repeats.
     *
     * @param kind the command kind
     * @return the stored value
     */
    public static String deterministicFailure(String kind) {
        return format(
                Reason.DETERMINISTIC_FAILURE,
                token(kind) + " command failed with a non-retryable error; see the executor log");
    }

    /**
     * The value for a command whose segment executed more nodes than the cap.
     *
     * @param kind the command kind
     * @param cap  the step cap that was exceeded
     * @return the stored value
     */
    public static String stepLimit(String kind, int cap) {
        return format(
                Reason.STEP_LIMIT,
                token(kind) + " command executed more than " + cap + " steps in one segment without reaching a wait");
    }

    /**
     * The value for a command that spent every attempt it was allowed.
     *
     * @param kind        the command kind
     * @param attempts    the attempts the command spent
     * @param maxAttempts the attempts it was allowed
     * @return the stored value
     */
    public static String attemptsExhausted(String kind, int attempts, int maxAttempts) {
        return format(
                Reason.ATTEMPTS_EXHAUSTED,
                token(kind) + " command was dead-lettered after " + attempts + " of " + maxAttempts + " attempts");
    }

    /**
     * The value for a command whose instance had ended when the command was claimed.
     *
     * @param kind   the command kind
     * @param status the status of the ended instance
     * @return the stored value
     */
    public static String instanceEnded(String kind, String status) {
        return format(
                Reason.INSTANCE_ENDED,
                token(kind) + " command was settled because its instance had already ended with status "
                        + token(status));
    }

    /**
     * Joins a reason code and a framework-composed message, and bounds the result.
     *
     * @param reason  the reason code
     * @param message the message; control characters are replaced by spaces
     * @return {@code <REASON_CODE>: <message>}, at most {@link #MAX_LENGTH} characters
     */
    static String format(Reason reason, String message) {
        StringBuilder text = new StringBuilder(reason.name()).append(": ");
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            text.append(Character.isISOControl(c) ? ' ' : c);
        }
        return text.length() <= MAX_LENGTH ? text.toString() : text.substring(0, MAX_LENGTH);
    }

    /** Reduces a value to a short identifier: letters, digits, underscore and hyphen, upper-cased. */
    private static String token(String value) {
        if (value == null || value.isEmpty()) {
            return "UNKNOWN";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < value.length() && text.length() < MAX_TOKEN_LENGTH; i++) {
            char c = value.charAt(i);
            if (c < 128 && (Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                text.append(c);
            }
        }
        return text.isEmpty() ? "UNKNOWN" : text.toString().toUpperCase(Locale.ROOT);
    }
}
