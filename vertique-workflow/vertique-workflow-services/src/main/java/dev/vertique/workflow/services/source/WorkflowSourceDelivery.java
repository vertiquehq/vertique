// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.extension.ObserverFailureReporter;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.source.SignalMessage;
import dev.vertique.workflow.source.SignalSourceEvent;
import dev.vertique.workflow.source.SignalSourceObserver;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the messages of a correlated signal source and records their signals, each under the identity
 * its envelope names.
 *
 * <p><strong>INTERNAL.</strong> This type is an internal seam, outside the maturity promise of
 * {@code vertique-workflow-services}. It is public only because a transport in another repository
 * needs it; do not depend on it from application code.
 *
 * <p>The caller passes resolved values only: a {@link SourceEnvelope} holding the security context,
 * an optional correlation, the invocation origin and the function that builds a signal's provenance.
 * Each recording runs in its own transaction on its own duplicated Vert.x context, created from the
 * root of the caller's context so that nothing the caller has bound reaches it. The delivery binds
 * exactly the envelope's security context, correlation (when it has one) and origin there, through
 * {@link InboundExecutionContextScope#installDispatch}, and closes the binding when the recording's
 * future completes, whether it succeeded or failed. The command's actor therefore comes from the
 * envelope's security context, and two deliveries in flight never see each other's values.
 *
 * <p>The future a delivery returns completes on the context the caller delivered from, after the
 * binding is closed. Nothing here blocks.
 *
 * <p>{@link #deliver} runs a whole {@link SignalSourcePlan} for one message. It captures its calling
 * context once, runs every stage and every recording on a fresh duplicate of it, and returns to the
 * captured context before it starts the next stage, because an application future may complete on
 * another context. It answers one {@link SourceDisposition} per message and reports the outcome of each
 * signal through {@link #report}.
 *
 * <p>The bean also tells the registered {@link SignalSourceObserver}s about an outcome through
 * {@link #report(SignalSourceEvent)}, which isolates every observer.
 *
 * <p>The class has a public constructor and no injectable constructor: only {@code WorkflowSourceModule}
 * binds it, so another module can declare an optional binding for it.
 */
@Slf4j
@Singleton
public final class WorkflowSourceDelivery {

    private static final String OBSERVER_CALLBACK = "onOutcome";

    private final Provider<TransactionalWorkflowOperations<SqlClient>> operations;
    private final Pool pool;
    private final Vertx vertx;
    private final InboundExecutionContextScope contextScope;
    private final Set<SignalSourceObserver> observers;
    private final ObserverFailureReporter observerFailureReporter;

    /**
     * Creates the delivery with its own observer failure reporter.
     *
     * @param operations the transactional workflow operations, resolved per recording
     * @param pool the pool whose transaction a recording runs in
     * @param vertx the Vert.x instance, for the calling context
     * @param contextScope installs the envelope's values on a delivery's context
     * @param observers the observers that {@link #report(SignalSourceEvent)} tells
     */
    public WorkflowSourceDelivery(
            Provider<TransactionalWorkflowOperations<SqlClient>> operations,
            Pool pool,
            Vertx vertx,
            InboundExecutionContextScope contextScope,
            Set<SignalSourceObserver> observers) {
        this(
                operations,
                pool,
                vertx,
                contextScope,
                observers,
                new ObserverFailureReporter(log, "Workflow signal source observer"));
    }

    /**
     * Creates the delivery with the given observer failure reporter, so a proof can observe it.
     *
     * @param operations the transactional workflow operations, resolved per recording
     * @param pool the pool whose transaction a recording runs in
     * @param vertx the Vert.x instance, for the calling context
     * @param contextScope installs the envelope's values on a delivery's context
     * @param observers the observers that {@link #report(SignalSourceEvent)} tells
     * @param observerFailureReporter receives what an observer throws
     */
    WorkflowSourceDelivery(
            Provider<TransactionalWorkflowOperations<SqlClient>> operations,
            Pool pool,
            Vertx vertx,
            InboundExecutionContextScope contextScope,
            Set<SignalSourceObserver> observers,
            ObserverFailureReporter observerFailureReporter) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.contextScope = Objects.requireNonNull(contextScope, "contextScope");
        this.observers = Objects.requireNonNull(observers, "observers");
        this.observerFailureReporter = Objects.requireNonNull(observerFailureReporter, "observerFailureReporter");
    }

    /**
     * Runs one message through a source plan and answers what the transport should do with it.
     *
     * <p>The stages run in this order: {@code when}, the dedup key, {@code dispatchAs} (unless the
     * envelope's identity was contributed), {@code scope}; then, for each signal in the order written,
     * its key, its payload and its recording. A message an earlier stage excludes or refuses reaches no
     * later stage. Each signal is judged alone and reported once; the disposition is the most severe of
     * them, {@link SourceDisposition.Fate#TERMINAL} over {@link SourceDisposition.Fate#RETRY} over
     * {@link SourceDisposition.Fate#WAIT} over {@link SourceDisposition.Fate#DONE}.
     *
     * <p>The disposition and the events carry the type of a failure, never its message, and nothing of
     * the message: no key, header or payload.
     *
     * @param plan the source's plan
     * @param message the decoded message
     * @param envelope the resolved values the transport supplies for the message
     * @param <T> the type of the decoded message body
     * @return a future that completes, on the context the caller delivered from, with the disposition;
     *     it fails with a {@link NullPointerException} when an argument is {@code null}
     */
    public <T> Future<SourceDisposition> deliver(
            SignalSourcePlan<T> plan, SignalMessage<T> message, SourceEnvelope envelope) {
        try {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(envelope, "envelope");
        } catch (NullPointerException e) {
            return Future.failedFuture(e);
        }
        return new SignalSourceRun<>(this, plan, message, envelope, capture()).run();
    }

    /**
     * Whether this node accepts new signals now. A transport that can hold a message back asks before it delivers one,
     * so a node that refuses intake keeps the message in the transport and not in a retry. The answer reads node-local
     * state only and may be read from any thread.
     *
     * @return {@code true} unless the node refuses new signals
     */
    public boolean admitting() {
        return operations.get().admitting();
    }

    /**
     * Tells every registered observer about an outcome.
     *
     * <p>An {@link Exception}, {@link LinkageError}, {@link AssertionError} or
     * {@link StackOverflowError} thrown by one observer is caught and handed to the failure reporter
     * with the observer's class and the callback name; it never reaches the caller and does not stop
     * the remaining observers. Any other {@link Error} propagates. A throw never changes a message's
     * fate. The method returns on the calling context and does not block.
     *
     * @param event the outcome to report; carries no key, header or payload
     */
    public void report(SignalSourceEvent event) {
        for (SignalSourceObserver observer : observers) {
            try {
                observer.onOutcome(event);
            } catch (Exception | LinkageError | AssertionError | StackOverflowError e) {
                observerFailureReporter.report(observer.getClass(), OBSERVER_CALLBACK, e);
            }
        }
    }

    /**
     * Records one signal under the envelope's binding, in its own transaction, completing on a context
     * the caller has already captured, so that one capture serves every stage of a message.
     *
     * <p>The signal is recorded with the provenance the envelope builds for the signal's own dedup key,
     * through {@code signalByBusinessKey} when {@code businessKey} is true and {@code signalByKey}
     * otherwise.
     *
     * @param captured the context the caller delivers from
     * @param signal the signal to record
     * @param businessKey whether the signal addresses instances by business key rather than by key
     * @param envelope the resolved values to bind for the recording
     * @return a future that completes on {@code captured} with the receipt, or fails with what the
     *     operations failed with
     */
    Future<CorrelatedSignalReceipt> record(
            ContextInternal captured, CorrelatedSignal signal, boolean businessKey, SourceEnvelope envelope) {
        return runBound(
                captured,
                bindings(envelope),
                envelope.transport(),
                () -> pool.withTransaction(connection -> {
                    CorrelatedSignal withProvenance =
                            signal.withProvenance(envelope.provenance().apply(signal.dedupKey()));
                    TransactionalWorkflowOperations<SqlClient> ops = operations.get();
                    return businessKey
                            ? ops.signalByBusinessKey(withProvenance, connection)
                            : ops.signalByKey(withProvenance, connection);
                }));
    }

    /**
     * Returns the Vert.x instance, for the timers a stage is bounded by.
     *
     * @return the Vert.x instance
     */
    Vertx vertx() {
        return vertx;
    }

    /**
     * Captures the context the caller delivers from. A caller captures it once per message, so one
     * capture serves every stage of the message.
     *
     * @return the calling context
     */
    ContextInternal capture() {
        return (ContextInternal) vertx.getOrCreateContext();
    }

    /**
     * Runs the action on a new duplicated context with the values installed there, and completes on
     * the captured context once the action's future has completed and the values are released.
     *
     * <p>The duplicate is created from the root of the captured context, so a value the caller has
     * bound on its own duplicate is not inherited. It is created per call and never reused. The
     * binding is released on every exit: success, a failed future and a synchronous throw. A failure
     * reaches the caller as the same instance.
     *
     * @param captured the context the caller delivers from, completed on at the end
     * @param values the values to install, keyed by class name
     * @param boundary the dispatch boundary the values come from
     * @param action starts the work that needs the values, on the duplicate
     * @param <T> the result type
     * @return a future that completes on {@code captured}
     */
    <T> Future<T> runBound(
            ContextInternal captured, Map<String, Object> values, String boundary, Supplier<Future<T>> action) {
        Promise<T> result = captured.promise();
        ContextInternal delivery = captured.unwrap().duplicate();
        delivery.runOnContext(ignored -> start(values, boundary, action).onComplete(result));
        return result.future();
    }

    private <T> Future<T> start(Map<String, Object> values, String boundary, Supplier<Future<T>> action) {
        ContextHolder.Scope binding;
        try {
            binding = contextScope.installDispatch(values, boundary);
        } catch (Exception | LinkageError | AssertionError e) {
            return Future.failedFuture(e);
        }
        Future<T> work;
        try {
            work = Objects.requireNonNull(action.get(), "the action returned null instead of a future");
        } catch (Exception | LinkageError | AssertionError e) {
            work = Future.failedFuture(e);
        }
        return work.eventually(() -> {
            binding.close();
            return Future.succeededFuture();
        });
    }

    private static Map<String, Object> bindings(SourceEnvelope envelope) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SecurityContext.class.getName(), envelope.security());
        CorrelationContext correlation = envelope.correlation();
        if (correlation != null) {
            values.put(CorrelationContext.class.getName(), correlation);
        }
        values.put(InvocationOrigin.class.getName(), envelope.origin());
        return values;
    }
}
