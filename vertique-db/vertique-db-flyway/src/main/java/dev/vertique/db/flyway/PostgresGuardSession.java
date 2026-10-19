// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import static dev.vertique.db.flyway.NamedMigrationFailure.COORDINATION;
import static dev.vertique.db.flyway.NamedMigrationFailure.OP_COORDINATION;
import static dev.vertique.db.flyway.NamedMigrationFailure.OP_RUN;
import static dev.vertique.db.flyway.NamedMigrationFailure.PREFLIGHT;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Guard session of one named PostgreSQL run, on a dedicated direct JDBC connection opened with the root
 * credential pair.
 *
 * <p>Opening captures the connection's {@code pg_catalog.current_schema()}, then pins only this session
 * to {@code search_path = pg_catalog} and confirms through {@code pg_catalog.version()} that the
 * destination is PostgreSQL. From then on every catalog relation and function is qualified with
 * {@code pg_catalog} and every value is bound. Migration sessions are never touched.
 *
 * <p>When a batch migrates, the session takes the advisory session lock with the two-integer key
 * ({@value #LOCK_KEY_FIRST}, {@value #LOCK_KEY_SECOND}) before any database-dependent check, polling
 * {@code pg_catalog.pg_try_advisory_lock} until the configured timeout has passed. It holds the lock until
 * it is closed and, before each instance, confirms that the same backend still owns it; a migrating
 * instance reached without the lock is rejected. VALIDATE-only batches never take it. The lock serializes
 * this wrapper's runs only: concurrent external schema DDL, history writers and role or search-path
 * changes must be excluded by the operator, and the connection must not go through a transaction-pooling
 * proxy.
 *
 * <p>The checks:
 *
 * <ul>
 *   <li>Every enabled instance without a known default schema gets the {@code pg_catalog.current_schema()}
 *       of a short connection opened with its own credential pair, without touching that connection's
 *       search path; the result is frozen for the run, and an unresolved default is rejected.
 *   <li>History tables of the frozen views must not collide in one schema, counting derived relations.
 *   <li>A migrating instance whose history table is absent must not manage a mix of existing and missing
 *       schemas. Only schema existence is read from {@code pg_catalog.pg_namespace}, never whether a
 *       schema holds objects. The check is repeated immediately before the instance runs.
 *   <li>While some migrating instance initializes its history, a conventional {@code flyway_schema_history}
 *       in the captured default schema, the first root schema, any managed schema or any frozen default
 *       blocks the batch, unless an enabled instance names it explicitly as its own history in its frozen
 *       default schema. Such owners are returned for native validation. The probe is repeated over the
 *       same schemas immediately before each migrating instance whose history is absent, with the owners
 *       read again from the current catalog. This detection is bounded to the conventional name; a
 *       custom-named history is not found.
 * </ul>
 *
 * <p>A catalog or privilege failure rejects the batch instead of assuming a state. Every connection this
 * session opens gets a network timeout of {@value #NETWORK_TIMEOUT_MS} ms before its first statement, so
 * each round trip is bounded and a timeout fails closed. Every connection is closed on every exit; a
 * connection that does not close cleanly is aborted.
 */
final class PostgresGuardSession implements DatabaseSupport.Session {

    static final int LOCK_KEY_FIRST = 1448365393;
    static final int LOCK_KEY_SECOND = 1296648018;

    private static final String CONVENTIONAL_HISTORY = "flyway_schema_history";
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    /** Upper bound on each round trip of a connection this session owns, so a silent path cannot block it. */
    static final int NETWORK_TIMEOUT_MS = 30_000;

    private static final String CURRENT_SCHEMA = "SELECT pg_catalog.current_schema()";
    private static final String PIN_SEARCH_PATH = "SET SESSION search_path = pg_catalog";
    private static final String VERSION = "SELECT pg_catalog.version()";
    private static final String BACKEND_PID = "SELECT pg_catalog.pg_backend_pid()";
    private static final String TRY_LOCK = "SELECT pg_catalog.pg_try_advisory_lock(?, ?)";
    private static final String OWNS_LOCK = "SELECT pg_catalog.pg_backend_pid() = ? AND EXISTS (SELECT 1"
            + " FROM pg_catalog.pg_locks l WHERE l.locktype = 'advisory'"
            + " AND l.classid = CAST(? AS pg_catalog.oid) AND l.objid = CAST(? AS pg_catalog.oid)"
            + " AND l.objsubid = 2 AND l.granted AND l.pid = pg_catalog.pg_backend_pid())";
    private static final String SCHEMA_EXISTS =
            "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n WHERE n.nspname = ?)";
    private static final String TABLE_EXISTS = "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_class c"
            + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r', 'p'))";

    private final JdbcConnector connector;
    private final String jdbcUrl;
    private final Connection connection;
    private final String capturedDefault;
    private final int pid;
    private final long lockTimeoutMs;
    private boolean locked;

    /** The enabled frozen views of a migrating batch, kept from its preflight for the per-instance recheck. */
    private List<MigrationInstanceView> frozenViews = List.of();

    /** The schemas probed for a conventional history, kept from the preflight of a migrating batch. */
    private Set<String> probedSchemas = Set.of();

    private PostgresGuardSession(
            JdbcConnector connector,
            String jdbcUrl,
            Connection connection,
            String capturedDefault,
            int pid,
            long lockTimeoutMs) {
        this.connector = connector;
        this.jdbcUrl = jdbcUrl;
        this.connection = connection;
        this.capturedDefault = capturedDefault;
        this.pid = pid;
        this.lockTimeoutMs = lockTimeoutMs;
    }

    /**
     * Opens the guard connection, captures its default schema, pins its search path and confirms the
     * database.
     *
     * @param connector the connector for connections the support owns
     * @param jdbcUrl the root JDBC URL
     * @param user the root migration user
     * @param password the root migration password
     * @param lockTimeoutMs the upper bound on waiting for the coordination lock
     * @return the open session
     * @throws SQLException if the connection cannot be opened or prepared
     * @throws MigrationRunException with failure code {@code PREFLIGHT} when the destination is not
     *     PostgreSQL
     */
    static PostgresGuardSession open(
            JdbcConnector connector, String jdbcUrl, String user, String password, long lockTimeoutMs)
            throws SQLException {
        Connection connection = connector.connect(jdbcUrl, user, password);
        boolean opened = false;
        try {
            connection.setNetworkTimeout(Runnable::run, NETWORK_TIMEOUT_MS);
            connection.setAutoCommit(true);
            String capturedDefault = string(connection, CURRENT_SCHEMA);
            try (Statement statement = connection.createStatement()) {
                statement.execute(PIN_SEARCH_PATH);
            }
            String version = string(connection, VERSION);
            if (version == null || !version.startsWith("PostgreSQL ")) {
                throw new MigrationRunException(
                        "the destination does not identify itself as PostgreSQL, the only database named"
                                + " migration supports",
                        PREFLIGHT,
                        OP_RUN,
                        null);
            }
            int pid = Integer.parseInt(string(connection, BACKEND_PID));
            PostgresGuardSession session =
                    new PostgresGuardSession(connector, jdbcUrl, connection, capturedDefault, pid, lockTimeoutMs);
            opened = true;
            return session;
        } finally {
            if (!opened) {
                release(connection);
            }
        }
    }

    @Override
    public DatabaseSupport.Preflight preflight(List<NamedPlan.Instance> enabledInstances, String rootFirstSchema) {
        boolean migrates =
                enabledInstances.stream().anyMatch(instance -> instance.view().mode() == FlywayMode.MIGRATE);
        if (migrates) {
            acquireLock();
        }
        List<MigrationInstanceView> frozen = new ArrayList<>();
        for (NamedPlan.Instance instance : enabledInstances) {
            MigrationInstanceView view = instance.view();
            frozen.add(view.schema() != null ? view : view.withSchema(resolveDefault(instance)));
        }
        String collision = PostgresIdentifiers.collision(frozen);
        if (collision != null) {
            throw preflightFailure(collision);
        }
        if (!migrates) {
            return new DatabaseSupport.Preflight(frozen, Set.of());
        }
        frozenViews = List.copyOf(frozen);
        probedSchemas = probedSchemas(frozen, rootFirstSchema);
        try {
            return checkInitialization(frozen);
        } catch (SQLException ex) {
            throw preflightFailure("the catalog could not be read to check the batch" + sqlState(ex));
        }
    }

    private DatabaseSupport.Preflight checkInitialization(List<MigrationInstanceView> frozen) throws SQLException {
        List<MigrationInstanceView> initializing = new ArrayList<>();
        Map<String, String> ownersBySchema = new LinkedHashMap<>();
        for (MigrationInstanceView view : frozen) {
            boolean historyExists = tableExists(view.schema(), view.table());
            if (!historyExists && view.mode() == FlywayMode.MIGRATE) {
                initializing.add(view);
            }
            if (historyExists && CONVENTIONAL_HISTORY.equals(view.table())) {
                ownersBySchema.put(view.schema(), view.name());
            }
        }
        if (initializing.isEmpty()) {
            return new DatabaseSupport.Preflight(frozen, Set.of());
        }
        for (MigrationInstanceView view : initializing) {
            checkManagedSchemas(view);
        }
        checkConventionalHistory(initializing.get(0), ownersBySchema.keySet());
        return new DatabaseSupport.Preflight(frozen, Set.copyOf(ownersBySchema.values()));
    }

    @Override
    public void beforeInstance(MigrationInstanceView frozenView) {
        if (frozenView.mode() == FlywayMode.MIGRATE && !locked) {
            throw coordinationFailure("the guard session does not hold the coordination lock");
        }
        if (locked) {
            verifyLock();
        }
        if (frozenView.mode() != FlywayMode.MIGRATE) {
            return;
        }
        try {
            if (!tableExists(frozenView.schema(), frozenView.table())) {
                checkManagedSchemas(frozenView);
                checkConventionalHistory(frozenView, currentConventionalOwners());
            }
        } catch (SQLException ex) {
            throw preflightFailure(
                    "the catalog could not be read to recheck instance '" + frozenView.name() + "'" + sqlState(ex));
        }
    }

    @Override
    public void close() {
        release(connection);
    }

    // --- Coordination lock ---

    private void acquireLock() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lockTimeoutMs);
        try (PreparedStatement tryLock = connection.prepareStatement(TRY_LOCK)) {
            tryLock.setInt(1, LOCK_KEY_FIRST);
            tryLock.setInt(2, LOCK_KEY_SECOND);
            while (true) {
                if (bool(tryLock)) {
                    locked = true;
                    return;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw coordinationFailure(
                            "the coordination lock could not be acquired within flyway.coordinationLockTimeoutMs");
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(POLL_NANOS, remaining));
            }
        } catch (SQLException ex) {
            throw coordinationFailure("the coordination lock could not be requested" + sqlState(ex));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw coordinationFailure("waiting for the coordination lock was interrupted");
        }
    }

    private void verifyLock() {
        boolean owned;
        try (PreparedStatement ownsLock = connection.prepareStatement(OWNS_LOCK)) {
            ownsLock.setInt(1, pid);
            ownsLock.setInt(2, LOCK_KEY_FIRST);
            ownsLock.setInt(3, LOCK_KEY_SECOND);
            owned = bool(ownsLock);
        } catch (SQLException ex) {
            owned = false;
        }
        if (!owned) {
            throw coordinationFailure("the guard session no longer holds the coordination lock");
        }
    }

    // --- Default schemas and initialization ---

    /** Reads the default schema of a short connection opened with the instance's own credential pair. */
    private String resolveDefault(NamedPlan.Instance instance) {
        String name = instance.view().name();
        String schema;
        Connection own = null;
        try {
            own = connector.connect(jdbcUrl, instance.view().user(), instance.password());
            own.setNetworkTimeout(Runnable::run, NETWORK_TIMEOUT_MS);
            schema = string(own, CURRENT_SCHEMA);
        } catch (SQLException ex) {
            throw preflightFailure(
                    "the connection default schema of instance '" + name + "' could not be read" + sqlState(ex));
        } finally {
            if (own != null) {
                release(own);
            }
        }
        if (schema == null) {
            throw preflightFailure("the connection default schema of instance '" + name
                    + "' resolves to no existing schema; give the instance a schema list or a db.schemas entry,"
                    + " or align its role's search path");
        }
        PostgresIdentifiers.checkResolvedSchema(name, schema);
        return schema;
    }

    /** Rejects a managed schema set that mixes existing and missing schemas. */
    private void checkManagedSchemas(MigrationInstanceView view) throws SQLException {
        List<String> managed = view.schemas().isEmpty() ? List.of(view.schema()) : view.schemas();
        boolean anyExisting = false;
        boolean anyMissing = false;
        for (String schema : managed) {
            if (schemaExists(schema)) {
                anyExisting = true;
            } else {
                anyMissing = true;
            }
        }
        if (anyExisting && anyMissing) {
            throw preflightFailure("instance '" + view.name()
                    + "' has no history yet and only some of its managed schemas exist; provision every managed"
                    + " schema of the instance, or none of them, and run again with the same baseline settings");
        }
    }

    /**
     * Returns the schemas probed for a conventional history: the captured default schema, the first root
     * schema, and every managed and frozen default schema of the batch.
     */
    private Set<String> probedSchemas(List<MigrationInstanceView> frozen, String rootFirstSchema) {
        Set<String> probed = new LinkedHashSet<>();
        if (capturedDefault != null) {
            probed.add(capturedDefault);
        }
        if (rootFirstSchema != null) {
            probed.add(rootFirstSchema);
        }
        for (MigrationInstanceView view : frozen) {
            probed.addAll(view.schemas());
            probed.add(view.schema());
        }
        return probed;
    }

    /**
     * Reads, from the current catalog, the frozen default schemas in which an enabled instance's explicit
     * conventional history exists.
     */
    private Set<String> currentConventionalOwners() throws SQLException {
        Set<String> owned = new LinkedHashSet<>();
        for (MigrationInstanceView view : frozenViews) {
            if (CONVENTIONAL_HISTORY.equals(view.table()) && tableExists(view.schema(), CONVENTIONAL_HISTORY)) {
                owned.add(view.schema());
            }
        }
        return owned;
    }

    /** Rejects initializing an instance's history while a probed schema holds an unowned conventional history. */
    private void checkConventionalHistory(MigrationInstanceView initializing, Set<String> ownedSchemas)
            throws SQLException {
        for (String schema : probedSchemas) {
            if (!ownedSchemas.contains(schema) && tableExists(schema, CONVENTIONAL_HISTORY)) {
                throw preflightFailure("instance '" + initializing.name()
                        + "' would initialize its history while a conventional single-Flyway history that no"
                        + " instance owns exists; keep the single-Flyway configuration for this database");
            }
        }
    }

    // --- Catalog queries ---

    private boolean schemaExists(String schema) throws SQLException {
        return exists(SCHEMA_EXISTS, schema);
    }

    private boolean tableExists(String schema, String table) throws SQLException {
        return exists(TABLE_EXISTS, schema, table);
    }

    private boolean exists(String sql, String... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            return bool(statement);
        }
    }

    private static String string(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new SQLException("the query returned no row");
            }
            return rows.getString(1);
        }
    }

    private static boolean bool(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            return rows.next() && rows.getBoolean(1);
        }
    }

    // --- Failures and release ---

    private static MigrationRunException preflightFailure(String detail) {
        return new MigrationRunException(detail, PREFLIGHT, OP_RUN, null);
    }

    private static MigrationRunException coordinationFailure(String detail) {
        return new MigrationRunException(detail, COORDINATION, OP_COORDINATION, null);
    }

    /** Returns {@code " (SQLState XXXXX)"} for a well-formed SQLState, else empty. */
    private static String sqlState(SQLException ex) {
        return NamedMigrationFailure.sqlStateSuffix(ex.getSQLState());
    }

    /** Closes a connection without throwing, aborting it when it does not close cleanly. */
    static void release(Connection connection) {
        try {
            connection.close();
        } catch (SQLException | RuntimeException ex) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException ignored) {
                // Nothing more can be done for a connection that neither closes nor aborts.
            }
        }
    }
}
