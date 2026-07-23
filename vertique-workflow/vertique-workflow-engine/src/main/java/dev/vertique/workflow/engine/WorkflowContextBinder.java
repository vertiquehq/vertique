// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableMetadata.MergePolicy;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binds effective durable context for one binder-row engine drive (Contract Appendix C2; PRD
 * FR-WF-CTX-020/024/025).
 *
 * <p>Instance-owned single-path drives — signal, cancel, retry, migrate, {@code taskCompleted},
 * {@code taskReassigned}, {@code taskDueFired}, {@code taskReminderFired}, {@code timerFired}, and
 * {@code timerFiringFailed} — route through {@link #withBound}, which implements the
 * <b>bind gate</b> and <b>base-wins/instance-fill</b> model:
 *
 * <ul>
 *   <li><b>Gate</b> — when both {@code explicitCarrier} is {@code null} AND
 *       {@link WorkflowInstance#metadata()} is {@code null}, no bind happens at all; {@code drive}
 *       runs exactly as it did before this carrier existed (FR-WF-CTX-012).
 *   <li><b>Base</b> — the explicit carrier wins when present; otherwise the base is the ambient
 *       durable context captured via {@link DurableContextPropagator#capture(String)} for the
 *       {@link DispatchBoundary#WORKFLOW} boundary.
 *   <li><b>Instance fill</b> — when the instance carries non-null metadata, it is merged into the
 *       base with {@link MergePolicy#CALLER_WINS}: the base is authoritative for any namespace
 *       present on both sides, and the instance only fills namespaces the base lacks
 *       (FR-WF-CTX-020/024).
 * </ul>
 *
 * <p>The merged effective document is installed via
 * {@link InboundExecutionContextScope#installDurable(DurableMetadata, String)} — this composes the
 * durable bind with every registered {@code InboundContextInitializer} (e.g. correlation seeding),
 * so instance fill always lands <em>before</em> initializers run and a namespace the instance
 * supplied is never re-seeded (PRD AC-9). {@code drive} is invoked <em>inside</em> the same
 * {@code try} block that installs the scope, exactly mirroring
 * {@link BranchTransitionEngine#driveBranchTransitions}'s scope-lifecycle shape: a synchronous
 * throw from {@code drive} is caught alongside a synchronous {@code installDurable} failure, the
 * scope is closed in the {@code catch}, and the exception is converted to a failed {@link Future} —
 * never propagated as a thrown exception. Once {@code drive} returns a {@link Future}, the scope
 * instead closes via {@link Future#eventually} so the binding survives the async drive and unwinds
 * on both success and failure (PRD NFR-WF-CTX-007 / FR-CTX-157b strictness propagation).
 *
 * <p>This class is a <b>binder-row</b> seam only — it must never be invoked for a branch-owned
 * drive (fork continuation, branch-targeted signal/timer/task). Those bind through
 * {@link BranchTransitionEngine#driveBranchTransitions}'s own carrier seam instead (the bind-once
 * routing rule, Contract Appendix C2/C3).
 */
@Singleton
final class WorkflowContextBinder {

    private static final Logger log = LoggerFactory.getLogger(WorkflowContextBinder.class);

    private final DurableContextPropagator propagator;
    private final InboundExecutionContextScope inboundExecScope;

    /**
     * Constructs the binder.
     *
     * @param propagator       durable context propagator used to capture the ambient base when no
     *                         explicit carrier is supplied; must not be {@code null}
     * @param inboundExecScope substrate lifecycle helper used to install the effective document and
     *                         run registered first-ingress initializers; must not be {@code null}
     */
    @Inject
    WorkflowContextBinder(DurableContextPropagator propagator, InboundExecutionContextScope inboundExecScope) {
        this.propagator = Objects.requireNonNull(propagator, "propagator");
        this.inboundExecScope = Objects.requireNonNull(inboundExecScope, "inboundExecScope");
    }

    /**
     * Returns a null-object binder whose {@link #withBound} runs {@code drive} with no scope
     * installed, unconditionally — the equivalent of the bind gate always being closed.
     *
     * <p>Used in place of a {@code null} {@link WorkflowContextBinder} reference: callers that used
     * to guard every call site on nullability (falling back to running the drive unwrapped) instead
     * substitute this instance and call {@link #withBound} directly, keeping the field
     * non-{@code @Nullable} and removing the scattered null-checks.
     *
     * @return a binder that always runs the drive unbound
     */
    static WorkflowContextBinder noop() {
        return NOOP;
    }

    private static final WorkflowContextBinder NOOP = new WorkflowContextBinder();

    /**
     * Private constructor for {@link #NOOP} only — never installs a scope, so it needs neither a
     * propagator nor an inbound-execution scope.
     */
    private WorkflowContextBinder() {
        this.propagator = null;
        this.inboundExecScope = null;
    }

    /**
     * Binds the effective durable context for one binder-row drive and runs {@code drive} inside
     * it, per the gate / base-wins / instance-fill model documented on this class.
     *
     * @param instance        the loaded workflow instance whose {@link WorkflowInstance#metadata()}
     *                        supplies the instance-fill half of the effective document
     * @param explicitCarrier an explicit signal carrier overriding the ambient capture as the base,
     *                        or {@code null} to use the ambient capture
     * @param drive           the drive to run, invoked either with no new scope (gate closed) or
     *                        inside the installed durable scope (gate open), via
     *                        {@link InboundExecutionContextScope#installDurableAndRun}; when the gate
     *                        is open, a synchronous {@link RuntimeException} thrown by {@code drive}
     *                        itself (as opposed to a returned failed {@link Future}) is caught, the
     *                        scope is closed, and the exception is surfaced as a failed {@link Future}
     *                        — never rethrown (see {@code installDurableAndRun}'s javadoc)
     * @param <T>             the drive's result type
     * @return a future completing with the drive's result; fails with the drive's cause whether that
     *     cause was a failed {@link Future} returned by {@code drive} or a {@link RuntimeException}
     *     {@code drive} threw synchronously
     */
    <T> Future<T> withBound(
            WorkflowInstance instance, @Nullable DurableMetadata explicitCarrier, Supplier<Future<T>> drive) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(drive, "drive");

        if (this == NOOP) {
            // Null-object short-circuit: run the drive unbound, before any capture/merge/bind —
            // callers that previously guarded on a null WorkflowContextBinder field now substitute
            // this instance instead and call withBound directly (see #noop()).
            return drive.get();
        }

        DurableMetadata instanceMetadata = instance.metadata();
        if (explicitCarrier == null && instanceMetadata == null) {
            // Gate: no carrier signal at all — run as today, no new bind (FR-WF-CTX-012).
            return drive.get();
        }

        DurableMetadata base =
                explicitCarrier != null ? explicitCarrier : propagator.capture(DispatchBoundary.WORKFLOW);
        DurableMetadata effective =
                instanceMetadata == null ? base : base.merge(instanceMetadata, MergePolicy.CALLER_WINS);

        logFillIfAny(base, effective, instance);

        return inboundExecScope.installDurableAndRun(effective, DispatchBoundary.WORKFLOW, drive);
    }

    /**
     * Emits a debug-level log naming every namespace the instance fill contributed to the effective
     * document (i.e. present in {@code effective} but absent from {@code base}), when at least one
     * such namespace exists (NFR-WF-CTX-008).
     *
     * @param base      the base document before instance fill (explicit carrier or ambient capture)
     * @param effective the merged effective document
     * @param instance  the instance being bound, for log correlation
     */
    private static void logFillIfAny(DurableMetadata base, DurableMetadata effective, WorkflowInstance instance) {
        if (!log.isDebugEnabled()) {
            return;
        }
        var filled = effective.namespaces().stream().filter(ns -> !base.has(ns)).toList();
        if (!filled.isEmpty()) {
            log.debug(
                    "workflow instance {} durable context bind: instance metadata filled namespace(s) {} absent from the base carrier",
                    instance.id().value(),
                    filled);
        }
    }
}
