// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.state.WorkflowStatus;
import jakarta.annotation.Nullable;
import java.util.Optional;

/**
 * INTERNAL: the one place that decides what becomes of a command that meets an ended or failed instance.
 *
 * <p>The table, by the status the instance has when the command runs:
 *
 * <table>
 *   <caption>Outcome of a command that meets an ended or failed instance</caption>
 *   <tr><th>Command</th><th>{@code FAILED}</th><th>{@code COMPLETED}, {@code COMPENSATED}, {@code EXPIRED}</th><th>{@code CANCELLED}</th></tr>
 *   <tr><td>Signal, instance-addressed</td><td>parked</td><td>rejected</td><td>cancelled</td></tr>
 *   <tr><td>Signal, branch-addressed</td><td>stale if its branch ended, else parked</td><td>stale</td><td>cancelled</td></tr>
 *   <tr><td>Task completion</td><td>rejected, reservation reopened</td><td>rejected, task cancelled</td><td>cancelled, task cancelled</td></tr>
 *   <tr><td>Retry</td><td>executes</td><td>rejected</td><td>cancelled</td></tr>
 *   <tr><td>Migration</td><td>rejected</td><td>rejected</td><td>cancelled</td></tr>
 *   <tr><td>Timer</td><td>stale</td><td>stale</td><td>cancelled</td></tr>
 * </table>
 *
 * <p>Any other command (a start) that meets an ended instance is rejected, or cancelled when the instance was cancelled.
 * Every reason is built by {@link CommandErrors}: a reason code and a framework statement, never a payload value or
 * exception text. The executor's claim, the segment of each command kind and the settlement of an instance's parked and
 * dead-lettered commands all read this table; none evaluates it on its own.
 */
public final class EndedInstanceOutcomes {

    /** How the command settles. */
    public enum Settlement {
        /** The command is rejected. */
        REJECTED,
        /** The command is stale: it addresses something that is no longer there. */
        STALE,
        /** The command is cancelled with its instance. */
        CANCELLED,
        /** The command is parked: the instance may yet resume ({@code FAILED} only). */
        PARKED,
        /** The command executes as if the instance were live ({@code FAILED} only; a retry). */
        EXECUTES
    }

    /** What happens to the task a completion reserves. */
    public enum Reservation {
        /** The command reserves no task, or leaves it as it is. */
        NONE,
        /** The reservation is reopened: the task is open again. */
        REOPEN,
        /** The task can no longer be completed and is cancelled. */
        CANCEL
    }

    /**
     * What becomes of a command.
     *
     * @param settlement how it settles
     * @param reason     the stored reason, {@code null} when the command executes or is parked without one
     * @param reservation what happens to the task a completion reserves
     */
    public record Outcome(Settlement settlement, @Nullable String reason, Reservation reservation) {}

    /**
     * What identifies the command beyond its kind, for the reason that is stored.
     *
     * @param name            a signal's or a decision's name, echoed bounded; {@code null} when the command names none
     * @param branchAddressed whether a signal addresses a branch
     * @param branchEnded     whether the branch a signal addresses has ended or was superseded
     * @param timerKind       a timer command's kind, or {@code null}
     */
    public record Target(
            @Nullable String name,
            boolean branchAddressed,
            boolean branchEnded,
            @Nullable String timerKind) {

        /** A command that is told apart by its kind alone. */
        public static Target none() {
            return new Target(null, false, false, null);
        }

        /**
         * A signal.
         *
         * @param name            the signal's name
         * @param branchAddressed whether it addresses a branch
         * @param branchEnded     whether that branch has ended or was superseded
         * @return the target
         */
        public static Target signal(@Nullable String name, boolean branchAddressed, boolean branchEnded) {
            return new Target(name, branchAddressed, branchEnded, null);
        }

        /**
         * A completion.
         *
         * @param decisionName the decision's name, or {@code null} when it is not known
         * @return the target
         */
        public static Target completion(@Nullable String decisionName) {
            return new Target(decisionName, false, false, null);
        }

        /**
         * A timer command.
         *
         * @param timerKind the timer kind
         * @return the target
         */
        public static Target timer(@Nullable String timerKind) {
            return new Target(null, false, false, timerKind);
        }
    }

    private EndedInstanceOutcomes() {
        // static table
    }

    /**
     * Tells whether the status is ended: the instance takes no further command. {@code FAILED} is not ended, because a
     * retry can resume it.
     *
     * @param status the instance status
     * @return {@code true} for {@code COMPLETED}, {@code COMPENSATED}, {@code CANCELLED} and {@code EXPIRED}
     */
    public static boolean isEnded(WorkflowStatus status) {
        return switch (status) {
            case COMPLETED, COMPENSATED, CANCELLED, EXPIRED -> true;
            default -> false;
        };
    }

    /**
     * Tells whether the status name is ended, as {@link #isEnded(WorkflowStatus)}.
     *
     * @param status the instance status name
     * @return {@code true} when ended
     */
    public static boolean isEnded(String status) {
        return switch (status) {
            case "COMPLETED", "COMPENSATED", "CANCELLED", "EXPIRED" -> true;
            default -> false;
        };
    }

    /**
     * Decides what becomes of a command that meets an instance in the given status.
     *
     * @param kind   the command kind
     * @param status the instance status name
     * @param target what identifies the command beyond its kind
     * @return the outcome, or empty when the instance is neither ended nor failed and the command runs as usual
     */
    public static Optional<Outcome> of(String kind, String status, Target target) {
        boolean cancelled = "CANCELLED".equals(status);
        boolean ended = isEnded(status);
        boolean failed = "FAILED".equals(status);
        if (!ended && !failed) {
            return Optional.empty();
        }
        if (cancelled) {
            return Optional.of(new Outcome(
                    Settlement.CANCELLED,
                    CommandErrors.instanceCancelled(kind),
                    "COMPLETION".equals(kind) ? Reservation.CANCEL : Reservation.NONE));
        }
        return Optional.of(
                switch (kind) {
                    case "SIGNAL" -> signal(status, ended, target);
                    case "COMPLETION" -> completion(status, ended, target);
                    case "RETRY" ->
                        ended
                                ? new Outcome(
                                        Settlement.REJECTED,
                                        CommandErrors.instanceEnded(kind, status),
                                        Reservation.NONE)
                                : new Outcome(Settlement.EXECUTES, null, Reservation.NONE);
                    case "MIGRATION" ->
                        new Outcome(
                                Settlement.REJECTED,
                                ended
                                        ? CommandErrors.instanceEnded(kind, status)
                                        : CommandErrors.migrationRejected(
                                                CommandErrors.MigrationRejection.INSTANCE_STATE),
                                Reservation.NONE);
                    case "TIMER" ->
                        new Outcome(
                                Settlement.STALE,
                                ended
                                        ? CommandErrors.instanceEnded(kind, status)
                                        : CommandErrors.timerInstanceFailed(target.timerKind()),
                                Reservation.NONE);
                    default ->
                        new Outcome(Settlement.REJECTED, CommandErrors.instanceEnded(kind, status), Reservation.NONE);
                });
    }

    /**
     * Decides what becomes of a parked or dead-lettered command when its instance has ended and the instance's end
     * settles it: rejected, or cancelled when the instance was cancelled. A signal's reason names the signal, any other
     * command's its kind.
     *
     * @param kind       the command kind
     * @param status     the ended instance's status name
     * @param signalName the signal's name for a {@code SIGNAL}, otherwise {@code null}
     * @return the outcome
     */
    public static Outcome atEnd(String kind, String status, @Nullable String signalName) {
        Reservation reservation = "COMPLETION".equals(kind) ? Reservation.CANCEL : Reservation.NONE;
        if ("CANCELLED".equals(status)) {
            return new Outcome(Settlement.CANCELLED, CommandErrors.instanceCancelled(kind), reservation);
        }
        return new Outcome(
                Settlement.REJECTED,
                "SIGNAL".equals(kind)
                        ? CommandErrors.signalInstanceEnded(signalName, status, false)
                        : CommandErrors.instanceEnded(kind, status),
                reservation);
    }

    private static Outcome signal(String status, boolean ended, Target target) {
        if (!target.branchAddressed()) {
            return ended
                    ? new Outcome(
                            Settlement.REJECTED,
                            CommandErrors.signalInstanceEnded(target.name(), status, false),
                            Reservation.NONE)
                    : new Outcome(Settlement.PARKED, null, Reservation.NONE);
        }
        if (ended) {
            return new Outcome(
                    Settlement.STALE, CommandErrors.signalInstanceEnded(target.name(), status, true), Reservation.NONE);
        }
        return target.branchEnded()
                ? new Outcome(Settlement.STALE, CommandErrors.signalBranchEnded(target.name()), Reservation.NONE)
                : new Outcome(Settlement.PARKED, null, Reservation.NONE);
    }

    private static Outcome completion(String status, boolean ended, Target target) {
        if (ended) {
            return new Outcome(
                    Settlement.REJECTED,
                    target.name() == null
                            ? CommandErrors.instanceEnded("COMPLETION", status)
                            : CommandErrors.completionInstanceEnded(target.name(), status),
                    Reservation.CANCEL);
        }
        return new Outcome(
                Settlement.REJECTED, CommandErrors.completionInstanceFailed(target.name()), Reservation.REOPEN);
    }
}
