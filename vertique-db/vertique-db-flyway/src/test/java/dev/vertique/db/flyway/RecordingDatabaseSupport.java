// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for the package-private database-support boundary of the Flyway runner. It never
 * touches a database: it records every call it receives, and it can be told to fail so a test can
 * prove how the runner reacts.
 *
 * <p>The nested {@link Selector} hands the runner this double (or reports an unsupported
 * destination), and the nested {@link CountingConnector} stands in for the runner's own JDBC
 * connector and fails every attempt, so any connection the runner opens by itself is both counted
 * and visible as a failure.
 */
final class RecordingDatabaseSupport implements DatabaseSupport {

    /** One recorded {@link #open(String, String, String)} call. */
    record Open(String jdbcUrl, String user, String password) {

        // The password is deliberately kept out of the rendering so a failing assertion cannot print it.
        @Override
        public String toString() {
            return "Open[jdbcUrl=" + jdbcUrl + ", user=" + user + "]";
        }
    }

    private final List<List<MigrationInstanceView>> validatedBatches = new CopyOnWriteArrayList<>();
    private final List<Open> opens = new CopyOnWriteArrayList<>();
    private final List<List<MigrationInstanceView>> preflights = new CopyOnWriteArrayList<>();
    private final List<MigrationInstanceView> beforeInstances = new CopyOnWriteArrayList<>();
    private final AtomicInteger closedSessions = new AtomicInteger();

    private volatile MigrationRunException validationFailure;
    private volatile RuntimeException validationRuntimeFailure;
    private volatile SQLException openFailure;

    /**
     * Makes {@link #validateConfiguration(List)} throw the given exception after recording the call.
     *
     * @param failure the exception to throw, or {@code null} to accept every configuration again
     * @return this double
     */
    RecordingDatabaseSupport failValidationWith(MigrationRunException failure) {
        this.validationFailure = failure;
        return this;
    }

    /**
     * Makes {@link #validateConfiguration(List)} throw the given unchecked exception after recording
     * the call, to stand in for a backend that breaks its own contract. It takes precedence over a
     * failure set with {@link #failValidationWith(MigrationRunException)}.
     *
     * @param failure the exception to throw, or {@code null} to stop throwing it
     * @return this double
     */
    RecordingDatabaseSupport failValidationWithRuntime(RuntimeException failure) {
        this.validationRuntimeFailure = failure;
        return this;
    }

    /**
     * Makes {@link #open(String, String, String)} throw the given exception after recording the call.
     *
     * @param failure the exception to throw, or {@code null} to open sessions again
     * @return this double
     */
    RecordingDatabaseSupport failOpenWith(SQLException failure) {
        this.openFailure = failure;
        return this;
    }

    /**
     * Returns the view lists handed to {@link #validateConfiguration(List)}, one entry per call.
     *
     * @return the recorded batches, oldest first
     */
    List<List<MigrationInstanceView>> validatedBatches() {
        return List.copyOf(validatedBatches);
    }

    /**
     * Returns the recorded {@link #open(String, String, String)} calls.
     *
     * @return the recorded calls, oldest first
     */
    List<Open> opens() {
        return List.copyOf(opens);
    }

    /**
     * Returns the view lists handed to {@link Session#preflight(List)}.
     *
     * @return the recorded batches, oldest first
     */
    List<List<MigrationInstanceView>> preflights() {
        return List.copyOf(preflights);
    }

    /**
     * Returns the views handed to {@link Session#beforeInstance(MigrationInstanceView)}.
     *
     * @return the recorded views, oldest first
     */
    List<MigrationInstanceView> beforeInstances() {
        return List.copyOf(beforeInstances);
    }

    /**
     * Returns how many sessions opened by this double have been closed.
     *
     * @return the number of closed sessions
     */
    int closedSessions() {
        return closedSessions.get();
    }

    /**
     * Returns a selector that always hands the runner this double and records the destination it was
     * asked about.
     *
     * @return a recording selector backed by this double
     */
    Selector selector() {
        return new Selector(this);
    }

    /**
     * Returns a selector that reports every destination as unsupported.
     *
     * @return a recording selector with no support
     */
    static Selector unsupportedDestination() {
        return new Selector(null);
    }

    @Override
    public void validateConfiguration(List<MigrationInstanceView> views) {
        validatedBatches.add(List.copyOf(views));
        RuntimeException runtimeFailure = validationRuntimeFailure;
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
        MigrationRunException failure = validationFailure;
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public Session open(String jdbcUrl, String user, String password) throws SQLException {
        opens.add(new Open(jdbcUrl, user, password));
        SQLException failure = openFailure;
        if (failure != null) {
            throw failure;
        }
        return new RecordingSession();
    }

    /** Session handed out by {@link RecordingDatabaseSupport#open(String, String, String)}. */
    private final class RecordingSession implements Session {

        @Override
        public void preflight(List<MigrationInstanceView> enabledViews) {
            preflights.add(List.copyOf(enabledViews));
        }

        @Override
        public void beforeInstance(MigrationInstanceView view) {
            beforeInstances.add(view);
        }

        @Override
        public void close() {
            closedSessions.incrementAndGet();
        }
    }

    /** Selector that records every destination it is asked about. */
    static final class Selector implements DatabaseSupportSelector {

        private final RecordingDatabaseSupport support;
        private final List<String> selectedUrls = new CopyOnWriteArrayList<>();
        private volatile RuntimeException selectFailure;

        private Selector(RecordingDatabaseSupport support) {
            this.support = support;
        }

        @Override
        public Optional<DatabaseSupport> select(String jdbcUrl) {
            selectedUrls.add(jdbcUrl);
            RuntimeException failure = selectFailure;
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(support);
        }

        /**
         * Makes {@link #select(String)} throw the given exception after recording the destination, to
         * stand in for a selector that breaks its own contract.
         *
         * @param failure the exception to throw, or {@code null} to answer normally again
         * @return this selector
         */
        Selector failSelectWith(RuntimeException failure) {
            this.selectFailure = failure;
            return this;
        }

        /**
         * Returns the JDBC URLs this selector was asked about.
         *
         * @return the recorded URLs, oldest first
         */
        List<String> selectedUrls() {
            return List.copyOf(selectedUrls);
        }
    }

    /** JDBC connector that counts every attempt and fails it, so no connection can ever succeed. */
    static final class CountingConnector implements JdbcConnector {

        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public Connection connect(String url, String user, String password) throws SQLException {
            attempts.incrementAndGet();
            throw new SQLException("the counting connector never connects");
        }

        /**
         * Returns how many connections the runner tried to open through this connector.
         *
         * @return the number of connection attempts
         */
        int attempts() {
            return attempts.get();
        }
    }
}
