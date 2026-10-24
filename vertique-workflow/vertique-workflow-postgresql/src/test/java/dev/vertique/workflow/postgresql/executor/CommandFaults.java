// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dev.vertique.db.exception.TransactionDeadlineExceededException;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Faults and observation points in front of a command repository, keyed by instance.
 *
 * <p>A segment is abandoned the way a crash abandons it, or failed with a given failure, at its first statement: the
 * attempt to take the execution lock. A crash is the failure of a segment that hit its deadline, which the executor
 * treats as a crash: nothing commits, no disposition runs and the claim lapses with its lease. Every member call is
 * recorded by name, and a test can stop the call of a failure disposition, or the return of a claim, until it lets it
 * go on.
 */
final class CommandFaults {

    /** The members that are neither a read nor a failure disposition. */
    private static final Set<String> NOT_A_DISPOSITION = Set.of(
            "peek",
            "claim",
            "fence",
            "complete",
            "reject",
            "release",
            "tryLockInstance",
            "lockInstance",
            "nextQueueSequence",
            "recordStart");

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger crashes = new AtomicInteger();
    private final Map<WorkflowInstanceId, Fault> faults = new ConcurrentHashMap<>();
    private final Map<WorkflowInstanceId, Function<ClaimedCommand, Future<Void>>> afterClaim =
            new ConcurrentHashMap<>();
    private final AtomicBoolean dispositionGateSpent = new AtomicBoolean();
    private volatile Supplier<Future<Void>> beforeFirstDisposition;

    private record Fault(AtomicInteger remaining, Supplier<? extends Throwable> failure, boolean crash) {}

    /** Abandons every segment of the instance as a crash would. */
    CommandFaults crashEverySegmentOf(WorkflowInstanceId id) {
        return crashSegmentsOf(id, Integer.MAX_VALUE);
    }

    /** Abandons the first {@code times} segments of the instance as a crash would. */
    CommandFaults crashSegmentsOf(WorkflowInstanceId id, int times) {
        faults.put(
                id,
                new Fault(
                        new AtomicInteger(times),
                        () -> new TransactionDeadlineExceededException("the test abandoned the segment"),
                        true));
        return this;
    }

    /** Fails the first {@code times} segments of the instance with the failure, which the runner maps. */
    CommandFaults failSegmentsOf(WorkflowInstanceId id, int times, Supplier<? extends Throwable> failure) {
        faults.put(id, new Fault(new AtomicInteger(times), failure, false));
        return this;
    }

    /** Lets the claim of the instance's command return only when the action's future completes. Used once. */
    CommandFaults afterClaimOf(WorkflowInstanceId id, Function<ClaimedCommand, Future<Void>> action) {
        afterClaim.put(id, action);
        return this;
    }

    /** Holds the first call of a failure disposition until the future completes. */
    CommandFaults beforeFirstDisposition(Supplier<Future<Void>> gate) {
        this.beforeFirstDisposition = gate;
        return this;
    }

    /** The names of the members called, in order. */
    List<String> calls() {
        return List.copyOf(calls);
    }

    /** How often the member was called. */
    long callsOf(String member) {
        return calls.stream().filter(member::equals).count();
    }

    /** How many segments were abandoned as a crash. */
    int crashes() {
        return crashes.get();
    }

    /** The decorator for {@code NodeSpec.commands}. */
    @SuppressWarnings("unchecked")
    UnaryOperator<WorkflowCommandRepository<SqlClient>> decorator() {
        return real -> (WorkflowCommandRepository<SqlClient>) Proxy.newProxyInstance(
                WorkflowCommandRepository.class.getClassLoader(),
                new Class<?>[] {WorkflowCommandRepository.class},
                (proxy, method, arguments) -> {
                    Callable<Object> forward = () -> {
                        try {
                            return method.invoke(real, arguments);
                        } catch (InvocationTargetException e) {
                            throw e.getCause() instanceof Exception cause ? cause : e;
                        }
                    };
                    String member = method.getName();
                    if (method.getDeclaringClass() == Object.class) {
                        return forward.call();
                    }
                    calls.add(member);
                    if (member.equals("tryLockInstance")) {
                        Throwable failure = nextFault((WorkflowInstanceId) arguments[0]);
                        if (failure != null) {
                            return Future.failedFuture(failure);
                        }
                    }
                    if (member.equals("claim")) {
                        return afterClaim(((CommandHead) arguments[0]).workflowId(), forward);
                    }
                    if (isDisposition(member)
                            && beforeFirstDisposition != null
                            && dispositionGateSpent.compareAndSet(false, true)) {
                        return beforeFirstDisposition.get().compose(ignored -> {
                            try {
                                return (Future<Object>) forward.call();
                            } catch (Exception e) {
                                return Future.failedFuture(e);
                            }
                        });
                    }
                    return forward.call();
                });
    }

    private Throwable nextFault(WorkflowInstanceId id) {
        Fault fault = faults.get(id);
        if (fault == null
                || fault.remaining().getAndUpdate(n -> n == Integer.MAX_VALUE ? n : Math.max(0, n - 1)) <= 0) {
            return null;
        }
        if (fault.crash()) {
            crashes.incrementAndGet();
        }
        return fault.failure().get();
    }

    @SuppressWarnings("unchecked")
    private Object afterClaim(WorkflowInstanceId id, Callable<Object> forward) throws Exception {
        Future<Optional<ClaimedCommand>> claimed = (Future<Optional<ClaimedCommand>>) forward.call();
        return claimed.compose(result -> {
            Function<ClaimedCommand, Future<Void>> action = result.isPresent() ? afterClaim.remove(id) : null;
            return action == null
                    ? Future.succeededFuture(result)
                    : action.apply(result.get()).map(ignored -> result);
        });
    }

    private static boolean isDisposition(String member) {
        return !NOT_A_DISPOSITION.contains(member) && !member.startsWith("find") && !member.startsWith("count");
    }
}
