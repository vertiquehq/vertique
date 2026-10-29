// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dev.vertique.db.IsolationLevel;
import dev.vertique.db.exception.ConnectionException;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A transaction runner decorator that fails the first transactions that carry a deadline, which are the segments of
 * claimed commands, with a failure of the database layer. The real runner still opens the transaction, rolls it back
 * and maps the failure, so the executor sees what it would see after a real fault.
 */
final class FailingTransactions implements UnaryOperator<WorkflowTransactionRunner<SqlClient>> {

    private final int times;
    private final Supplier<? extends Throwable> failure;
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    private FailingTransactions(int times, Supplier<? extends Throwable> failure) {
        this.times = times;
        this.failure = failure;
    }

    /**
     * Fails the first {@code times} segments.
     *
     * @param times   how many segments fail
     * @param failure creates the failure of each
     * @return the decorator
     */
    static FailingTransactions times(int times, Supplier<? extends Throwable> failure) {
        return new FailingTransactions(times, failure);
    }

    /** Fails every segment. */
    static FailingTransactions always(Supplier<? extends Throwable> failure) {
        return new FailingTransactions(Integer.MAX_VALUE, failure);
    }

    /** A lost connection. */
    static Supplier<Throwable> connectionFailure() {
        return connectionFailure("connection reset by peer");
    }

    /** A lost connection whose message is the given text. */
    static Supplier<Throwable> connectionFailure(String message) {
        return () -> new ConnectionException(message);
    }

    /** How many segments this decorator failed. */
    int failed() {
        return failed.get();
    }

    @Override
    public WorkflowTransactionRunner<SqlClient> apply(WorkflowTransactionRunner<SqlClient> delegate) {
        return new WorkflowTransactionRunner<>() {
            @Override
            public <R> Future<R> inTransaction(@Nullable IsolationLevel level, Function<SqlClient, Future<R>> work) {
                return delegate.inTransaction(level, work);
            }

            @Override
            public <R> Future<R> inTransaction(
                    @Nullable IsolationLevel level, Duration deadline, Function<SqlClient, Future<R>> work) {
                if (calls.getAndIncrement() < times) {
                    failed.incrementAndGet();
                    return delegate.inTransaction(level, deadline, tx -> Future.<R>failedFuture(failure.get()));
                }
                return delegate.inTransaction(level, deadline, work);
            }
        };
    }
}
