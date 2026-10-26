// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dev.vertique.workflow.engine.spi.CommandWakeups;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.QueueSnapshot;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The node's one connection that listens for commands becoming pending or deferred, shared by every executor
 * instance of the node.
 *
 * <p>The first {@link #register registration} opens a dedicated connection outside the pool with the pool's connect
 * options (TLS and credentials), and the last close closes it. The connection issues {@code LISTEN
 * vertique_workflow_commands} and nothing else: it never starts a transaction. A notification wakes every registered
 * instance on that instance's own context. The payload is the command's available time in epoch milliseconds; it is a
 * hint and is never trusted beyond that, so a payload that is not digits only wakes the instances to peek now. The
 * notification is database-wide: an instance of any node, or of another engine on the same database, may be woken for
 * a command it does not own, and peeks and finds nothing.
 *
 * <p>A connection that cannot be opened or is lost leaves the instances polling. The listener logs one warning for
 * the outage, without connection details, and tries to open the connection again once per reconnect interval.
 *
 * <p>While at least one executor observer is bound, the listener also reads {@code pg_notification_queue_usage()} on
 * a pooled connection once per queue-usage interval, outside any transaction, and reports it to the observers as
 * {@link QueueSnapshot#notificationQueueUsage()}. The other fields of that snapshot are zero: they are reported by
 * the executor instances. An exception an observer throws is caught and never affects the listener.
 */
public final class CommandNotificationListener implements CommandWakeups {

    private static final Logger log = LoggerFactory.getLogger(CommandNotificationListener.class);

    /** The channel the commands' trigger notifies on. */
    static final String CHANNEL = "vertique_workflow_commands";

    private static final String USAGE_SQL = "SELECT pg_notification_queue_usage()";

    /** The most digits a payload may have to be read as a time; an epoch in milliseconds has 13. */
    private static final int MAX_PAYLOAD_DIGITS = 18;

    private final Vertx vertx;
    private final PgConnectOptions connectOptions;
    private final Pool pool;
    private final List<WorkflowExecutorObserver> observers;
    private final long reconnectIntervalMs;
    private final long queueUsageIntervalMs;

    private final Object lock = new Object();
    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    // guarded by lock
    private boolean active;
    private int generation;
    private PgConnection connection;
    private long reconnectTimerId = -1;
    private long usageTimerId = -1;
    private boolean warned;

    /**
     * Creates the listener of one node. It opens nothing until the first registration.
     *
     * @param vertx                the Vert.x instance the connection and timers belong to
     * @param connectOptions       the pool's connect options; the connection is opened with them
     * @param pool                 the pool the queue usage is read through
     * @param observers            the executor observers the queue usage is reported to; may be empty
     * @param reconnectIntervalMs  the least time between two attempts to open the connection, in milliseconds
     * @param queueUsageIntervalMs the time between two reads of the queue usage, in milliseconds
     */
    public CommandNotificationListener(
            Vertx vertx,
            PgConnectOptions connectOptions,
            Pool pool,
            Set<WorkflowExecutorObserver> observers,
            long reconnectIntervalMs,
            long queueUsageIntervalMs) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.connectOptions = Objects.requireNonNull(connectOptions, "connectOptions");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.observers = List.copyOf(Objects.requireNonNull(observers, "observers"));
        if (reconnectIntervalMs < 1 || queueUsageIntervalMs < 1) {
            throw new IllegalArgumentException("the intervals must be at least 1 ms");
        }
        this.reconnectIntervalMs = reconnectIntervalMs;
        this.queueUsageIntervalMs = queueUsageIntervalMs;
    }

    @Override
    public Registration register(Context context, WakeupSink sink) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(sink, "sink");
        Entry entry = new Entry(context, sink);
        boolean first;
        synchronized (lock) {
            first = entries.isEmpty();
            entries.add(entry);
            if (first) {
                active = true;
                generation++;
            }
        }
        if (first) {
            connect(currentGeneration());
            scheduleUsageRead(currentGeneration(), 1);
        }
        return () -> deregister(entry);
    }

    private void deregister(Entry entry) {
        PgConnection toClose = null;
        synchronized (lock) {
            if (!entries.remove(entry)) {
                return;
            }
            entry.closed = true;
            if (entries.isEmpty()) {
                active = false;
                generation++;
                toClose = connection;
                connection = null;
                cancelTimer(reconnectTimerId);
                cancelTimer(usageTimerId);
                reconnectTimerId = -1;
                usageTimerId = -1;
                warned = false;
            }
        }
        if (toClose != null) {
            toClose.close();
        }
    }

    private int currentGeneration() {
        synchronized (lock) {
            return generation;
        }
    }

    private boolean current(int generation) {
        synchronized (lock) {
            return active && this.generation == generation;
        }
    }

    // --- Connection ---

    private void connect(int generation) {
        if (!current(generation)) {
            return;
        }
        AtomicBoolean closedEarly = new AtomicBoolean();
        PgConnection.connect(vertx, connectOptions)
                .compose(conn -> {
                    conn.notificationHandler(notification -> {
                        if (CHANNEL.equals(notification.getChannel())) {
                            dispatch(parse(notification.getPayload()));
                        }
                    });
                    conn.closeHandler(v -> {
                        closedEarly.set(true);
                        lost(generation, conn);
                    });
                    return conn.query("LISTEN " + CHANNEL)
                            .execute()
                            .<PgConnection>map(rows -> conn)
                            .recover(failure -> {
                                conn.close();
                                return io.vertx.core.Future.failedFuture(failure);
                            });
                })
                .onComplete(result -> {
                    if (result.failed()) {
                        failed(generation, result.cause());
                        return;
                    }
                    opened(generation, result.result(), closedEarly);
                });
    }

    /**
     * Keeps a connection that has finished its {@code LISTEN}, unless it is no longer wanted. A connection that closed
     * before it was stored was missed by the close handler, so it is treated as lost here: otherwise nothing would
     * ever open a replacement.
     *
     * @return whether the connection is kept
     */
    boolean opened(int generation, PgConnection conn, AtomicBoolean closedEarly) {
        boolean keep;
        synchronized (lock) {
            keep = active && this.generation == generation && connection == null;
            if (keep) {
                connection = conn;
                warned = false;
            }
        }
        if (!keep) {
            conn.close();
            return false;
        }
        if (closedEarly.get()) {
            lost(generation, conn);
            return false;
        }
        return true;
    }

    /** Whether a listening connection is stored. */
    boolean connected() {
        synchronized (lock) {
            return connection != null;
        }
    }

    private void failed(int generation, Throwable cause) {
        if (warnOnce(generation)) {
            log.warn(
                    "The workflow command listener could not open its connection; executors poll ({})",
                    cause.getClass().getName());
        }
        log.debug("Workflow command listener connection failure", cause);
        scheduleReconnect(generation);
    }

    private void lost(int generation, PgConnection conn) {
        synchronized (lock) {
            if (connection != conn) {
                return;
            }
            connection = null;
        }
        if (warnOnce(generation)) {
            log.warn("The workflow command listener lost its connection; executors poll until it is open again");
        }
        scheduleReconnect(generation);
    }

    /** Whether this is the first problem since the connection was last open, in the current generation. */
    private boolean warnOnce(int generation) {
        synchronized (lock) {
            if (!active || this.generation != generation || warned) {
                return false;
            }
            warned = true;
            return true;
        }
    }

    private void scheduleReconnect(int generation) {
        synchronized (lock) {
            if (!active || this.generation != generation || reconnectTimerId >= 0) {
                return;
            }
            try {
                // raw timer: one-shot reconnect attempt, at most one per interval
                reconnectTimerId = vertx.setTimer(reconnectIntervalMs, id -> {
                    synchronized (lock) {
                        reconnectTimerId = -1;
                    }
                    connect(generation);
                });
            } catch (RuntimeException e) {
                // Vert.x is closing: the listener ends with it
                log.debug("No reconnect could be scheduled", e);
            }
        }
    }

    // --- Notifications ---

    /** The available time in the payload, or 0 (a time in the past, so peek now) for anything but digits. */
    private static long parse(String payload) {
        if (payload == null || payload.isEmpty() || payload.length() > MAX_PAYLOAD_DIGITS) {
            return 0;
        }
        long value = 0;
        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    private void dispatch(long availableAtEpochMs) {
        for (Entry entry : entries) {
            try {
                entry.context.runOnContext(ignored -> {
                    if (entry.closed) {
                        return;
                    }
                    try {
                        entry.sink.wake(availableAtEpochMs);
                    } catch (RuntimeException e) {
                        log.debug("A workflow executor wake-up failed", e);
                    }
                });
            } catch (RuntimeException e) {
                log.debug("A workflow executor wake-up could not be delivered", e);
            }
        }
    }

    // --- Queue usage ---

    private void scheduleUsageRead(int generation, long delayMs) {
        if (observers.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (!active || this.generation != generation) {
                return;
            }
            try {
                // raw timer: one-shot read, rescheduled when the previous read ends so that reads never overlap
                usageTimerId = vertx.setTimer(delayMs, id -> readUsage(generation));
            } catch (RuntimeException e) {
                log.debug("No queue usage read could be scheduled", e);
            }
        }
    }

    private void readUsage(int generation) {
        if (!current(generation)) {
            return;
        }
        io.vertx.core.Future<io.vertx.sqlclient.RowSet<Row>> read;
        try {
            read = pool.query(USAGE_SQL).execute();
        } catch (RuntimeException e) {
            read = io.vertx.core.Future.failedFuture(e);
        }
        read.onComplete(result -> {
            if (result.succeeded() && current(generation)) {
                double usage = usage(result.result());
                if (usage >= 0) {
                    emit(new QueueSnapshot(0, 0, 0, 0, usage));
                }
            } else if (result.failed()) {
                log.debug("The notification queue usage could not be read", result.cause());
            }
            scheduleUsageRead(generation, queueUsageIntervalMs);
        });
    }

    private static double usage(Iterable<Row> rows) {
        for (Row row : rows) {
            Double value = row.getDouble(0);
            if (value != null && !value.isNaN()) {
                return Math.min(1.0, Math.max(0.0, value));
            }
        }
        return -1;
    }

    private void emit(QueueSnapshot snapshot) {
        for (WorkflowExecutorObserver observer : observers) {
            try {
                observer.onEvent(snapshot);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError error) {
                    throw error;
                }
                log.warn(
                        "Executor observer {} threw while handling a QueueSnapshot event: {}",
                        observer.getClass().getName(),
                        t.getClass().getName());
                log.debug("Executor observer failure", t);
            }
        }
    }

    private void cancelTimer(long id) {
        if (id >= 0) {
            vertx.cancelTimer(id);
        }
    }

    private static final class Entry {
        final Context context;
        final WakeupSink sink;
        volatile boolean closed;

        Entry(Context context, WakeupSink sink) {
            this.context = context;
            this.sink = sink;
        }
    }
}
