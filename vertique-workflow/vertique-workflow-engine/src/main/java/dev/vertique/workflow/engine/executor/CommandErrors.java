// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.registry.store.DefinitionFailure;
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
        /** The command's instance was cancelled, so the command was cancelled with it. */
        INSTANCE_CANCELLED,
        /** This node holds no plan for the instance's pin, so the command was deferred. */
        NOT_RUNNABLE,
        /** A start resolved to a version this node evaluated as failing, so it was refused. */
        DEFINITION_FAILING,
        /** A signal names no wait of the definition version its instance is pinned to. */
        UNDECLARED_SIGNAL,
        /** A branch-addressed signal arrived after its branch was superseded or ended. */
        BRANCH_ENDED,
        /** A completion's task is no longer reserved by that completion. */
        TASK_NOT_RESERVED,
        /** A completion's instance had failed, so it cannot take the decision. */
        INSTANCE_FAILED,
        /** A timer's wait was left, or its task closed, before the timer ran. */
        TIMER_NOT_AWAITED,
        /** A dead-lettered start was replaced by a retry that initialized the instance. */
        SUPERSEDED,
        /** A retry found nothing left to recover. */
        NOTHING_TO_RECOVER,
        /** A migration is not valid for its instance, so it was rejected and the instance is unchanged. */
        MIGRATION_REJECTED
    }

    /** Why a migration was rejected; each is a fixed framework statement, never text of a failure. */
    public enum MigrationRejection {
        /** The instance is not in a state that can be migrated. */
        INSTANCE_STATE("the instance is in a status that cannot be migrated, or holds active branches"),
        /** The target is not a version the instance may move to. */
        TARGET_NOT_ADMITTED(
                "the target version is not visible to the instance, names another contract, changes the state type"
                        + " incompatibly, or is not admitted for a new pin"),
        /** The target version is deprecated. */
        TARGET_DEPRECATED("the target version is deprecated"),
        /** No handler is registered for the move. */
        HANDLER_MISSING("no migration handler is registered for the source and target version"),
        /** The handler's state types are not the state types of the definitions. */
        STATE_TYPE_MISMATCH("the handler's state types do not match the state types of the definitions"),
        /** The handler or the re-pin failed. */
        FAILED("the migration handler or the re-pin failed; see the executor log");

        private final String text;

        MigrationRejection(String text) {
            this.text = text;
        }
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
     * The value for a command that was cancelled because its instance was cancelled, whether the cancellation settled
     * it or its instance was found cancelled when the command ran.
     *
     * @param kind the command kind
     * @return the stored value
     */
    public static String instanceCancelled(String kind) {
        return format(
                Reason.INSTANCE_CANCELLED, token(kind) + " command was cancelled because its instance was cancelled");
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
     * The value for a command refused because the version it runs is failing on this node.
     *
     * @param kind the command kind
     * @param rule the rule the version breaks
     * @return the stored value
     */
    public static String definitionFailing(String kind, DefinitionFailure.Rule rule) {
        return format(
                Reason.DEFINITION_FAILING,
                token(kind) + " command was refused: the definition version it runs is failing (" + rule
                        + ") and does not accept new starts");
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
     * The value for a timer whose wait was left, or whose task was closed, before the timer ran; the timer is stale and
     * its instance is unchanged.
     *
     * @param timerKind the timer kind
     * @return the stored value
     */
    public static String timerNotAwaited(String timerKind) {
        return format(
                Reason.TIMER_NOT_AWAITED,
                "TIMER command (" + token(timerKind) + ") was settled as stale: its wait is no longer awaited");
    }

    /**
     * The value for a timer whose instance had failed, so the timer cannot run.
     *
     * @param timerKind the timer kind
     * @return the stored value
     */
    public static String timerInstanceFailed(String timerKind) {
        return format(
                Reason.INSTANCE_FAILED,
                "TIMER command (" + token(timerKind) + ") was settled as stale: its instance has failed");
    }

    /**
     * The value for a command that a retry replaced.
     *
     * @param kind the command kind
     * @return the stored value
     */
    public static String superseded(String kind) {
        return format(
                Reason.SUPERSEDED,
                token(kind) + " command was superseded by a retry that initialized the instance from the stored start");
    }

    /**
     * The value for a retry that found nothing to recover.
     *
     * @return the stored value
     */
    public static String nothingToRecover() {
        return format(
                Reason.NOTHING_TO_RECOVER,
                "RETRY command was settled as stale: the instance is not failed and holds no dead-lettered command");
    }

    /**
     * The value for a migration that was rejected; the instance is unchanged on its previous version.
     *
     * @param rejection why the migration was rejected
     * @return the stored value
     */
    public static String migrationRejected(MigrationRejection rejection) {
        return format(
                Reason.MIGRATION_REJECTED,
                "MIGRATION command was rejected: " + rejection.text + "; the instance is unchanged");
    }

    /**
     * The value for a migration whose handler and re-drive executed more nodes than the step cap allows a segment.
     *
     * @param cap the step cap that was exceeded
     * @return the stored value
     */
    public static String migrationStepLimit(int cap) {
        return format(
                Reason.STEP_LIMIT,
                "MIGRATION command executed more than " + cap
                        + " steps in one segment without reaching a wait and was rejected; the instance is unchanged");
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
