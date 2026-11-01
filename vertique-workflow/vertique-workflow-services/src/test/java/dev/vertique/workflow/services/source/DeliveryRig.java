// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import static dev.vertique.workflow.services.source.DeliveryFixture.await;
import static dev.vertique.workflow.services.source.DeliveryFixture.callOn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.extension.ObserverFailureReporter;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.SignalProvenance;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.services.source.DeliveryFixture.Ambient;
import dev.vertique.workflow.services.source.ObserverFixture.LogCapture;
import dev.vertique.workflow.services.source.ObserverFixture.RecordingObserver;
import dev.vertique.workflow.source.SignalMessage;
import dev.vertique.workflow.source.SignalSourceEvent;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import dev.vertique.workflow.source.SignalSourceObserver;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.TransactionPropagation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared setup of the plan delivery proofs: a {@link WorkflowSignalSourceDelivery} over the context classes
 * of {@code vertique-context}, a mocked {@link Pool} that applies its function to a mocked connection,
 * fake operations that answer from a script, a recording observer, a reporter that records what it is
 * handed, and a capture of every log record.
 *
 * <p>The fake operations take the answers of {@link #operations(Answer...)} one call at a time and
 * repeat the last one. Each call is recorded with the context it ran on and the values bound there,
 * and a timeline notes when each call started and when its future completed.
 *
 * <p>Close the rig when the proof ends: it detaches the log capture and fails any future it still
 * holds for a foreign context.
 */
final class DeliveryRig implements AutoCloseable {

    /** How long a future completed from a foreign context waits, so the stage has registered by then. */
    private static final long FOREIGN_DELAY_MS = 25;

    /** One call the fake operations received. */
    record Call(int number, String method, CorrelatedSignal signal, ContextInternal context, Ambient ambient) {}

    /** What the fake operations answer to one call. A throw is a synchronous failure. */
    @FunctionalInterface
    interface Answer {
        Future<CorrelatedSignalReceipt> answer(Call call);
    }

    /** A Vert.x context the test delivers from, standing in for the code that calls the bean. */
    final class Caller implements AutoCloseable {
        private final ContextInternal context;
        private final List<ContextHolder.Scope> scopes = new ArrayList<>();

        private Caller(ContextInternal context) {
            this.context = context;
        }

        ContextInternal context() {
            return context;
        }

        /** Binds a value on this context, the way an application's own code would. */
        <T extends ContextValue> Caller binding(Class<T> type, T value) {
            scopes.add(callOn(context, () -> holder.bind(type, value)));
            return this;
        }

        /** Delivers on this context and returns the future without waiting for it. */
        <T> Future<SourceDisposition> deliver(
                SignalSourcePlan<T> plan, SignalMessage<T> message, SourceEnvelope envelope) {
            return callOn(context, () -> delivery.deliver(plan, message, envelope));
        }

        /** Runs the action on this context and returns what it produced. */
        <T> T run(Supplier<T> action) {
            return callOn(context, action);
        }

        @Override
        public void close() {
            for (ContextHolder.Scope scope : scopes) {
                callOn(context, () -> {
                    scope.close();
                    return null;
                });
            }
        }
    }

    private final Vertx vertx;
    private final DefaultContextHolder holder = new DefaultContextHolder();
    private final Pool pool = mock(Pool.class);
    private final SqlConnection connection = mock(SqlConnection.class);

    @SuppressWarnings("unchecked")
    private final TransactionalWorkflowOperations<SqlClient> operations = mock(TransactionalWorkflowOperations.class);

    private final FakeInbox inbox = new FakeInbox();
    private final RecordingObserver recorder = new RecordingObserver();
    private final ObserverFailureReporter reporter = mock(ObserverFailureReporter.class);
    private final LogCapture logs = new LogCapture();
    private final LookupProbe probe = new LookupProbe(holder);
    private final WorkflowSignalSourceDelivery delivery;

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<String> timeline = new CopyOnWriteArrayList<>();
    private final List<String> order = new CopyOnWriteArrayList<>();
    private final List<String> provenanceArguments = new CopyOnWriteArrayList<>();
    private final List<Context> completedOn = new CopyOnWriteArrayList<>();
    private final List<Promise<?>> foreign = new CopyOnWriteArrayList<>();
    private final AtomicInteger callNumbers = new AtomicInteger();
    private final AtomicInteger position = new AtomicInteger();
    private final AtomicInteger transactions = new AtomicInteger();
    private volatile List<Answer> script = List.of(recorded());

    private DeliveryRig(
            Vertx vertx,
            boolean recording,
            List<SignalSourceObserver> others,
            Set<InboundContextInitializer> initializers) {
        this.vertx = vertx;
        stubPool();
        stubOperations();
        Set<SignalSourceObserver> observers = new LinkedHashSet<>();
        if (recording) {
            observers.add(recorder);
        }
        observers.addAll(others);
        this.delivery = new WorkflowSignalSourceDelivery(
                () -> operations,
                inbox,
                pool,
                vertx,
                DeliveryFixture.scopeOver(holder, initializers),
                observers,
                reporter);
    }

    /** The inbox the delivery writes to. */
    FakeInbox inbox() {
        return inbox;
    }

    /** A rig whose only observer is the rig's own recorder, read through {@link #events()}. */
    static DeliveryRig over(Vertx vertx) {
        return new DeliveryRig(vertx, true, List.of(), Set.of());
    }

    /** A rig as {@link #over}, whose context binding runs the given initializer on every stage and recording. */
    static DeliveryRig initializing(Vertx vertx, InboundContextInitializer initializer) {
        return new DeliveryRig(vertx, true, List.of(), Set.of(initializer));
    }

    /** A rig whose observer set is exactly the observers given, in order. */
    static DeliveryRig observing(Vertx vertx, SignalSourceObserver... observers) {
        return new DeliveryRig(vertx, false, List.of(observers), Set.of());
    }

    // --- scripting the operations ---

    /** Answers the operations' calls from these, one call at a time, repeating the last. */
    DeliveryRig operations(Answer... answers) {
        position.set(0);
        script = List.of(answers);
        return this;
    }

    /** Operations whose every call fails with the given exception. */
    DeliveryRig operationsRaising(Throwable failure) {
        return operations(raising(failure));
    }

    static Answer recorded() {
        return call -> Future.succeededFuture(DeliveryFixture.RECEIPT);
    }

    static Answer duplicate() {
        return call ->
                Future.succeededFuture(new CorrelatedSignalReceipt(CorrelatedSignalReceipt.Outcome.DUPLICATE, null));
    }

    /** A recording that fails after the match. */
    static Answer raising(Throwable failure) {
        return call -> Future.failedFuture(failure);
    }

    /** A recording that throws before it returns a future. */
    static Answer throwing(RuntimeException failure) {
        return call -> {
            throw failure;
        };
    }

    /** A recording whose future completes, a moment later, from the given context. */
    Answer recordedFrom(ContextInternal context) {
        return call -> completedFrom(context, DeliveryFixture.RECEIPT);
    }

    /**
     * A future that completes with the value, a moment later, from the given context, which is how an
     * application future completes on a context other than the one that asked for it.
     */
    <V> Future<V> completedFrom(ContextInternal context, V value) {
        Promise<V> promise = Promise.promise();
        foreign.add(promise);
        context.runOnContext(v -> vertx.setTimer(FOREIGN_DELAY_MS, id -> {
            completedOn.add(Vertx.currentContext());
            promise.tryComplete(value);
        }));
        return promise.future();
    }

    // --- delivering ---

    /** Delivers from a fresh context and waits for the disposition. */
    <T> SourceDisposition deliver(SignalSourcePlan<T> plan, SignalMessage<T> message, SourceEnvelope envelope) {
        return await(delivery.deliver(plan, message, envelope));
    }

    /** A new root event-loop context, such as the one a delivery is called from. */
    Caller callerOnRoot() {
        return new Caller((ContextInternal) vertx.getOrCreateContext());
    }

    /** A new duplicated context with nothing bound on it. */
    Caller callerOnDuplicate() {
        return new Caller(((ContextInternal) vertx.getOrCreateContext()).duplicate());
    }

    /** A new root context other than any caller's, for futures to complete on. */
    ContextInternal foreignContext() {
        return (ContextInternal) vertx.getOrCreateContext();
    }

    // --- what the proofs read ---

    List<Call> calls() {
        return List.copyOf(calls);
    }

    List<CorrelatedSignal> signals() {
        return calls.stream().map(Call::signal).toList();
    }

    /** When each call started and when its future completed, as {@code start:<n>} and {@code end:<n>}. */
    List<String> timeline() {
        return List.copyOf(timeline);
    }

    /** Notes that a stage ran, in order; a plan's stage functions call this. */
    void note(String stage) {
        order.add(stage);
    }

    List<String> order() {
        return List.copyOf(order);
    }

    List<SignalSourceEvent> events() {
        return recorder.events();
    }

    List<Outcome> outcomes() {
        return events().stream().map(SignalSourceEvent::outcome).toList();
    }

    /** How many transactions the pool was asked to open. */
    int transactions() {
        return transactions.get();
    }

    LookupProbe probe() {
        return probe;
    }

    ObserverFailureReporter reporter() {
        return reporter;
    }

    /** The contexts the foreign futures completed on. */
    List<Context> completedOn() {
        return List.copyOf(completedOn);
    }

    /** A provenance function that notes the dedup key it is given and answers a Camel provenance for it. */
    Function<String, SignalProvenance> provenance() {
        return dedupKey -> {
            provenanceArguments.add(dedupKey);
            return SignalProvenance.camel("carriers", "t-1", dedupKey);
        };
    }

    List<String> provenanceArguments() {
        return List.copyOf(provenanceArguments);
    }

    /** Every log record written since the rig was built, as the text a reader of the log would see. */
    List<String> logs() {
        return logs.records();
    }

    /** The number of log records so far, to mark where a later {@link #logsSince(int)} begins. */
    int logCount() {
        return logs.records().size();
    }

    List<String> logsSince(int mark) {
        List<String> all = logs.records();
        return all.subList(Math.min(mark, all.size()), all.size());
    }

    /** The ambient values of the current context, for a stage that wants to read them. */
    Ambient ambient() {
        return new Ambient(
                holder.current(SecurityContext.class).orElse(null),
                holder.current(CorrelationContext.class).orElse(null),
                holder.current(InvocationOrigin.class).orElse(null));
    }

    /**
     * Fails the foreign futures still pending and detaches the log capture.
     */
    @Override
    public void close() {
        for (Promise<?> promise : foreign) {
            promise.tryFail("released by the test");
        }
        logs.close();
    }

    // --- shared assertions ---

    /** Nothing the message held reaches a disposition, an event or a log record. */
    void assertNothingLeaked(SourceDisposition disposition, String... fragments) {
        List<String> everythingOut = new ArrayList<>();
        everythingOut.add(String.valueOf(disposition));
        if (disposition.errorType() != null) {
            everythingOut.add(disposition.errorType());
        }
        for (SignalSourceEvent event : events()) {
            everythingOut.add(event.toString());
        }
        everythingOut.addAll(logs());
        for (String fragment : fragments) {
            assertThat(everythingOut).as("nothing out holds '%s'", fragment).noneMatch(text -> text.contains(fragment));
        }
    }

    // --- wiring of the doubles ---

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPool() {
        doAnswer(invocation -> {
                    transactions.incrementAndGet();
                    return ((Function) invocation.getArgument(0)).apply(connection);
                })
                .when(pool)
                .withTransaction(any(Function.class));
        doAnswer(invocation -> {
                    transactions.incrementAndGet();
                    return ((Function) invocation.getArgument(1)).apply(connection);
                })
                .when(pool)
                .withTransaction(any(TransactionPropagation.class), any(Function.class));
    }

    private void stubOperations() {
        when(operations.signalByKey(any(), any()))
                .thenAnswer(invocation -> receive("signalByKey", invocation.getArgument(0)));
        when(operations.signalByBusinessKey(any(), any()))
                .thenAnswer(invocation -> receive("signalByBusinessKey", invocation.getArgument(0)));
    }

    private Future<CorrelatedSignalReceipt> receive(String method, CorrelatedSignal signal) {
        Call call = new Call(
                callNumbers.incrementAndGet(), method, signal, (ContextInternal) Vertx.currentContext(), ambient());
        calls.add(call);
        timeline.add("start:" + call.number());
        List<Answer> answers = script;
        Answer answer = answers.get(Math.min(position.getAndIncrement(), answers.size() - 1));
        try {
            return answer.answer(call).onComplete(result -> timeline.add("end:" + call.number()));
        } catch (RuntimeException failure) {
            timeline.add("end:" + call.number());
            throw failure;
        }
    }
}
