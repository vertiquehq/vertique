// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.reply;

import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.inboxoutbox.OutboxOutcome;
import dev.vertique.inboxoutbox.OutboxOutcomeConsumer;
import dev.vertique.inboxoutbox.OutboxReplies;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SystemIdentities;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.RelayedSignals;
import dev.vertique.workflow.ops.ReplyOutcomeObserver;
import dev.vertique.workflow.ops.ReplyPayloadBounds;
import dev.vertique.workflow.ops.ServiceFailure;
import dev.vertique.workflow.ops.SignalProvenance;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.ReplySignals;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Provider;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class WorkflowReplyConsumer implements OutboxOutcomeConsumer, OutboxReplies {
    private final Provider<RelayedSignals<SqlClient>> signals;
    private final Pool pool;
    private final Vertx vertx;
    private final InboundExecutionContextScope scope;
    private final Set<ReplyOutcomeObserver> observers;
    private final ContextHolder contextHolder;
    private final int maxPayloadBytes;

    WorkflowReplyConsumer(
            Provider<RelayedSignals<SqlClient>> signals,
            Pool pool,
            Vertx vertx,
            InboundExecutionContextScope scope,
            Set<ReplyOutcomeObserver> observers,
            ContextHolder contextHolder,
            WorkflowRecordingConfig recording) {
        this.signals = signals;
        this.pool = pool;
        this.vertx = vertx;
        this.scope = scope;
        this.observers = observers;
        this.contextHolder = contextHolder;
        this.maxPayloadBytes = recording.maxPayloadBytes();
    }

    @Override
    public Future<Boolean> awaits(String text) {
        ReplyHandle handle;
        try {
            handle = ReplyHandle.parse(text);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        return bound(
                        Map.of(),
                        () -> pool.withTransaction(tx -> signals.get()
                                .waitOpen(new WorkflowInstanceId(handle.instanceId()), handle.waitId(), tx)))
                .map(open -> {
                    if (!open) report(ReplyOutcomeObserver.Outcome.STALE);
                    return open;
                });
    }

    @Override
    public Future<Void> onOutcome(OutboxOutcome outcome) {
        ReplyHandle handle;
        try {
            handle = ReplyHandle.parse(outcome.handle());
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        Map<String, Object> values = new HashMap<>();
        Object correlation = outcome.dispatchContext().get(CorrelationContext.class.getName());
        if (correlation != null) values.put(CorrelationContext.class.getName(), correlation);
        WorkflowInstanceId id = new WorkflowInstanceId(handle.instanceId());
        SignalProvenance provenance = SignalProvenance.outbox(outcome.destination(), outcome.entryId());
        return bound(
                        values,
                        () -> pool.withTransaction(tx -> {
                            RelayedSignals<SqlClient> ops = signals.get();
                            Future<ReplyOutcomeObserver.Outcome> recording;
                            if (outcome instanceof OutboxOutcome.Success success) {
                                recording = ops.signalWait(id, handle.waitId(), success.value(), provenance, tx)
                                        .map(WorkflowReplyConsumer::recordedOutcome)
                                        .recover(error -> error instanceof WorkflowInvalidPayloadException
                                                ? ops.failWait(
                                                                id,
                                                                handle.waitId(),
                                                                new ServiceFailure(
                                                                        ServiceFailure.Reason.REPLY_REFUSED,
                                                                        outcome.attempts()),
                                                                provenance,
                                                                tx)
                                                        .map(receipt -> receipt.outcome()
                                                                        == CorrelatedSignalReceipt.Outcome.DUPLICATE
                                                                ? ReplyOutcomeObserver.Outcome.DUPLICATE
                                                                : ReplyOutcomeObserver.Outcome.REFUSED)
                                                : Future.failedFuture(error));
                            } else {
                                OutboxOutcome.Failure failure = (OutboxOutcome.Failure) outcome;
                                ServiceFailure.Reason reason = failure.kind() == OutboxOutcome.Failure.Kind.PERMANENT
                                        ? ServiceFailure.Reason.PERMANENT
                                        : ServiceFailure.Reason.RETRIES_EXHAUSTED;
                                recording = ops.failWait(
                                                id,
                                                handle.waitId(),
                                                new ServiceFailure(reason, outcome.attempts()),
                                                provenance,
                                                tx)
                                        .map(WorkflowReplyConsumer::recordedOutcome);
                            }
                            return recording.recover(error -> error instanceof WorkflowSignalNoMatchException
                                    ? Future.succeededFuture(ReplyOutcomeObserver.Outcome.STALE)
                                    : Future.failedFuture(error));
                        }))
                .map(recorded -> {
                    report(recorded);
                    return (Void) null;
                });
    }

    @Override
    public Future<Completion> complete(String handle, Object value) {
        return completeOutcome(handle, value, false);
    }

    @Override
    public Future<Completion> fail(String handle) {
        return completeOutcome(handle, null, true);
    }

    private Future<Completion> completeOutcome(String text, Object value, boolean failure) {
        ReplyHandle handle;
        try {
            handle = ReplyHandle.parse(text);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        Map<String, Object> values = new HashMap<>();
        contextHolder
                .current(SecurityContext.class)
                .filter(security -> security.identity().actor().type() != PrincipalType.ANONYMOUS)
                .ifPresent(security -> values.put(SecurityContext.class.getName(), security));
        contextHolder
                .current(dev.vertique.security.SnapshotDegradationMarker.class)
                .ifPresent(
                        marker -> values.put(dev.vertique.security.SnapshotDegradationMarker.class.getName(), marker));
        WorkflowInstanceId id = new WorkflowInstanceId(handle.instanceId());
        return bound(
                        values,
                        () -> pool.withTransaction(tx -> {
                            RelayedSignals<SqlClient> ops = signals.get();
                            SignalProvenance provenance = SignalProvenance.completed();
                            Future<CorrelatedSignalReceipt> recorded = failure
                                    ? ops.failWait(
                                            id,
                                            handle.waitId(),
                                            new ServiceFailure(ServiceFailure.Reason.REPORTED, 0),
                                            provenance,
                                            tx)
                                    : ops.signalWait(id, handle.waitId(), value, provenance, tx);
                            return recorded.compose(receipt -> {
                                        if (receipt.outcome() == CorrelatedSignalReceipt.Outcome.RECORDED)
                                            return Future.succeededFuture(new CompletionDecision(
                                                    Completion.RECORDED, ReplyOutcomeObserver.Outcome.RECORDED));
                                        if (!failure) ReplyPayloadBounds.validate(value, maxPayloadBytes);
                                        return ops.recordedOutcomeName(id, handle.waitId(), tx)
                                                .compose(name -> {
                                                    if (name.isEmpty())
                                                        return Future.failedFuture(new IllegalStateException(
                                                                "Recorded reply outcome is missing"));
                                                    boolean same = ReplySignals.isFailure(name.get()) == failure;
                                                    return Future.succeededFuture(new CompletionDecision(
                                                            same ? Completion.DUPLICATE : Completion.STALE,
                                                            same
                                                                    ? ReplyOutcomeObserver.Outcome.DUPLICATE
                                                                    : ReplyOutcomeObserver.Outcome.STALE));
                                                });
                                    })
                                    .recover(error -> {
                                        if (error instanceof WorkflowSignalNoMatchException)
                                            return Future.succeededFuture(new CompletionDecision(
                                                    Completion.STALE, ReplyOutcomeObserver.Outcome.STALE));
                                        if (!failure && error instanceof WorkflowInvalidPayloadException) {
                                            return ops.failWait(
                                                            id,
                                                            handle.waitId(),
                                                            new ServiceFailure(ServiceFailure.Reason.REPLY_REFUSED, 0),
                                                            provenance,
                                                            tx)
                                                    .map(ignored -> new CompletionDecision(
                                                            Completion.STALE, ReplyOutcomeObserver.Outcome.REFUSED))
                                                    .recover(fail -> fail instanceof WorkflowSignalNoMatchException
                                                            ? Future.succeededFuture(new CompletionDecision(
                                                                    Completion.STALE,
                                                                    ReplyOutcomeObserver.Outcome.STALE))
                                                            : Future.failedFuture(fail));
                                        }
                                        return Future.failedFuture(error);
                                    });
                        }))
                .map(result -> {
                    report(result.outcome());
                    return result.completion();
                });
    }

    private record CompletionDecision(Completion completion, ReplyOutcomeObserver.Outcome outcome) {}

    private static ReplyOutcomeObserver.Outcome recordedOutcome(CorrelatedSignalReceipt receipt) {
        return receipt.outcome() == CorrelatedSignalReceipt.Outcome.DUPLICATE
                ? ReplyOutcomeObserver.Outcome.DUPLICATE
                : ReplyOutcomeObserver.Outcome.RECORDED;
    }

    private <T> Future<T> bound(Map<String, Object> input, Supplier<Future<T>> action) {
        ContextInternal captured = (ContextInternal) vertx.getOrCreateContext();
        Promise<T> result = captured.promise();
        ContextInternal duplicate = captured.unwrap().duplicate();
        duplicate.runOnContext(ignored -> {
            Map<String, Object> values = new HashMap<>(input);
            values.putIfAbsent(
                    SecurityContext.class.getName(),
                    SecurityContexts.system(SystemIdentities.internal("outbox reply")));
            values.put(InvocationOrigin.class.getName(), InvocationOrigin.of("outbox"));
            ContextHolder.Scope binding;
            try {
                binding = scope.installDispatch(values, "outbox");
            } catch (Exception | LinkageError | AssertionError e) {
                result.fail(e);
                return;
            }
            Future<T> work;
            try {
                work = java.util.Objects.requireNonNull(action.get(), "The reply action returned null");
            } catch (Exception | LinkageError | AssertionError e) {
                work = Future.failedFuture(e);
            }
            work.eventually(() -> {
                        binding.close();
                        return Future.succeededFuture();
                    })
                    .onComplete(result);
        });
        return result.future();
    }

    private void report(ReplyOutcomeObserver.Outcome outcome) {
        if (outcome == ReplyOutcomeObserver.Outcome.DUPLICATE) log.debug("Duplicate outbox reply ignored");
        for (ReplyOutcomeObserver observer : observers) {
            try {
                observer.onOutcome(outcome);
            } catch (Exception | LinkageError | AssertionError | StackOverflowError ignored) {
            }
        }
    }
}
