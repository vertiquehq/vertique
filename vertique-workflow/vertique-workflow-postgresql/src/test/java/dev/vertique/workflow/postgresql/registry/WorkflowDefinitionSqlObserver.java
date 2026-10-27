// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import io.vertx.core.Future;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Records the result shape of definition-body SQL without changing the application-facing graph. */
final class WorkflowDefinitionSqlObserver {

    private final List<ObservedQuery> queries = new CopyOnWriteArrayList<>();
    private final List<String> statements = new CopyOnWriteArrayList<>();
    private final List<Execution> executions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean shortenDefinitionLockTimeout = new AtomicBoolean();
    private volatile long shortenedLockTimeoutMillis = 500;
    private volatile long stickyShortenedLockTimeoutMillis;
    private final java.util.concurrent.atomic.AtomicInteger shortenedDefinitionLockTimeouts =
            new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicBoolean failNextEncodingLookup = new AtomicBoolean();
    private volatile PgException nextEncodingLookupFailure;
    private final AtomicBoolean completionLockArmed = new AtomicBoolean();
    private final AtomicBoolean completionResponseArmed = new AtomicBoolean();
    private volatile CompletionFailureGate completionFailureGate;
    private volatile CompletionLockGate completionLockGate;
    private volatile CompletionResponseLossGate completionResponseLossGate;
    private final AtomicBoolean failNextActivationWrite = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger injectedActivationFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger activationWrites =
            new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicBoolean failNextFailureMarkWrite = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger injectedFailureMarkWrites =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger failureMarkWrites =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.regex.Pattern FAILURE_MARK_WRITE = java.util.regex.Pattern.compile(
            "update\\s+(?:\"?[\\w$]+\"?\\.)?\"?workflow_definitions\"?\\s+set\\s+failure_reason\\b");
    private static final java.util.regex.Pattern ACTIVATION_WRITE = java.util.regex.Pattern.compile(
            "(insert\\s+into|update|merge\\s+into)\\s+(?:\"?[\\w$]+\"?\\.)?\"?workflow_definition_activations\"?\\b");

    Pool observe(Pool delegate) {
        return (Pool) wrapClient(delegate, Pool.class);
    }

    List<ObservedQuery> queries() {
        return List.copyOf(queries);
    }

    List<String> statements() {
        return List.copyOf(statements);
    }

    /** Every statement execution seen, in order, with the values bound to its parameters. */
    List<Execution> executions() {
        return List.copyOf(executions);
    }

    void shortenNextDefinitionLockTimeout() {
        shortenedLockTimeoutMillis = 500;
        shortenDefinitionLockTimeout.set(true);
    }

    /** Shortens the next definition lock timeout to the given wait, leaving time to observe the queued lock. */
    void shortenNextDefinitionLockTimeout(long millis) {
        shortenedLockTimeoutMillis = millis;
        shortenDefinitionLockTimeout.set(true);
    }

    /** Shortens every definition lock timeout issued from now on to the given wait. */
    void shortenEveryDefinitionLockTimeout(long millis) {
        stickyShortenedLockTimeoutMillis = millis;
    }

    int shortenedDefinitionLockTimeouts() {
        return shortenedDefinitionLockTimeouts.get();
    }

    /**
     * Fails the next statement that inserts or updates an activation record, once, with a non-transient database
     * error, so the transaction that issued it cannot commit. Reads and deletes of records are not affected.
     */
    void failNextActivationWrite() {
        failNextActivationWrite.set(true);
    }

    /** How many times {@link #failNextActivationWrite} has made a statement fail. */
    int injectedActivationFailures() {
        return injectedActivationFailures.get();
    }

    /** How many statements that insert or update an activation record this observer has seen issued. */
    int activationWrites() {
        return activationWrites.get();
    }

    /**
     * Fails the next statement that writes a definition row's failing mark, once, with a non-transient database error.
     * Other statements, among them the executor's own, are not affected.
     */
    void failNextFailureMarkWrite() {
        failNextFailureMarkWrite.set(true);
    }

    /** How many times {@link #failNextFailureMarkWrite} has made a statement fail. */
    int injectedFailureMarkWrites() {
        return injectedFailureMarkWrites.get();
    }

    /** How many statements that write a failing mark this observer has seen issued, the failed ones included. */
    int failureMarkWrites() {
        return failureMarkWrites.get();
    }

    private static boolean isFailureMarkWrite(String sql) {
        return FAILURE_MARK_WRITE
                .matcher(sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))
                .find();
    }

    private static boolean isActivationWrite(String sql) {
        return ACTIVATION_WRITE
                .matcher(sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))
                .find();
    }

    int encodingLookupCount() {
        return (int) statements.stream()
                .filter(statement -> statement.toLowerCase(Locale.ROOT).contains("server_encoding"))
                .count();
    }

    PgException failNextEncodingLookup() {
        PgException failure = new PgException("controlled encoding lookup failure", "ERROR", "57P01", null);
        nextEncodingLookupFailure = failure;
        failNextEncodingLookup.set(true);
        return failure;
    }

    CompletionFailureGate holdNextTransactionFailure(String messageMarker) {
        CompletionFailureGate gate = new CompletionFailureGate(messageMarker);
        completionFailureGate = gate;
        return gate;
    }

    CompletionLockGate holdNextCompletionLockAfterClaim() {
        CompletionLockGate gate = new CompletionLockGate();
        completionLockGate = gate;
        completionLockArmed.set(false);
        return gate;
    }

    CompletionResponseLossGate loseNextSuccessfulCompletionResponse() {
        CompletionResponseLossGate gate = new CompletionResponseLossGate();
        completionResponseLossGate = gate;
        completionResponseArmed.set(false);
        return gate;
    }

    private Object wrapClient(Object delegate, Class<?> clientType) {
        InvocationHandler handler = (proxy, method, args) -> invokeClient(delegate, method, args);
        return Proxy.newProxyInstance(clientType.getClassLoader(), new Class<?>[] {clientType}, handler);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object invokeClient(Object delegate, Method method, Object[] args) throws Throwable {
        Object[] forwarded = args == null ? new Object[0] : args.clone();
        for (int i = 0; i < forwarded.length; i++) {
            if (forwarded[i] instanceof Function<?, ?> function) {
                Function<Object, Object> original = (Function<Object, Object>) function;
                forwarded[i] = (Function<Object, Object>)
                        connection -> original.apply(wrapClient(connection, SqlConnection.class));
            }
        }

        String sql = sqlArgument(method, forwarded);
        if (sql != null) {
            statements.add(sql);
            if (isActivationWrite(sql)) activationWrites.incrementAndGet();
            if (isFailureMarkWrite(sql)) failureMarkWrites.incrementAndGet();
            long sticky = stickyShortenedLockTimeoutMillis;
            if (isDefinitionLockTimeout(sql)
                    && (sticky > 0 || shortenDefinitionLockTimeout.compareAndSet(true, false))) {
                long wait = sticky > 0 ? sticky : shortenedLockTimeoutMillis;
                for (int i = 0; i < forwarded.length; i++) {
                    if (sql.equals(forwarded[i])) forwarded[i] = "SET LOCAL lock_timeout = '" + wait + "ms'";
                }
                shortenedDefinitionLockTimeouts.incrementAndGet();
            }
        }
        Object result = invokeDelegate(delegate, method, forwarded);
        if (sql != null && result != null && method.getReturnType().isInterface()) {
            return wrapStatement(result, method.getReturnType(), sql);
        }
        if (result instanceof Future<?> future) {
            if (method.getName().equals("withTransaction")) {
                return future.transform(outcome -> {
                    if (outcome.failed()) {
                        completionResponseArmed.set(false);
                        CompletionFailureGate gate = completionFailureGate;
                        if (gate != null && gate.capture(outcome.cause())) {
                            return gate.released().compose(ignored -> Future.failedFuture(outcome.cause()));
                        }
                        return Future.failedFuture(outcome.cause());
                    }
                    if (completionResponseArmed.compareAndSet(true, false)) {
                        CompletionResponseLossGate responseLoss = completionResponseLossGate;
                        if (responseLoss != null && responseLoss.capture()) {
                            return Future.failedFuture(responseLoss.failure());
                        }
                    }
                    Object value = outcome.result();
                    if (value instanceof SqlConnection connection) {
                        value = wrapClient(connection, SqlConnection.class);
                    }
                    return Future.succeededFuture(value);
                });
            }
            return future.map(value -> {
                if (value instanceof SqlConnection connection) {
                    return wrapClient(connection, SqlConnection.class);
                }
                return value;
            });
        }
        if (result instanceof SqlClient client) {
            return wrapClient(client, SqlClient.class);
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object wrapStatement(Object delegate, Class<?> statementType, String sql) {
        InvocationHandler handler = (proxy, method, args) -> {
            recordExecution(sql, method, args);
            if (method.getName().equals("execute")
                    && isActivationWrite(sql)
                    && failNextActivationWrite.compareAndSet(true, false)) {
                injectedActivationFailures.incrementAndGet();
                return Future.failedFuture(
                        new PgException("controlled activation write failure", "ERROR", "XX000", null));
            }
            if (method.getName().equals("execute")
                    && isFailureMarkWrite(sql)
                    && failNextFailureMarkWrite.compareAndSet(true, false)) {
                injectedFailureMarkWrites.incrementAndGet();
                return Future.failedFuture(
                        new PgException("controlled failure mark write failure", "ERROR", "XX000", null));
            }
            if (method.getName().equals("execute")
                    && sql.toLowerCase(Locale.ROOT).contains("pg_advisory_xact_lock")
                    && completionLockArmed.compareAndSet(true, false)) {
                CompletionLockGate gate = completionLockGate;
                if (gate != null && gate.capture()) {
                    return gate.released().compose(ignored -> executeAndObserveFuture(delegate, method, args, sql));
                }
            }
            if (sql.toLowerCase(Locale.ROOT).contains("server_encoding")
                    && method.getName().equals("execute")
                    && failNextEncodingLookup.compareAndSet(true, false)) {
                return Future.failedFuture(nextEncodingLookupFailure);
            }
            return executeAndObserve(delegate, method, args, sql);
        };
        return Proxy.newProxyInstance(statementType.getClassLoader(), new Class<?>[] {statementType}, handler);
    }

    private void recordExecution(String sql, Method method, Object[] args) {
        if (method.getName().equals("execute")) {
            executions.add(new Execution(
                    sql, args != null && args.length > 0 && args[0] instanceof Tuple t ? values(t) : List.of()));
        } else if (method.getName().equals("executeBatch") && args != null && args[0] instanceof List<?> batch) {
            for (Object tuple : batch) if (tuple instanceof Tuple t) executions.add(new Execution(sql, values(t)));
        }
    }

    private static List<Object> values(Tuple tuple) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < tuple.size(); i++) {
            Object value = tuple.getValue(i);
            values.add(value instanceof Object[] array ? java.util.Arrays.asList(array) : value);
        }
        return values;
    }

    private Object executeAndObserve(Object delegate, Method method, Object[] args, String sql) throws Throwable {
        Object result = invokeDelegate(delegate, method, args == null ? new Object[0] : args);
        if (result instanceof Future<?> future) {
            return future.map(rows -> {
                String normalized =
                        sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").stripLeading();
                if (normalized.startsWith("update workflow_definitions set status='publishing'")) {
                    completionLockArmed.set(true);
                }
                if (normalized.startsWith("update workflow_definitions set status='available', publish_token=null")
                        && normalized.contains("and status='publishing' and publish_token=$4")
                        && rows instanceof RowSet<?> rowSet
                        && rowSet.rowCount() > 0) {
                    completionResponseArmed.set(true);
                }
                observeRows(sql, rows);
                return rows;
            });
        }
        return result;
    }

    private Future<?> executeAndObserveFuture(Object delegate, Method method, Object[] args, String sql) {
        try {
            Object result = executeAndObserve(delegate, method, args, sql);
            return result instanceof Future<?> future ? future : Future.succeededFuture(result);
        } catch (Throwable failure) {
            return Future.failedFuture(failure);
        }
    }

    private void observeRows(String sql, Object result) {
        String normalized = sql.toLowerCase(Locale.ROOT);
        if (!normalized.contains("workflow_definitions")
                || !normalized.contains("document")
                || !(result instanceof Iterable<?> rows)) {
            return;
        }

        List<ObservedRow> observedRows = new ArrayList<>();
        for (Object value : rows) {
            if (!(value instanceof Row row)) {
                continue;
            }
            if (row.getColumnIndex("document") < 0) {
                continue;
            }
            Object body = rowValue(row, "document");
            Object definitionId = rowValue(row, "definition_id");
            Object documentSize = sizeProjection(row);
            Object documentCodePoints = codePointProjection(row);
            observedRows.add(new ObservedRow(
                    definitionId == null ? null : definitionId.toString(), body, documentSize, documentCodePoints));
        }
        if (!observedRows.isEmpty()) {
            queries.add(new ObservedQuery(sql, List.copyOf(observedRows)));
        }
    }

    private static Object sizeProjection(Row row) {
        for (int index = 0; index < row.size(); index++) {
            String column = row.getColumnName(index).toLowerCase(Locale.ROOT);
            if (column.contains("byte") || column.contains("size") || column.contains("octet")) {
                return row.getValue(index);
            }
        }
        return null;
    }

    private static Object codePointProjection(Row row) {
        for (int index = 0; index < row.size(); index++) {
            String column = row.getColumnName(index).toLowerCase(Locale.ROOT);
            if (column.contains("code_point") || column.contains("codepoint") || column.contains("char_count")) {
                return row.getValue(index);
            }
        }
        return null;
    }

    private static Object rowValue(Row row, String column) {
        return row.getColumnIndex(column) < 0 ? null : row.getValue(column);
    }

    private static String sqlArgument(Method method, Object[] args) {
        if ((method.getName().equals("query") || method.getName().equals("preparedQuery"))
                && args.length > 0
                && args[0] instanceof String sql) {
            return sql;
        }
        return null;
    }

    private static boolean isDefinitionLockTimeout(String sql) {
        return sql.strip().replaceAll("\\s+", " ").equalsIgnoreCase("SET LOCAL lock_timeout = '30000ms'");
    }

    private static Object invokeDelegate(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    /** One execution of a statement with the values bound to its parameters, arrays as lists. */
    record Execution(String sql, List<Object> parameters) {}

    record ObservedQuery(String sql, List<ObservedRow> rows) {}

    record ObservedRow(String definitionId, Object documentBody, Object documentSizeBytes, Object documentCodePoints) {}

    static final class CompletionFailureGate {

        private final String messageMarker;
        private final io.vertx.core.Promise<Throwable> observed = io.vertx.core.Promise.promise();
        private final io.vertx.core.Promise<Void> release = io.vertx.core.Promise.promise();
        private final AtomicBoolean captured = new AtomicBoolean();

        private CompletionFailureGate(String messageMarker) {
            this.messageMarker = messageMarker;
        }

        Future<Throwable> observed() {
            return observed.future();
        }

        void release() {
            release.tryComplete();
        }

        private Future<Void> released() {
            return release.future();
        }

        private boolean capture(Throwable failure) {
            if (!containsMessage(failure, messageMarker) || !captured.compareAndSet(false, true)) {
                return false;
            }
            observed.tryComplete(failure);
            return true;
        }

        private static boolean containsMessage(Throwable failure, String marker) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause.getMessage() != null && cause.getMessage().contains(marker)) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class CompletionLockGate {

        private final io.vertx.core.Promise<Void> observed = io.vertx.core.Promise.promise();
        private final io.vertx.core.Promise<Void> release = io.vertx.core.Promise.promise();
        private final AtomicBoolean captured = new AtomicBoolean();

        Future<Void> observed() {
            return observed.future();
        }

        void release() {
            release.tryComplete();
        }

        private Future<Void> released() {
            return release.future();
        }

        private boolean capture() {
            if (!captured.compareAndSet(false, true)) return false;
            observed.tryComplete();
            return true;
        }
    }

    static final class CompletionResponseLossGate {

        private final IllegalStateException failure =
                new IllegalStateException("controlled committed activation response loss");
        private final io.vertx.core.Promise<Throwable> injected = io.vertx.core.Promise.promise();
        private final AtomicBoolean captured = new AtomicBoolean();

        Future<Throwable> injected() {
            return injected.future();
        }

        private Throwable failure() {
            return failure;
        }

        private boolean capture() {
            if (!captured.compareAndSet(false, true)) return false;
            injected.tryComplete(failure);
            return true;
        }
    }
}
