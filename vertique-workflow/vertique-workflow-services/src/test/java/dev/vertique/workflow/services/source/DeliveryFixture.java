// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.InboundDispatchScope;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SystemIdentities;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.SignalProvenance;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.TransactionPropagation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared setup of the delivery proofs: the context classes of {@code vertique-context} built
 * directly, a mocked {@link Pool} that applies its function to a mocked {@link SqlConnection}, and a
 * fake {@link TransactionalWorkflowOperations} that only records what it receives.
 *
 * <p>The fake reads the ambient {@link SecurityContext}, {@link CorrelationContext} and
 * {@link InvocationOrigin} on entry, and a held-open recording reads them again when it resumes. A
 * {@link Caller} is a Vert.x context the test delivers from.
 */
final class DeliveryFixture {

    /** The contract class of every signal the proofs deliver. */
    interface OrdersContract {}

    /** A value only the caller binds, so a proof can see that the caller's own binding survives. */
    record CallerMarker(String label) implements ContextValue {}

    static final CorrelatedSignalReceipt RECEIPT =
            new CorrelatedSignalReceipt(CorrelatedSignalReceipt.Outcome.RECORDED, "def-1");

    private static final long WAIT_SECONDS = 10;

    /** The three values the delivery binds, as the context holder reports them. */
    record Ambient(SecurityContext security, CorrelationContext correlation, InvocationOrigin origin) {

        boolean isEmpty() {
            return security == null && correlation == null && origin == null;
        }
    }

    /** One call the fake operations received. */
    static final class Entry {
        private final String method;
        private final CorrelatedSignal signal;
        private final SqlClient transaction;
        private final ContextInternal context;
        private final Ambient atEntry;
        private final AtomicReference<Promise<CorrelatedSignalReceipt>> heldOpen = new AtomicReference<>();
        private final AtomicReference<Ambient> afterResumption = new AtomicReference<>();

        Entry(String method, CorrelatedSignal signal, SqlClient transaction, ContextInternal context, Ambient atEntry) {
            this.method = method;
            this.signal = signal;
            this.transaction = transaction;
            this.context = context;
            this.atEntry = atEntry;
        }

        String method() {
            return method;
        }

        CorrelatedSignal signal() {
            return signal;
        }

        SqlClient transaction() {
            return transaction;
        }

        /** The Vert.x context the fake ran on, which is the delivery's own duplicate. */
        ContextInternal context() {
            return context;
        }

        Ambient atEntry() {
            return atEntry;
        }

        /** What the fake read again when its held-open recording resumed; null until it did. */
        Ambient afterResumption() {
            return afterResumption.get();
        }

        /** Completes the held-open recording from the calling thread. A second call is harmless. */
        void release() {
            Promise<CorrelatedSignalReceipt> promise = heldOpen.get();
            if (promise != null) {
                promise.tryComplete(RECEIPT);
            }
        }

        /** Completes the held-open recording from the given context. A second call is harmless. */
        void release(ContextInternal completer) {
            Promise<CorrelatedSignalReceipt> promise = heldOpen.get();
            if (promise != null) {
                completer.runOnContext(v -> promise.tryComplete(RECEIPT));
            }
        }
    }

    /** What the fake operations have received, in arrival order. */
    static final class Observed {
        private final List<Entry> entries = new CopyOnWriteArrayList<>();
        private final BlockingQueue<Entry> arrivals = new LinkedBlockingQueue<>();

        void arrived(Entry entry) {
            entries.add(entry);
            arrivals.add(entry);
        }

        /** Blocks until the next call arrives, and fails the proof if none does. */
        Entry awaitEntry() {
            try {
                Entry entry = arrivals.poll(WAIT_SECONDS, TimeUnit.SECONDS);
                assertThat(entry).as("the fake operations received a call").isNotNull();
                return entry;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the fake operations", e);
            }
        }

        List<Entry> entries() {
            return List.copyOf(entries);
        }

        long callsTo(String method) {
            return entries.stream().filter(e -> e.method().equals(method)).count();
        }
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
            ContextHolder.Scope scope = callOn(context, () -> holder.bind(type, value));
            scopes.add(scope);
            return this;
        }

        /** Runs the action on this context and returns the future it produced, without waiting for it. */
        <T> Future<T> run(Supplier<Future<T>> action) {
            return callOn(context, action);
        }

        /** Reads a value on this context, as the code that called the bean would see it. */
        <T> T read(Supplier<T> reader) {
            return callOn(context, reader);
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

    /** The outcome of two deliveries that were open at the same time. */
    record Overlap(Entry first, Entry second, Ambient firstAfterCompletion, Ambient secondAfterCompletion) {}

    private enum Mode {
        SUCCEED,
        HOLD,
        FAIL,
        THROW
    }

    private final Vertx vertx;
    private final DefaultContextHolder holder = new DefaultContextHolder();
    private final InboundExecutionContextScope scope = scopeOver(holder);
    private final Pool pool = mock(Pool.class);
    private final SqlConnection connection = mock(SqlConnection.class);

    @SuppressWarnings("unchecked")
    private final TransactionalWorkflowOperations<SqlClient> operations = mock(TransactionalWorkflowOperations.class);

    private final Observed observed = new Observed();
    private final Mode mode;
    private final Throwable cause;
    private final ContextInternal home;
    private final WorkflowSourceDelivery delivery;

    private DeliveryFixture(Vertx vertx, Mode mode, Throwable cause, ContextInternal home) {
        this.vertx = vertx;
        this.mode = mode;
        this.cause = cause;
        this.home = home;
        stubPool();
        stubOperations();
        this.delivery = new WorkflowSourceDelivery(() -> operations, pool, vertx, scope, Set.of());
    }

    /** Operations that record the call and succeed with {@link #RECEIPT} at once. */
    static DeliveryFixture succeeding(Vertx vertx) {
        return new DeliveryFixture(vertx, Mode.SUCCEED, null, null);
    }

    /** Operations that hold each recording open on a promise bound to the delivery's own context. */
    static DeliveryFixture holdingOpen(Vertx vertx) {
        return new DeliveryFixture(vertx, Mode.HOLD, null, null);
    }

    /** Operations that hold each recording open on a promise bound to the given context. */
    static DeliveryFixture holdingOpenOn(Vertx vertx, ContextInternal home) {
        return new DeliveryFixture(vertx, Mode.HOLD, null, home);
    }

    /** Operations whose recording fails with the given cause. */
    static DeliveryFixture failing(Vertx vertx, Throwable cause) {
        return new DeliveryFixture(vertx, Mode.FAIL, cause, null);
    }

    /** Operations that throw the given exception synchronously. */
    static DeliveryFixture throwing(Vertx vertx, RuntimeException cause) {
        return new DeliveryFixture(vertx, Mode.THROW, cause, null);
    }

    /** The context classes of {@code vertique-context}, built as their own unit test builds them. */
    static InboundExecutionContextScope scopeOver(DefaultContextHolder holder) {
        ContextScopeBinder binder = new ContextScopeBinder(holder);
        DurableContextMetadataRegistry emptyRegistry = new DurableContextMetadataRegistry(Set.of(), Set.of());
        DurableContextPropagator durable = new DurableContextPropagator(emptyRegistry, holder, binder);
        return new InboundExecutionContextScope(new InboundDispatchScope(), durable, Set.of());
    }

    WorkflowSourceDelivery delivery() {
        return delivery;
    }

    Observed observed() {
        return observed;
    }

    /** The connection the mocked pool hands to the function it is given. */
    SqlConnection connection() {
        return connection;
    }

    /** A new duplicated context with nothing bound on it. */
    Caller callerOnDuplicate() {
        return new Caller(((ContextInternal) vertx.getOrCreateContext()).duplicate());
    }

    /** A new duplicated context that carries the given security context, as an application's code might. */
    Caller callerOnDuplicate(SecurityContext security) {
        return callerOnDuplicate().binding(SecurityContext.class, security);
    }

    /** A new root event-loop context. */
    Caller callerOnRoot() {
        return new Caller((ContextInternal) vertx.getOrCreateContext());
    }

    /** Delivers one signal from the caller and returns the future the bean produced. */
    Future<CorrelatedSignalReceipt> record(
            Caller caller, CorrelatedSignal signal, boolean businessKey, SourceEnvelope envelope) {
        return caller.run(() -> delivery.record(signal, businessKey, envelope));
    }

    /** Runs two deliveries so that both are open together, then completes them in the opposite order. */
    Overlap overlap(SourceEnvelope first, SourceEnvelope second) {
        try (Caller shared = callerOnDuplicate();
                Caller foreign = callerOnDuplicate()) {
            Entry a = null;
            Entry b = null;
            try {
                Future<CorrelatedSignalReceipt> firstFuture = record(shared, signal("KEY-A", "d-A"), false, first);
                a = observed.awaitEntry();
                Future<CorrelatedSignalReceipt> secondFuture = record(shared, signal("KEY-B", "d-B"), false, second);
                b = observed.awaitEntry();

                b.release(foreign.context());
                await(secondFuture);
                a.release(foreign.context());
                await(firstFuture);

                return new Overlap(a, b, ambientOn(a.context()), ambientOn(b.context()));
            } finally {
                if (a != null) {
                    a.release();
                }
                if (b != null) {
                    b.release();
                }
            }
        }
    }

    /** The three bound values as seen from the current Vert.x context. */
    Ambient ambient() {
        return new Ambient(
                holder.current(SecurityContext.class).orElse(null),
                holder.current(CorrelationContext.class).orElse(null),
                holder.current(InvocationOrigin.class).orElse(null));
    }

    /** Schedules a read on the given context and waits for it, which is how a later task would see it. */
    Ambient ambientOn(ContextInternal context) {
        return callOn(context, this::ambient);
    }

    /** Reads the given context's bound values at once, on the calling thread, with that context current. */
    Ambient ambientDispatchedOn(ContextInternal context) {
        AtomicReference<Ambient> seen = new AtomicReference<>();
        context.dispatch(() -> seen.set(ambient()));
        return seen.get();
    }

    <T extends ContextValue> T currentOn(Caller caller, Class<T> type) {
        return caller.read(() -> holder.current(type).orElse(null));
    }

    // --- signals and envelopes ---

    static CorrelatedSignal signal(String key, String dedupKey) {
        return CorrelatedSignal.of(OrdersContract.class, "shipment-delivered", key, "delivered", dedupKey);
    }

    static SecurityContext security(String reason) {
        return SecurityContexts.system(SystemIdentities.internal(reason));
    }

    static SourceEnvelope envelope(
            SecurityContext security, CorrelationContext correlation, Function<String, SignalProvenance> provenance) {
        return new SourceEnvelope(
                "kafka", security, false, correlation, InvocationOrigin.of("kafka"), null, provenance);
    }

    static CorrelationContext correlation(String label) {
        return CorrelationContext.generated(label);
    }

    // --- shared assertions ---

    /** The three values are the envelope's own. Identity, not instance, is compared: a context may copy a value. */
    static void assertAmbientIs(Ambient seen, SourceEnvelope envelope) {
        assertThat(seen.security()).as("bound security context").isNotNull();
        assertThat(seen.security().identity())
                .as("bound identity")
                .isEqualTo(envelope.security().identity());
        if (envelope.correlation() == null) {
            assertThat(seen.correlation()).as("bound correlation").isNull();
        } else {
            assertThat(seen.correlation()).as("bound correlation").isNotNull();
            assertThat(seen.correlation().correlationId())
                    .as("bound correlation id")
                    .isEqualTo(envelope.correlation().correlationId());
        }
        assertThat(seen.origin()).as("bound origin").isEqualTo(envelope.origin());
    }

    static void assertNothingBound(Ambient seen) {
        assertThat(seen.isEmpty())
                .as("no SecurityContext, CorrelationContext or InvocationOrigin is bound, but saw %s", seen)
                .isTrue();
    }

    // --- waiting ---

    static <T> T await(Future<T> future) {
        return join(future.toCompletionStage().toCompletableFuture());
    }

    /** Waits for the future to fail and returns the very throwable it failed with. */
    static Throwable failureOf(Future<?> future) {
        CompletableFuture<?> completable = future.toCompletionStage().toCompletableFuture();
        try {
            completable.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the future", e);
        } catch (TimeoutException e) {
            throw new AssertionError("the future did not complete", e);
        }
        throw new AssertionError("the future succeeded, but a failure was expected");
    }

    static <T> T join(CompletableFuture<T> completable) {
        try {
            return completable.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError("the awaited work failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        } catch (TimeoutException e) {
            throw new AssertionError("the awaited work did not complete", e);
        }
    }

    static <T> T callOn(ContextInternal context, Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        context.runOnContext(v -> {
            try {
                result.complete(task.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return join(result);
    }

    // --- wiring of the doubles ---

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPool() {
        doAnswer(invocation -> ((Function) invocation.getArgument(0)).apply(connection))
                .when(pool)
                .withTransaction(any(Function.class));
        doAnswer(invocation -> ((Function) invocation.getArgument(1)).apply(connection))
                .when(pool)
                .withTransaction(any(TransactionPropagation.class), any(Function.class));
    }

    private void stubOperations() {
        when(operations.signalByKey(any(), any()))
                .thenAnswer(invocation -> receive("signalByKey", invocation.getArgument(0), invocation.getArgument(1)));
        when(operations.signalByBusinessKey(any(), any()))
                .thenAnswer(invocation ->
                        receive("signalByBusinessKey", invocation.getArgument(0), invocation.getArgument(1)));
    }

    private Future<CorrelatedSignalReceipt> receive(String method, CorrelatedSignal signal, SqlClient transaction) {
        ContextInternal context = (ContextInternal) Vertx.currentContext();
        Entry entry = new Entry(method, signal, transaction, context, ambient());
        observed.arrived(entry);
        switch (mode) {
            case FAIL:
                return Future.failedFuture(cause);
            case THROW:
                throw (RuntimeException) cause;
            case HOLD:
                ContextInternal on = home != null ? home : context;
                Promise<CorrelatedSignalReceipt> promise = on != null ? on.promise() : Promise.promise();
                entry.heldOpen.set(promise);
                return promise.future().map(receipt -> {
                    entry.afterResumption.set(ambient());
                    return receipt;
                });
            default:
                return Future.succeededFuture(RECEIPT);
        }
    }
}
