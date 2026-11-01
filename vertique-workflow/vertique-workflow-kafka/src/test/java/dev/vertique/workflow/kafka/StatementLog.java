// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Query;
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
import java.util.function.Function;

/**
 * A decorator of a node's pool that records every statement the node executes, with the values bound to its
 * parameters, in the order it ran. It changes nothing the node does.
 */
final class StatementLog {

    /**
     * One execution of a statement.
     *
     * @param sql the statement text
     * @param values the values bound to its parameters; empty for a statement without any
     */
    record Execution(String sql, List<Object> values) {

        /** The text, lower case on one line, for matching. */
        String normalized() {
            return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        }
    }

    private final List<Execution> executions = new CopyOnWriteArrayList<>();

    /** Wraps the pool so that its executions are recorded here. */
    Pool observe(Pool delegate) {
        return (Pool) wrapClient(delegate, Pool.class);
    }

    /** Every execution seen so far, oldest first. */
    List<Execution> executions() {
        return List.copyOf(executions);
    }

    /** The executions that insert into {@code workflow_commands} a command of the kind. */
    List<Execution> commandInserts(String kind) {
        String literal = "'" + kind.toLowerCase(Locale.ROOT) + "'";
        return executions.stream()
                .filter(execution -> {
                    String sql = execution.normalized();
                    return sql.contains("insert into") && sql.contains("workflow_commands") && sql.contains(literal);
                })
                .toList();
    }

    /** The executions that take a command for an executor, which set it {@code CLAIMED} and name the claimant. */
    List<Execution> claims() {
        return executions.stream()
                .filter(execution -> {
                    String sql = execution.normalized();
                    return sql.startsWith("update") && sql.contains("state = 'claimed'") && sql.contains("claimed_by");
                })
                .toList();
    }

    /** The executions that insert, update or delete rows of the definition or activation tables. */
    List<Execution> definitionWrites() {
        return executions.stream()
                .filter(execution -> {
                    String sql = execution.normalized();
                    return (sql.startsWith("insert") || sql.startsWith("update") || sql.startsWith("delete"))
                            && (sql.contains("workflow_definitions")
                                    || sql.contains("workflow_definition_activations"));
                })
                .toList();
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
                forwarded[i] = (Function<Object, Object>) connection -> original.apply(wrapIfConnection(connection));
            }
        }
        Object result = invokeDelegate(delegate, method, forwarded);
        String sql = sqlArgument(method, forwarded);
        if (sql != null && method.getName().equals("prepare")) {
            executions.add(new Execution(sql, List.of()));
        }
        if (sql != null && result instanceof Query<?> query) {
            return wrapQuery(query, method.getReturnType(), sql);
        }
        if (result instanceof Future<?> future) {
            return future.map(this::wrapIfConnection);
        }
        if (result instanceof SqlClient client && result != delegate) {
            return wrapClient(client, SqlClient.class);
        }
        return result;
    }

    private Object wrapIfConnection(Object value) {
        return value instanceof SqlConnection connection ? wrapClient(connection, SqlConnection.class) : value;
    }

    private Object wrapQuery(Object delegate, Class<?> queryType, String sql) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("execute")) {
                executions.add(new Execution(
                        sql, args != null && args.length > 0 && args[0] instanceof Tuple t ? values(t) : List.of()));
            } else if (method.getName().equals("executeBatch") && args != null && args[0] instanceof List<?> batch) {
                for (Object tuple : batch) {
                    if (tuple instanceof Tuple t) executions.add(new Execution(sql, values(t)));
                }
            }
            Object result = invokeDelegate(delegate, method, args);
            if (result instanceof Query<?> next && method.getReturnType().isInterface()) {
                return wrapQuery(next, method.getReturnType(), sql);
            }
            return result;
        };
        return Proxy.newProxyInstance(queryType.getClassLoader(), new Class<?>[] {queryType}, handler);
    }

    private static String sqlArgument(Method method, Object[] args) {
        String name = method.getName();
        boolean statement = name.equals("query") || name.equals("preparedQuery") || name.equals("prepare");
        return statement && args.length > 0 && args[0] instanceof String sql ? sql : null;
    }

    private static List<Object> values(Tuple tuple) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < tuple.size(); i++) {
            values.add(tuple.getValue(i));
        }
        return values;
    }

    private static Object invokeDelegate(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
