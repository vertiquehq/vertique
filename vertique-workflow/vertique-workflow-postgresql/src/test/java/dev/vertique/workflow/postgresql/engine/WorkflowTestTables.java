// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.engine.WorkflowExecutorControl;
import io.vertx.core.Future;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Empties workflow tables for a test without deadlocking against the executors that poll the same database.
 *
 * <p>A test that truncates workflow tables while an executor of its engine polls can deadlock: the executor's claim
 * statements and {@code TRUNCATE ... CASCADE} take table locks in different orders. Every fixture that truncates
 * therefore goes through {@link #truncating}: the executors of the engines this test support built over the same
 * database are stopped (which waits for every segment in flight), the tables are truncated, and the executors that were
 * running are started again. A truncation that is still chosen as a deadlock victim (40P01), for example by a session
 * the support does not track, is retried a bounded number of times.
 */
public final class WorkflowTestTables {

    private static final int ATTEMPTS = 5;

    private static final Set<TrackedExecutor> EXECUTORS = ConcurrentHashMap.newKeySet();

    private WorkflowTestTables() {
        // static support
    }

    /**
     * A statement that empties tables; run it with {@link Truncation#execute()}.
     *
     * @param client the client the statement runs on
     * @param sql    the {@code TRUNCATE} statement
     * @return the statement
     */
    public static Truncation truncating(SqlClient client, String sql) {
        return new Truncation(client, sql);
    }

    /** A {@code TRUNCATE} statement that quiesces the executors of its database first. */
    public static final class Truncation {

        private final SqlClient client;
        private final String sql;

        private Truncation(SqlClient client, String sql) {
            this.client = client;
            this.sql = sql;
        }

        /**
         * Stops the tracked executors of the client's database, truncates, and restarts the executors it stopped.
         *
         * @return the result of the truncation
         */
        public Future<RowSet<Row>> execute() {
            return client.query("SELECT current_database()").execute().compose(rows -> {
                String database = rows.iterator().next().getString(0);
                return forDatabase(database).compose(running -> {
                    List<Future<Void>> stops = new ArrayList<>();
                    for (TrackedExecutor executor : running) {
                        stops.add(executor.pause());
                    }
                    return Future.join(stops).transform(stopped -> attempt(1)).transform(truncated -> restart(running)
                            .transform(restarted -> truncated.succeeded()
                                    ? Future.succeededFuture(truncated.result())
                                    : Future.<RowSet<Row>>failedFuture(truncated.cause())));
                });
            });
        }

        private Future<RowSet<Row>> attempt(int number) {
            return client.query(sql).execute().recover(failure -> {
                if (number < ATTEMPTS && deadlock(failure)) {
                    return attempt(number + 1);
                }
                return Future.failedFuture(failure);
            });
        }

        private static Future<Void> restart(List<TrackedExecutor> paused) {
            List<Future<Void>> starts = new ArrayList<>();
            for (TrackedExecutor executor : paused) {
                starts.add(executor.resume());
            }
            return Future.join(starts).mapEmpty();
        }
    }

    /** The executors that are running on the database and whose pool is still open. */
    private static Future<List<TrackedExecutor>> forDatabase(String database) {
        List<Future<TrackedExecutor>> candidates = new ArrayList<>();
        for (TrackedExecutor executor : EXECUTORS) {
            if (!executor.running) {
                continue;
            }
            candidates.add(owns(executor, database));
        }
        return Future.join(candidates).map(joined -> {
            List<TrackedExecutor> matching = new ArrayList<>();
            for (int i = 0; i < joined.size(); i++) {
                TrackedExecutor executor = joined.resultAt(i);
                if (executor != null) {
                    matching.add(executor);
                }
            }
            return matching;
        });
    }

    /** The executor when it runs on the database, otherwise {@code null}; the executor of a closed pool is forgotten. */
    private static Future<TrackedExecutor> owns(TrackedExecutor executor, String database) {
        try {
            return executor.pool
                    .query("SELECT current_database()")
                    .execute()
                    .map(rows -> database.equals(rows.iterator().next().getString(0)) ? executor : null)
                    .recover(failure -> forget(executor));
        } catch (RuntimeException closed) {
            // the pool, or the Vert.x instance it ran on, of an earlier test is gone
            return forget(executor);
        }
    }

    private static Future<TrackedExecutor> forget(TrackedExecutor executor) {
        EXECUTORS.remove(executor);
        return Future.succeededFuture(null);
    }

    private static boolean deadlock(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PgException pg && "40P01".equals(pg.getSqlState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wraps an engine so that its executor is known to the truncation helper.
     *
     * @param engine the engine
     * @param pool   the pool the engine transacts on
     * @return a handle that answers {@code executor()} with a tracking control and delegates everything else
     */
    static WorkflowEngineHandle tracking(WorkflowEngineHandle engine, Pool pool) {
        TrackedExecutor tracked = new TrackedExecutor(engine.executor(), pool);
        EXECUTORS.add(tracked);
        return (WorkflowEngineHandle) Proxy.newProxyInstance(
                WorkflowEngineHandle.class.getClassLoader(), new Class<?>[] {WorkflowEngineHandle.class}, (p, m, a) -> {
                    if ("executor".equals(m.getName()) && m.getParameterCount() == 0) {
                        return tracked;
                    }
                    try {
                        return m.invoke(engine, a);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** An executor control that remembers whether the executor is running. */
    private static final class TrackedExecutor implements WorkflowExecutorControl {

        private final WorkflowExecutorControl delegate;
        private final Pool pool;
        private volatile boolean running;

        private TrackedExecutor(WorkflowExecutorControl delegate, Pool pool) {
            this.delegate = delegate;
            this.pool = pool;
        }

        @Override
        public Future<Void> start() {
            return delegate.start().onSuccess(v -> running = true);
        }

        @Override
        public Future<Void> stop() {
            running = false;
            return delegate.stop();
        }

        /** Stops the executor for a truncation, keeping the mark that it is to run again. */
        private Future<Void> pause() {
            try {
                return delegate.stop();
            } catch (RuntimeException e) {
                return Future.failedFuture(e);
            }
        }

        /** Starts the executor again unless the test stopped it meanwhile. */
        private Future<Void> resume() {
            try {
                return running ? delegate.start() : Future.succeededFuture();
            } catch (RuntimeException e) {
                return Future.failedFuture(e);
            }
        }
    }
}
