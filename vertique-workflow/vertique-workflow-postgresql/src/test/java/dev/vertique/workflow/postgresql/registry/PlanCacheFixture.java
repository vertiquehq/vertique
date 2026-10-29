// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.cache.CacheCoreModule;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.VertxModule;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParserProbe;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.di.WorkflowDefinitionsConfigModule;
import dev.vertique.workflow.di.WorkflowExecutorConfigModule;
import dev.vertique.workflow.engine.registry.PlanCache;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.migration.WorkflowMigrationModule;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.postgresql.engine.WorkflowTestTables;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservationModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.CallbackCatalogModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.CodeCatalogModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.DefinitionSourceModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.LargeUnicodeMapperModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.PostgresInfrastructureModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.TypeCatalogModule;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.schema.WorkflowSchemaModule;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

/**
 * An application graph with a running executor over a pool of a chosen size, a plan cache of a chosen size and a
 * recording cache observer, and the document rows and recorded starts the plan cache tests run against.
 *
 * <p>The graph starts first and the document rows are seeded afterwards, with the propagation period set so high that
 * the poller never folds them in, so the node's registry never holds them and only the plan cache can make them
 * runnable. Instances are recorded as {@code PENDING} with a {@code START} command straight through the command
 * repository, because a start through the engine selects its version from the node's registry. Everything a test
 * observes is read through a pool of its own that the graph does not use.
 */
final class PlanCacheFixture implements AutoCloseable {

    static final String ACME = "acme";
    static final String DOCUMENT_ID = "plan-cache-document";
    static final String FIRST_WAIT = "wait-paid";

    /** How long a test waits for the executor to settle recorded work. */
    static final Duration IDLE = Duration.ofSeconds(60);

    private static final Instant LOADED_AT = Instant.parse("2026-10-27T12:00:00Z");

    /** The contract and wiring of the graph: everything of the shared graph plus the cache module and the schema module. */
    @Module
    abstract static class JsonBindings {

        @Provides
        @Singleton
        static JsonMapperProfileRegistry profileRegistry() {
            return new DefaultJsonMapperProfileRegistry(Set.of());
        }

        @Provides
        static JsonConfig jsonConfig() {
            return JsonConfig.defaults();
        }
    }

    /** Contributes the fixture's recording observer to the cache observers of the graph. */
    @Module
    static final class ObserverModule {

        private final RecordingCacheObserver observer;

        ObserverModule(RecordingCacheObserver observer) {
            this.observer = observer;
        }

        @Provides
        @IntoSet
        CacheObserver observer() {
            return observer;
        }
    }

    /** The shared graph with the schema module and the cache module added, and the entry points the tests use. */
    interface PlanCacheGraph extends AppComponent {

        PlanCache planCache();

        WorkflowInstanceRepository<SqlClient> instanceRepository();

        WorkflowCommandRepository<SqlClient> commandRepository();

        Set<PersistedDefinitionLoader> persistedLoaders();
    }

    @Singleton
    @Component(
            modules = {
                dev.vertique.core.VertxModule.class,
                ConfigParsingModule.class,
                WorkflowDefinitionsConfigModule.class,
                WorkflowExecutorConfigModule.class,
                DeployerModule.class,
                CoreLifecycleStepsModule.class,
                WorkflowPostgresqlModule.class,
                dev.vertique.workflow.definition.di.WorkflowDefinitionModule.class,
                DefinitionSourceModule.class,
                CodeCatalogModule.class,
                LargeUnicodeMapperModule.class,
                CallbackCatalogModule.class,
                PostgresInfrastructureModule.class,
                BootObservationModule.class,
                WorkflowDefinitionParserProbe.ParserModule.class,
                TypeCatalogModule.class,
                WorkflowSchemaModule.class,
                WorkflowMigrationModule.class,
                JsonBindings.class,
                CacheCoreModule.class,
                ObserverModule.class
            })
    interface PlanCacheApp extends PlanCacheGraph {

        @Component.Builder
        interface Builder {

            Builder vertxModule(VertxModule module);

            Builder postgresInfrastructureModule(PostgresInfrastructureModule module);

            Builder bootObservationModule(BootObservationModule module);

            Builder definitionSourceModule(DefinitionSourceModule module);

            Builder codeCatalogModule(CodeCatalogModule module);

            Builder largeUnicodeMapperModule(LargeUnicodeMapperModule module);

            Builder parserModule(WorkflowDefinitionParserProbe.ParserModule module);

            Builder typeCatalogModule(TypeCatalogModule module);

            Builder observerModule(ObserverModule module);

            PlanCacheApp build();
        }
    }

    /** A command row as the tests read it. */
    record CommandRow(
            long id,
            String kind,
            String state,
            int attempts,
            int deferralCount,
            String lastError,
            OffsetDateTime availableAt) {}

    /** A history entry as the tests read it. */
    record HistoryEntry(long sequence, String type, String transaction) {}

    private final Vertx vertx;
    private final Pool admin;
    private final Pool enginePool;
    private final WorkflowDefinitionSqlObserver sql = new WorkflowDefinitionSqlObserver();
    private final WorkflowDefinitionParserProbe parserProbe = new WorkflowDefinitionParserProbe();
    private final RecordingCacheObserver cacheEvents = new RecordingCacheObserver();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final PlanCacheGraph app;
    private final VertiqueApplicationHandle<AppComponent> handle;
    private final AtomicInteger recorded = new AtomicInteger();
    private int parserBaseline;
    private int queryBaseline;
    private boolean closed;

    private PlanCacheFixture(
            Vertx vertx, PostgresContainer db, int maxEntries, int poolSize, int acquisitionTimeoutSeconds) {
        this.vertx = vertx;
        this.admin = pool(vertx, db, 4, 0);
        resetTables();
        this.enginePool = pool(vertx, db, poolSize, acquisitionTimeoutSeconds);
        logs.start();
        rootLogger.addAppender(logs);
        JsonObject config = new JsonObject()
                .put(
                        "workflow",
                        new JsonObject()
                                .put(
                                        "definitions",
                                        new JsonObject()
                                                .put("cache", new JsonObject().put("maximumEntries", maxEntries)))
                                .put("executor", new JsonObject().put("pollIntervalMs", 100)));
        WorkflowDefinitionSource none = List::of;
        PlanCacheGraph built = DaggerPlanCacheFixture_PlanCacheApp.builder()
                .vertxModule(new VertxModule(vertx, config))
                .postgresInfrastructureModule(new PostgresInfrastructureModule(sql.observe(enginePool)))
                .bootObservationModule(
                        new BootObservationModule(new BootObservation("plan-cache-unused", 1, "unused", false)))
                .definitionSourceModule(new DefinitionSourceModule(none))
                .codeCatalogModule(new CodeCatalogModule(false, List.of()))
                .largeUnicodeMapperModule(new LargeUnicodeMapperModule(false))
                .parserModule(parserProbe.module())
                .typeCatalogModule(new TypeCatalogModule(PlanCacheIT.class, List.of()))
                .observerModule(new ObserverModule(cacheEvents))
                .build();
        this.app = built;
        this.handle = await(
                VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, new JsonObject()), ignored -> built));
    }

    /**
     * Starts the graph over a pool of the given size whose acquisition gives up after the given seconds (0 waits
     * indefinitely), with a plan cache of {@code maxEntries} plans.
     */
    static PlanCacheFixture open(
            Vertx vertx, PostgresContainer db, int maxEntries, int poolSize, int acquisitionTimeoutSeconds) {
        return new PlanCacheFixture(vertx, db, maxEntries, poolSize, acquisitionTimeoutSeconds);
    }

    private static Pool pool(Vertx vertx, PostgresContainer db, int size, int acquisitionTimeoutSeconds) {
        var config = db.toPoolConfig();
        return PgBuilder.pool()
                .with(new PoolOptions()
                        .setMaxSize(size)
                        .setConnectionTimeout(acquisitionTimeoutSeconds)
                        .setConnectionTimeoutUnit(TimeUnit.SECONDS))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
    }

    private void resetTables() {
        await(WorkflowTestTables.truncating(
                        admin,
                        "TRUNCATE workflow_commands, workflow_command_queues, workflow_history, workflow_dedup,"
                                + " workflow_timers, workflow_tasks, workflow_instances, workflow_definitions CASCADE")
                .execute());
    }

    // --- Access ---

    PlanCache planCache() {
        return app.planCache();
    }

    RecordingCacheObserver cacheEvents() {
        return cacheEvents;
    }

    PlanCacheGraph app() {
        return app;
    }

    // --- Document rows ---

    /** The document of the given version of the fixture's id, waiting for the {@code paid} signal first. */
    static byte[] document(long version) {
        return ("""
                definitionId: %s
                definitionVersion: %d
                stateType: dev.vertique.workflow.definition.it.fixture.OrderDocState
                contract: %s
                startPayloadType: dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc
                initialStateMapper: doc.order.fromPlaceOrder
                initialStep: %s
                steps:
                  - id: %s
                    type: wait-signal
                    signal: paid
                    payloadType: dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc
                    stateReducer: doc.order.applyInventoryReserved
                    next: done
                  - id: done
                    type: complete
                """.formatted(DOCUMENT_ID, version, PlanCacheIT.PaidContract.class.getName(), FIRST_WAIT, FIRST_WAIT))
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Seeds available versions 1 to {@code count} of the fixture's document in the scope, after the graph started,
     * and checks that this node's registry does not hold any of them. The build and read counters of the fixture
     * count from here.
     */
    List<DefinitionKey> seedVersions(String scope, int count) {
        List<DefinitionKey> keys = new ArrayList<>();
        for (int version = 1; version <= count; version++) {
            keys.add(TenantRowSeed.seed(
                    app,
                    scope,
                    document(version),
                    DefinitionScenario.source("plan-cache-" + version, LOADED_AT),
                    DefinitionStatus.AVAILABLE));
        }
        for (DefinitionKey key : keys) {
            assertThat(app.workflowRegistry().find(key))
                    .as("the node must not hold %s before its first command", key)
                    .isEmpty();
        }
        parserBaseline = parserProbe.calls();
        queryBaseline = sql.queries().size();
        return keys;
    }

    /** Replaces the stored plan hash of the row with one no build of the document can produce. */
    void corruptHash(DefinitionKey key) {
        await(admin.preparedQuery("UPDATE workflow_definitions SET plan_hash = $4 WHERE scope_key = $1 AND"
                        + " definition_id = $2 AND definition_version = $3")
                .execute(Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion(), "f".repeat(64))));
    }

    /** Clears the two columns of the row, as a row written before they existed has them. */
    void legacyRow(DefinitionKey key) {
        await(admin.preparedQuery("UPDATE workflow_definitions SET signal_names_json = NULL, start_schema_json = NULL"
                        + " WHERE scope_key = $1 AND definition_id = $2 AND definition_version = $3")
                .execute(Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion())));
    }

    /** The signal names, start schema and plan hash of the row. */
    JsonObject definitionRow(DefinitionKey key) {
        return await(admin.preparedQuery("SELECT plan_hash, signal_names_json, start_schema_json, xmin::text AS tx"
                                + " FROM workflow_definitions WHERE scope_key = $1 AND definition_id = $2 AND"
                                + " definition_version = $3")
                        .execute(Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion())))
                .iterator()
                .next()
                .toJson();
    }

    // --- Recording ---

    /** Records a {@code PENDING} instance pinned to the row, with its {@code START} command, and commits. */
    WorkflowInstanceId recordStart(DefinitionKey key) {
        String planHash = definitionRow(key).getString("plan_hash");
        int n = recorded.incrementAndGet();
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        Instant now = Instant.now();
        WorkflowInstance pending = new WorkflowInstance(
                id,
                key.definitionId(),
                key.definitionVersion(),
                planHash,
                0L,
                WorkflowStatus.PENDING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now,
                null,
                key.scopeKey(),
                key.scopeKey());
        String payload = new JsonObject().put("orderId", "order-" + n).encode();
        await(caller(() -> admin.withTransaction(tx -> app.commandRepository()
                .lockInstance(id, tx)
                .compose(locked -> app.instanceRepository().insert(pending, tx))
                .compose(inserted -> app.commandRepository().recordStart(id, "idem-" + n, payload, null, tx)))));
        return id;
    }

    /** Records one signal of the instance in a transaction of the test and returns the outcome of the recording. */
    Future<Void> recordSignal(WorkflowInstanceId id, String name, String dedupKey) {
        return caller(() -> admin.withTransaction(tx -> app.transactionalOperations()
                .signal(
                        id,
                        name,
                        new dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc("reservation-1"),
                        dedupKey,
                        tx)));
    }

    private <T> Future<T> caller(Supplier<Future<T>> work) {
        Promise<T> result = Promise.promise();
        ContextInternal context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        context.runOnContext(ignored -> {
            try {
                work.get().onComplete(result);
            } catch (Throwable failure) {
                result.tryFail(failure);
            }
        });
        return result.future();
    }

    // --- Waiting ---

    /** Waits until the executor has nothing left to run for any instance. */
    void awaitAllIdle() {
        await(app.executionProbe().awaitAllIdle(IDLE));
    }

    /** Waits until the executor has nothing left to run for the instance. */
    void awaitIdle(WorkflowInstanceId id) {
        await(app.executionProbe().awaitIdle(id, IDLE));
    }

    /** Polls the condition until it holds or the timeout passes, and fails with the description then. */
    static void awaitCondition(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + description, e);
            }
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    /** Makes the instance's deferred command available now, as if its backoff had elapsed. */
    void fastForward(WorkflowInstanceId id) {
        await(admin.preparedQuery("UPDATE workflow_commands SET available_at = now() WHERE workflow_id = $1")
                .execute(Tuple.of(id.value())));
    }

    // --- Observation ---

    /** The plan builds since the rows were seeded: every document parse the graph made after that. */
    int builds() {
        return parserProbe.calls() - parserBaseline;
    }

    /** The reads of the document text of the key's definition id since the rows were seeded. */
    long documentReads(DefinitionKey key) {
        return sql.queries().stream()
                .skip(queryBaseline)
                .filter(query -> query.rows().stream()
                        .anyMatch(row ->
                                row.definitionId() == null || row.definitionId().equals(key.definitionId())))
                .count();
    }

    /** The statements the graph's pool saw since the rows were seeded, in order. */
    List<String> statements() {
        return sql.statements();
    }

    /** How many statements since the start filled the signal names of a definition row. */
    long fillStatements() {
        return sql.statements().stream()
                .filter(statement -> statement.replaceAll("\\s+", " ").contains("SET signal_names_json=$4"))
                .count();
    }

    /** The log records at warning level or above, formatted with the stack trace messages of their throwables. */
    List<String> warnings() {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            if (!event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)) continue;
            StringBuilder text = new StringBuilder(event.getFormattedMessage());
            for (var proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.append(" | ").append(proxy.getClassName()).append(": ").append(proxy.getMessage());
            }
            messages.add(text.toString());
        }
        return messages;
    }

    List<CommandRow> commandsOf(WorkflowInstanceId id) {
        List<CommandRow> commands = new ArrayList<>();
        for (Row row : await(admin.preparedQuery("SELECT id, kind, state, attempts, deferral_count, last_error,"
                        + " available_at FROM workflow_commands WHERE workflow_id = $1 ORDER BY seq")
                .execute(Tuple.of(id.value())))) {
            commands.add(new CommandRow(
                    row.getLong("id"),
                    row.getString("kind"),
                    row.getString("state"),
                    row.getInteger("attempts"),
                    row.getInteger("deferral_count"),
                    row.getString("last_error"),
                    row.getOffsetDateTime("available_at")));
        }
        return commands;
    }

    /** Every command of every instance. */
    List<CommandRow> allCommands() {
        List<CommandRow> commands = new ArrayList<>();
        for (Row row : await(admin.query("SELECT id, kind, state, attempts, deferral_count, last_error, available_at"
                        + " FROM workflow_commands ORDER BY id")
                .execute())) {
            commands.add(new CommandRow(
                    row.getLong("id"),
                    row.getString("kind"),
                    row.getString("state"),
                    row.getInteger("attempts"),
                    row.getInteger("deferral_count"),
                    row.getString("last_error"),
                    row.getOffsetDateTime("available_at")));
        }
        return commands;
    }

    /** A JSON column value as an object, whether the client delivered it parsed or as text. */
    static JsonObject json(Object value) {
        if (value instanceof JsonObject object) return object;
        if (value instanceof String text) return new JsonObject(text);
        return JsonObject.mapFrom(value);
    }

    /** The status, current step and state of the instance. */
    JsonObject instanceOf(WorkflowInstanceId id) {
        return await(admin.preparedQuery(
                                "SELECT status, current_step_id, state_json FROM workflow_instances" + " WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .toJson();
    }

    List<HistoryEntry> historyOf(WorkflowInstanceId id) {
        List<HistoryEntry> entries = new ArrayList<>();
        for (Row row : await(admin.preparedQuery("SELECT sequence, entry_type, xmin::text AS tx FROM workflow_history"
                        + " WHERE workflow_id = $1 ORDER BY sequence")
                .execute(Tuple.of(id.value())))) {
            entries.add(new HistoryEntry(row.getLong("sequence"), row.getString("entry_type"), row.getString("tx")));
        }
        return entries;
    }

    /** The number of rows in the table. */
    long rowCount(String table) {
        return await(admin.query("SELECT count(*) FROM " + table).execute())
                .iterator()
                .next()
                .getLong(0);
    }

    /** The number of rows in every table whose name mentions an outbox. */
    long outboxRows() {
        long rows = 0;
        for (Row table : await(
                admin.query("SELECT table_name FROM information_schema.tables WHERE table_name LIKE" + " '%outbox%'")
                        .execute())) {
            rows += rowCount(table.getString("table_name"));
        }
        return rows;
    }

    /** The number of {@code SIGNAL} commands and signal dedup rows the instance has. */
    long signalRows(WorkflowInstanceId id) {
        long commands = await(admin.preparedQuery("SELECT count(*) FROM workflow_commands WHERE workflow_id = $1 AND"
                                + " kind = 'SIGNAL'")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getLong(0);
        long dedup = await(admin.preparedQuery(
                                "SELECT count(*) FROM workflow_dedup WHERE workflow_id = $1 AND" + " kind = 'signal'")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getLong(0);
        return commands + dedup;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            await(handle.shutdown());
        } finally {
            rootLogger.detachAppender(logs);
            logs.stop();
            try {
                await(enginePool.close());
            } finally {
                await(admin.close());
            }
        }
    }
}
