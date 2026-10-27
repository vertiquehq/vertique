// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.types.EchoedNames;
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
        INSTANCE_ENDED,
        /** This node holds no plan for the instance's pin, so the command was deferred. */
        NOT_RUNNABLE,
        /** A signal names no wait of the definition version its instance is pinned to. */
        UNDECLARED_SIGNAL,
        /** A branch-addressed signal arrived after its branch was superseded or ended. */
        BRANCH_ENDED,
        /** A completion's task is no longer reserved by that completion. */
        TASK_NOT_RESERVED,
        /** A completion's instance had failed, so it cannot take the decision. */
        INSTANCE_FAILED
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
     * The value for a command deferred because this node cannot run the plan its instance is pinned to.
     *
     * @param kind the command kind
     * @return the stored value
     */
    public static String notRunnable(String kind) {
        return format(
                Reason.NOT_RUNNABLE,
                token(kind) + " command was deferred: this node holds no runnable plan for the instance's pinned"
                        + " definition");
    }

    /**
     * The value for a signal whose application failed in a way a retry repeats; the signal is rejected and its
     * instance is unchanged.
     *
     * @param signalName the signal's name, echoed bounded
     * @return the stored value
     */
    public static String signalFailure(String signalName) {
        return format(
                Reason.DETERMINISTIC_FAILURE,
                "SIGNAL command for signal '" + EchoedNames.bounded(signalName)
                        + "' failed with a non-retryable error and was rejected; the instance is unchanged;"
                        + " see the executor log");
    }

    /**
     * The value for a signal whose application executed more nodes than the step cap allows a segment.
     *
     * @param signalName the signal's name, echoed bounded
     * @param cap        the step cap that was exceeded
     * @return the stored value
     */
    public static String signalStepLimit(String signalName, int cap) {
        return format(
                Reason.STEP_LIMIT,
                "SIGNAL command for signal '" + EchoedNames.bounded(signalName) + "' executed more than " + cap
                        + " steps in one segment without reaching a wait and was rejected; the instance is unchanged");
    }

    /**
     * The value for a signal that the pinned definition version never waits for.
     *
     * @param signalName the signal's name, echoed bounded
     * @param version    the definition version the instance is pinned to
     * @return the stored value
     */
    public static String signalUndeclared(String signalName, long version) {
        return format(
                Reason.UNDECLARED_SIGNAL,
                "SIGNAL command rejected: signal '" + EchoedNames.bounded(signalName) + "' is not declared by version "
                        + version + " of the pinned definition");
    }

    /**
     * The value for a signal whose instance had ended.
     *
     * @param signalName the signal's name, echoed bounded
     * @param status     the status of the ended instance
     * @param stale      whether the signal is settled stale (a branch-addressed one) rather than rejected
     * @return the stored value
     */
    public static String signalInstanceEnded(String signalName, String status, boolean stale) {
        return format(
                Reason.INSTANCE_ENDED,
                "SIGNAL command for signal '" + EchoedNames.bounded(signalName) + "' was "
                        + (stale ? "settled stale" : "rejected") + ": instance ended with status " + token(status));
    }

    /**
     * The value for a branch-addressed signal whose branch was superseded or ended.
     *
     * @param signalName the signal's name, echoed bounded
     * @return the stored value
     */
    public static String signalBranchEnded(String signalName) {
        return format(
                Reason.BRANCH_ENDED,
                "SIGNAL command for signal '" + EchoedNames.bounded(signalName)
                        + "' is stale: its branch was superseded or ended");
    }

    /**
     * The value for a completion whose application failed in a way a retry repeats; the completion is rejected, its
     * task is open again and its instance is unchanged.
     *
     * @param decisionName the decision's name, echoed bounded
     * @return the stored value
     */
    public static String completionFailure(String decisionName) {
        return format(
                Reason.DETERMINISTIC_FAILURE,
                "COMPLETION command for decision '" + EchoedNames.bounded(decisionName)
                        + "' failed with a non-retryable error and was rejected; the task is open again;"
                        + " see the executor log");
    }

    /**
     * The value for a completion whose application executed more nodes than the step cap allows a segment.
     *
     * @param decisionName the decision's name, echoed bounded
     * @param cap          the step cap that was exceeded
     * @return the stored value
     */
    public static String completionStepLimit(String decisionName, int cap) {
        return format(
                Reason.STEP_LIMIT,
                "COMPLETION command for decision '" + EchoedNames.bounded(decisionName) + "' executed more than " + cap
                        + " steps in one segment without reaching a wait and was rejected; the task is open again");
    }

    /**
     * The value for a completion whose task is no longer reserved by it.
     *
     * @param decisionName the decision's name, echoed bounded
     * @return the stored value
     */
    public static String completionNotReserved(String decisionName) {
        return format(
                Reason.TASK_NOT_RESERVED,
                "COMPLETION command for decision '" + EchoedNames.bounded(decisionName)
                        + "' is stale: its task is no longer reserved by it");
    }

    /**
     * The value for a completion whose instance had failed.
     *
     * @param decisionName the decision's name, echoed bounded
     * @return the stored value
     */
    public static String completionInstanceFailed(String decisionName) {
        return format(
                Reason.INSTANCE_FAILED,
                "COMPLETION command for decision '" + EchoedNames.bounded(decisionName)
                        + "' was rejected: its instance has failed; the task is open again");
    }

    /**
     * The value for a completion whose instance had ended.
     *
     * @param decisionName the decision's name, echoed bounded
     * @param status       the status of the ended instance
     * @return the stored value
     */
    public static String completionInstanceEnded(String decisionName, String status) {
        return format(
                Reason.INSTANCE_ENDED,
                "COMPLETION command for decision '" + EchoedNames.bounded(decisionName)
                        + "' was rejected: instance ended with status " + token(status) + "; the task is cancelled");
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
