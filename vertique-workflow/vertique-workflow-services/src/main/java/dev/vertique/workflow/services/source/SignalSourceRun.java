// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.scope.ScopeContext;
import dev.vertique.core.scope.ScopeKey;
import dev.vertique.core.util.Strings;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.workflow.actor.ActorText;
import dev.vertique.workflow.exception.WorkflowIntakeRefusedException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowSignalActorException;
import dev.vertique.workflow.exception.WorkflowSignalAmbiguousException;
import dev.vertique.workflow.exception.WorkflowSignalLimitException;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.services.source.SignalSourcePlan.Target;
import dev.vertique.workflow.services.source.SourceDisposition.Fate;
import dev.vertique.workflow.source.SignalMessage;
import dev.vertique.workflow.source.SignalNoMatch;
import dev.vertique.workflow.source.SignalSourceEvent;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import dev.vertique.workflow.source.SignalSourceRefusal;
import dev.vertique.workflow.types.ScopeKeyRules;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.EncodeException;
import io.vertx.sqlclient.DatabaseException;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs one message through a {@link SignalSourcePlan} to one {@link SourceDisposition}.
 *
 * <p>The run is a chain of futures over the context {@code deliver} captured. Every stage and every
 * recording starts on its own duplicate of that context, made by {@link WorkflowSourceDelivery}, and
 * the chain resumes on the captured context before the next stage starts, whichever context completed
 * the stage's own future. The stages run in this order: {@code when}, the dedup key, {@code dispatchAs},
 * {@code scope}; then, for each signal in turn, its key, its payload and its recording. Each signal is
 * judged alone, and the message's fate is the most severe of them.
 *
 * <p>A stage that throws before it returns is told apart from a stage whose future fails: the first
 * is terminal, the second is retried. Nothing the message holds, a key, a header or a payload, and no
 * message of a failure, reaches a disposition, an event or a log record.
 */
@Slf4j
final class SignalSourceRun<T> {

    private static final String BLANK_DEDUP_KEY = "BlankDedupKey";
    private static final String ANONYMOUS_IDENTITY = "AnonymousIdentity";
    private static final int MAX_CAUSE_DEPTH = 16;
    private static final String SQLSTATE_DATA_EXCEPTION = "22";

    /** How a stage ended: with a value, by throwing before it returned a future, or by a failed future. */
    private record Stage<V>(
            @Nullable V value,
            @Nullable Throwable thrown,
            @Nullable Throwable failed) {
        static <V> Stage<V> ok(@Nullable V value) {
            return new Stage<>(value, null, null);
        }

        static <V> Stage<V> thrown(Throwable thrown) {
            return new Stage<>(null, thrown, null);
        }

        static <V> Stage<V> failed(Throwable failed) {
            return new Stage<>(null, null, failed);
        }
    }

    /** What one judgement decided: the outcome to report and the fate to hand back. */
    private record Verdict(
            Outcome outcome,
            Fate fate,
            @Nullable String errorType,
            @Nullable String definitionId) {
        static Verdict of(Outcome outcome, Fate fate, @Nullable String errorType) {
            return new Verdict(outcome, fate, errorType, null);
        }
    }

    /** Either the value a step produced or the verdict that ends the step's signal. */
    private record Step<V>(@Nullable V value, @Nullable Verdict verdict) {
        static <V> Step<V> go(@Nullable V value) {
            return new Step<>(value, null);
        }

        static <V> Step<V> stop(Verdict verdict) {
            return new Step<>(null, verdict);
        }
    }

    private final WorkflowSourceDelivery delivery;
    private final SignalSourcePlan<T> plan;
    private final SignalMessage<T> message;
    private final SourceEnvelope envelope;
    private final ContextInternal captured;

    private SourceEnvelope effective;
    private String dedupKey = "";
    private @Nullable ScopeContext scopeContext;
    private @Nullable String scopeKey;

    SignalSourceRun(
            WorkflowSourceDelivery delivery,
            SignalSourcePlan<T> plan,
            SignalMessage<T> message,
            SourceEnvelope envelope,
            ContextInternal captured) {
        this.delivery = delivery;
        this.plan = plan;
        this.message = message;
        this.envelope = envelope;
        this.effective = envelope;
        this.captured = captured;
    }

    /** Starts the run and returns a future that completes on the captured context. */
    Future<SourceDisposition> run() {
        Promise<SourceDisposition> out = captured.promise();
        whenStage()
                .compose(stop -> stop != null ? Future.succeededFuture(stop) : dedupStage())
                .compose(stop -> stop != null ? Future.succeededFuture(stop) : dispatchStage())
                .map(stop -> stop != null ? stop : identityStage())
                .compose(stop -> stop != null ? Future.succeededFuture(stop) : scopeStage())
                .compose(stop -> stop != null ? Future.succeededFuture(stop) : signals())
                .recover(failure -> Future.succeededFuture(conclude(null, failedVerdict(failure))))
                .onComplete(out);
        return out.future();
    }

    // --- stages before any signal; each answers the disposition that ends the message, or null to go on ---

    private Future<SourceDisposition> whenStage() {
        if (plan.when() == null) {
            return Future.succeededFuture(null);
        }
        return sync(bindings(null), () -> plan.when().test(message.body())).map(result -> {
            if (result.thrown() != null) {
                return conclude(null, thrownVerdict(result.thrown()));
            }
            return Boolean.TRUE.equals(result.value())
                    ? null
                    : conclude(null, Verdict.of(Outcome.IGNORED, Fate.DONE, null));
        });
    }

    private Future<SourceDisposition> dedupStage() {
        Function<T, String> dedupBy = plan.dedupBy();
        if (dedupBy == null) {
            return Future.succeededFuture(acceptDedupKey(envelope.defaultDedupKey()));
        }
        return sync(bindings(null), () -> dedupBy.apply(message.body())).map(result -> {
            if (result.thrown() != null) {
                return conclude(null, thrownVerdict(result.thrown()));
            }
            return acceptDedupKey(result.value());
        });
    }

    private @Nullable SourceDisposition acceptDedupKey(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return conclude(null, Verdict.of(Outcome.INVALID_INPUT, Fate.TERMINAL, BLANK_DEDUP_KEY));
        }
        dedupKey = key;
        return null;
    }

    private Future<SourceDisposition> dispatchStage() {
        Function<SignalMessage<T>, Future<Optional<SecurityIdentity>>> dispatchAs = plan.dispatchAs();
        if (dispatchAs == null || envelope.identityContributed()) {
            return Future.succeededFuture(null);
        }
        return lookup(Map.of(), () -> dispatchAs.apply(message)).map(result -> {
            Throwable failure = result.thrown() != null ? result.thrown() : result.failed();
            SignalSourceRefusal refusal = refusalIn(failure);
            if (refusal != null) {
                return conclude(
                        null,
                        Verdict.of(
                                Outcome.REFUSED_IDENTITY, Fate.TERMINAL, Strings.errorTypeToken(refusal.errorType())));
            }
            if (result.thrown() != null) {
                return conclude(null, thrownVerdict(result.thrown()));
            }
            if (result.failed() != null) {
                return conclude(null, failedVerdict(result.failed()));
            }
            // An application's future may answer null instead of an empty Optional.
            Optional<SecurityIdentity> answer = Objects.requireNonNullElse(result.value(), Optional.empty());
            if (answer.isEmpty()) {
                return null;
            }
            effective = new SourceEnvelope(
                    envelope.transport(),
                    SecurityContexts.unauthenticated(answer.get()),
                    envelope.identityContributed(),
                    envelope.correlation(),
                    envelope.origin(),
                    envelope.defaultDedupKey(),
                    envelope.provenance());
            return null;
        });
    }

    /** Refuses an anonymous identity, whether the envelope carried it or {@code dispatchAs} answered it. */
    private @Nullable SourceDisposition identityStage() {
        if (effective.security().identity().actor().type() == PrincipalType.ANONYMOUS) {
            return conclude(null, Verdict.of(Outcome.REFUSED_IDENTITY, Fate.TERMINAL, ANONYMOUS_IDENTITY));
        }
        return null;
    }

    private Future<SourceDisposition> scopeStage() {
        Function<SignalMessage<T>, Optional<String>> scope = plan.scope();
        if (scope == null) {
            return Future.succeededFuture(null);
        }
        return sync(bindings(null), () -> scope.apply(message)).map(result -> {
            if (result.thrown() != null) {
                return conclude(null, thrownVerdict(result.thrown()));
            }
            Optional<String> answer = Objects.requireNonNullElse(result.value(), Optional.empty());
            if (answer.isEmpty()) {
                return conclude(null, Verdict.of(Outcome.SCOPE_EMPTY, Fate.DONE, null));
            }
            try {
                String key = answer.get();
                ScopeKeyRules.require(key, "scopeKey");
                scopeContext = new ScopeContext(new ScopeKey(key));
                scopeKey = key;
            } catch (IllegalArgumentException e) {
                return conclude(null, Verdict.of(Outcome.INVALID_INPUT, Fate.TERMINAL, typeOf(e)));
            }
            return null;
        });
    }

    // --- the signals, one at a time ---

    private Future<SourceDisposition> signals() {
        Future<SourceDisposition> folded = Future.succeededFuture(
                new SourceDisposition(Fate.DONE, null, effective.security().snapshot()));
        for (Target<T> target : plan.targets()) {
            folded = folded.compose(sofar -> signal(target).map(next -> fold(sofar, next)));
        }
        return folded;
    }

    /**
     * Runs one signal to its disposition. A failure the stages did not judge ends this signal as a retry,
     * so a later signal still runs and the message's fate stays the most severe of them.
     */
    private Future<SourceDisposition> signal(Target<T> target) {
        return keyStep(target)
                .compose(key -> {
                    if (key.verdict() != null) {
                        return Future.succeededFuture(conclude(target, key.verdict()));
                    }
                    return payloadStep(target).compose(payload -> {
                        if (payload.verdict() != null) {
                            return Future.succeededFuture(conclude(target, payload.verdict()));
                        }
                        return record(target, key.value(), payload.value());
                    });
                })
                .recover(failure -> Future.succeededFuture(conclude(target, failedVerdict(failure))));
    }

    private Future<Step<String>> keyStep(Target<T> target) {
        Map<String, Object> bindings = bindings(scopeContext);
        if (target.key() != null) {
            Function<T, String> key = target.key();
            return sync(bindings, () -> key.apply(message.body())).map(result -> keyOf(target, result, result.value()));
        }
        Function<SignalMessage<T>, Future<Optional<String>>> lookup = target.lookup();
        return lookup(bindings, () -> lookup.apply(message)).map(result -> {
            Optional<String> answer = Objects.requireNonNullElse(result.value(), Optional.empty());
            return keyOf(target, result, answer.orElse(null));
        });
    }

    private Step<String> keyOf(Target<T> target, Stage<?> result, @Nullable String key) {
        if (result.thrown() != null) {
            return Step.stop(thrownVerdict(result.thrown()));
        }
        if (result.failed() != null) {
            return Step.stop(failedVerdict(result.failed()));
        }
        if (key == null || key.isEmpty()) {
            return Step.stop(Verdict.of(Outcome.IGNORED, Fate.DONE, null));
        }
        try {
            // The signal's own rules refuse a blank, over-long or NUL-holding key before any operation runs.
            CorrelatedSignal.of(target.contract(), target.signalName(), key, null, dedupKey);
        } catch (IllegalArgumentException e) {
            return Step.stop(Verdict.of(Outcome.INVALID_INPUT, Fate.TERMINAL, typeOf(e)));
        }
        return Step.go(key);
    }

    private Future<Step<Object>> payloadStep(Target<T> target) {
        Function<T, ?> payload = target.payload();
        return sync(bindings(null), () -> payload == null ? message.body() : payload.apply(message.body()))
                .map(result -> result.thrown() != null
                        ? Step.<Object>stop(thrownVerdict(result.thrown()))
                        : Step.<Object>go(result.value()));
    }

    private Future<SourceDisposition> record(Target<T> target, @Nullable String key, @Nullable Object payload) {
        CorrelatedSignal signal = new CorrelatedSignal(
                target.contract(), target.signalName(), key, payload, dedupKey, null, scopeKey, null);
        return delivery.record(captured, signal, target.businessKey(), effective)
                .transform(outcome -> Future.succeededFuture(judge(target, outcome.cause(), outcome.result())))
                .map(verdict -> conclude(target, verdict));
    }

    // --- judging a recording ---

    private static Verdict judge(
            Target<?> target, @Nullable Throwable failure, @Nullable CorrelatedSignalReceipt receipt) {
        if (failure == null) {
            if (receipt != null && receipt.outcome() == CorrelatedSignalReceipt.Outcome.DUPLICATE) {
                return Verdict.of(Outcome.DEDUPLICATED, Fate.DONE, null);
            }
            return new Verdict(Outcome.APPLIED, Fate.DONE, null, receipt == null ? null : receipt.definitionId());
        }
        Verdict named = inChain(failure, cause -> named(target, cause));
        return named != null ? named : failedVerdict(failure);
    }

    /** The refusal a failure is or wraps, found with the depth limit {@link #judge} uses, or {@code null}. */
    private static @Nullable SignalSourceRefusal refusalIn(@Nullable Throwable failure) {
        return inChain(failure, cause -> cause instanceof SignalSourceRefusal refusal ? refusal : null);
    }

    /** The first answer {@code match} gives along the cause chain, to a bounded depth, or {@code null}. */
    private static @Nullable <R> R inChain(@Nullable Throwable failure, Function<Throwable, R> match) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            R found = match.apply(cause);
            if (found != null) {
                return found;
            }
            Throwable next = cause.getCause();
            cause = next == cause ? null : next;
        }
        return null;
    }

    private static @Nullable Verdict named(Target<?> target, Throwable cause) {
        if (cause instanceof WorkflowSignalNoMatchException) {
            boolean retry = target.onNoMatch() == SignalNoMatch.RETRY;
            return Verdict.of(Outcome.NO_MATCH, retry ? Fate.RETRY : Fate.DONE, retry ? typeOf(cause) : null);
        }
        if (cause instanceof WorkflowSignalAmbiguousException) {
            return Verdict.of(Outcome.AMBIGUOUS, Fate.TERMINAL, typeOf(cause));
        }
        if (cause instanceof WorkflowSignalLimitException) {
            return Verdict.of(Outcome.LIMITED, Fate.RETRY, typeOf(cause));
        }
        if (cause instanceof WorkflowIntakeRefusedException) {
            return Verdict.of(Outcome.LIMITED, Fate.WAIT, typeOf(cause));
        }
        if (cause instanceof WorkflowInvalidPayloadException) {
            return Verdict.of(Outcome.OVERSIZED, Fate.TERMINAL, typeOf(cause));
        }
        if (cause instanceof WorkflowSignalActorException) {
            return Verdict.of(Outcome.REFUSED_IDENTITY, Fate.TERMINAL, typeOf(cause));
        }
        if (cause instanceof EncodeException) {
            return Verdict.of(Outcome.INVALID_INPUT, Fate.TERMINAL, typeOf(cause));
        }
        if (cause instanceof DatabaseException database
                && database.getSqlState() != null
                && database.getSqlState().startsWith(SQLSTATE_DATA_EXCEPTION)) {
            return Verdict.of(Outcome.INVALID_INPUT, Fate.TERMINAL, typeOf(cause));
        }
        return null;
    }

    private static Verdict thrownVerdict(Throwable thrown) {
        return Verdict.of(Outcome.STAGE_FAILED, Fate.TERMINAL, typeOf(thrown));
    }

    private static Verdict failedVerdict(Throwable failed) {
        return Verdict.of(Outcome.STAGE_FAILED, Fate.RETRY, typeOf(failed));
    }

    private static String typeOf(Throwable failure) {
        String name = failure.getClass().getSimpleName();
        return name.isEmpty() ? failure.getClass().getName() : name;
    }

    // --- ending a judgement: report it, log it when no command shows who attempted it, fold it ---

    private SourceDisposition conclude(@Nullable Target<T> target, Verdict verdict) {
        delivery.report(new SignalSourceEvent(
                plan.sourceName(),
                envelope.transport(),
                target == null ? null : target.contractName(),
                target == null ? null : target.signalName(),
                verdict.definitionId(),
                verdict.outcome()));
        logNoCommand(target, verdict);
        return new SourceDisposition(
                verdict.fate(),
                verdict.fate() == Fate.DONE ? null : verdict.errorType(),
                effective.security().snapshot());
    }

    /**
     * Records who attempted a delivery that left no command row, when the identity is not a system's.
     * It names the actor, the contract and signal (none for an outcome before a signal is chosen) and the
     * outcome, and nothing of the message. A recorded, deduplicated or ignored message needs none.
     */
    private void logNoCommand(@Nullable Target<T> target, Verdict verdict) {
        if (verdict.outcome() == Outcome.APPLIED
                || verdict.outcome() == Outcome.DEDUPLICATED
                || verdict.outcome() == Outcome.IGNORED) {
            return;
        }
        SecurityIdentity identity = effective.security().identity();
        if (identity.actor().type() == PrincipalType.SYSTEM) {
            return;
        }
        log.warn(
                "Source '{}' recorded no command for actor '{}': contract={}, signal={}, outcome={}",
                plan.sourceName(),
                ActorText.clean(identity.actor().id()),
                target == null ? null : target.contractName(),
                target == null ? null : target.signalName(),
                verdict.outcome());
    }

    private static SourceDisposition fold(SourceDisposition sofar, SourceDisposition next) {
        return rank(next.fate()) > rank(sofar.fate()) ? next : sofar;
    }

    private static int rank(Fate fate) {
        return switch (fate) {
            case DONE -> 0;
            case WAIT -> 1;
            case RETRY -> 2;
            case TERMINAL -> 3;
        };
    }

    // --- running a stage on a fresh duplicate of the captured context ---

    /** Runs a synchronous stage; a throw ends it as {@code thrown}. */
    private <V> Future<Stage<V>> sync(Map<String, Object> bindings, Supplier<V> stage) {
        return delivery.runBound(captured, bindings, envelope.transport(), () -> {
            try {
                return Future.succeededFuture(Stage.ok(stage.get()));
            } catch (Exception | LinkageError | AssertionError | StackOverflowError e) {
                return Future.succeededFuture(Stage.<V>thrown(e));
            }
        });
    }

    /**
     * Runs a stage that answers a future, bounded by the plan's lookup timeout. A throw before the
     * future is returned ends it as {@code thrown}; a failed or stalled future ends it as {@code failed}.
     */
    private <V> Future<Stage<V>> lookup(Map<String, Object> bindings, Supplier<Future<V>> stage) {
        return delivery.runBound(captured, bindings, envelope.transport(), () -> {
            Future<V> pending;
            try {
                pending = stage.get();
                if (pending == null) {
                    throw new NullPointerException("the stage returned no future");
                }
            } catch (Exception | LinkageError | AssertionError | StackOverflowError e) {
                return Future.succeededFuture(Stage.<V>thrown(e));
            }
            return bounded(pending)
                    .map(value -> Stage.ok(value))
                    .recover(failure -> Future.succeededFuture(Stage.<V>failed(failure)));
        });
    }

    private <V> Future<V> bounded(Future<V> pending) {
        Promise<V> bound = Promise.promise();
        long timer = delivery.vertx().setTimer(plan.lookupTimeoutMs(), id -> bound.tryFail(new TimeoutException()));
        pending.onComplete(result -> {
            delivery.vertx().cancelTimer(timer);
            if (result.succeeded()) {
                bound.tryComplete(result.result());
            } else {
                bound.tryFail(result.cause());
            }
        });
        return bound.future();
    }

    private Map<String, Object> bindings(@Nullable ScopeContext scope) {
        Map<String, Object> values = new LinkedHashMap<>();
        CorrelationContext correlation = envelope.correlation();
        if (correlation != null) {
            values.put(CorrelationContext.class.getName(), correlation);
        }
        values.put(InvocationOrigin.class.getName(), envelope.origin());
        if (scope != null) {
            values.put(ScopeContext.class.getName(), scope);
        }
        return values;
    }
}
