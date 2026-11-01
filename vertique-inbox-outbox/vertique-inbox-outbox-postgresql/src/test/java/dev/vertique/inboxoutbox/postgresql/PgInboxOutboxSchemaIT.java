// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.BindsInstance;
import dagger.BindsOptionalOf;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.db.DbModule;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.DbSchemasModule;
import dev.vertique.db.MigrationDefinition;
import dev.vertique.db.MigrationRunner;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.flyway.DbFlywayModule;
import dev.vertique.db.flyway.FlywayConfig;
import dev.vertique.db.flyway.FlywayMigrationRunner;
import dev.vertique.db.flyway.FlywayMode;
import dev.vertique.db.flyway.MigrationInstanceResult;
import dev.vertique.db.flyway.MigrationInstanceView;
import dev.vertique.db.flyway.MigrationReport;
import dev.vertique.db.flyway.MigrationStatus;
import dev.vertique.db.postgresql.DbPostgresqlModule;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.InboxRepository;
import dev.vertique.inboxoutbox.InboxResult;
import dev.vertique.inboxoutbox.InboxService;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxRecord;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.inboxoutbox.RelayCapabilities;
import dev.vertique.inboxoutbox.RelayStrategy;
import dev.vertique.inboxoutbox.postgresql.maintenance.OutboxMaintenanceService;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proof that the neutral schema placement setting places the inbox/outbox migration and every
 * runtime statement of the PostgreSQL adapter in the mapped schema, while the adapter keeps sharing the
 * caller's transaction.
 *
 * <p>A fresh database gets a decoy default schema whose tables carry the inbox and outbox names but an
 * incompatible shape and one sentinel row each, and no migration history. A migration or statement that
 * ignores the mapped schema therefore collides with, or fails against, the decoy instead of silently
 * succeeding, and the decoy's catalog entries and rows are compared after every phase.
 *
 * <p>Three generated Dagger components share one configuration: a migration-only graph with the shipped
 * migration declaration and the named runner, a runtime-only graph with the shipped adapter module and no
 * Flyway module, and a compatibility graph without any placement binding.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PgInboxOutboxSchemaIT {

    private static final String INSTANCE = "inbox-outbox";
    private static final String LOCATION = "classpath:db/migration/inbox-outbox";
    private static final String HISTORY = "flyway_schema_history_inbox_outbox";

    private static final String MAPPED_SCHEMA = "messaging";
    private static final String DECOY_SCHEMA = "public";
    private static final String APP_SCHEMA = "app_ledger";
    private static final String APP_TABLE = APP_SCHEMA + ".orders";

    private static final Set<String> MESSAGING_TABLES = Set.of("inbox", "outbox");
    private static final Set<String> MIGRATED_INDEXES = Set.of(
            "idx_outbox_pending",
            "idx_outbox_processing",
            "idx_outbox_aggregate",
            "idx_outbox_published",
            "idx_outbox_dead_letter",
            "idx_inbox_processed_at");
    private static final String OUTBOX_SEQUENCE = "outbox_id_seq";
    private static final String SCOPE_COLUMN = "scope_key";
    private static final String NOTIFY_FUNCTION = "notify_transactional_outbox";
    private static final String NOTIFY_TRIGGER = "transactional_outbox_notify";

    /** Column names disjoint from every column the adapter references, so any unmapped statement fails. */
    private static final List<String> DECOY_COLUMNS = List.of("decoy_key", "sentinel");

    private static final Set<String> DECOY_INDEXES = Set.of("inbox_pkey", "outbox_pkey");
    private static final String DECOY_OUTBOX_SENTINEL = "decoy-outbox-row";
    private static final String DECOY_INBOX_SENTINEL = "decoy-inbox-row";

    private static final String SOURCE = "schema-placement-it";
    private static final String UNDEFINED_COLUMN = "42703";

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("inbox_outbox_schema");

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("inbox/outbox migration and every adapter operation use the mapped schema and roll back with app work")
    void routesOperationsToMappedSchemaAndRollsBackWithApp(Vertx vertx) throws Exception {
        // Given: a decoy default schema, an application table, and a migration-only plus a runtime-only
        // component sharing one placement configuration
        createDecoyDefaultSchema();
        createApplicationTable();
        MigrationComponent migration =
                DaggerPgInboxOutboxSchemaIT_MigrationComponent.factory().create(vertx, migrationConfig());
        RuntimeComponent runtime =
                DaggerPgInboxOutboxSchemaIT_RuntimeComponent.factory().create(vertx, runtimeConfig());
        try {
            FlywayMigrationRunner runner = migration.runner();

            // Then: both graphs have the intended shape before anything touches the database
            assertMigrationGraphHasNoPool(migration);
            assertRuntimeGraphHasNoFlyway(runtime);
            assertPlacementEqualsConfiguration(migration, runtime);
            assertShippedDeclarationIsTheOnlyOne(migration);
            assertConnectionFreeInstanceView(runner);

            // When: the named runner migrates for the first time
            MigrationReport first = await(runner.run(vertx));

            // Then: both scripts ran and every inbox/outbox object exists only in the mapped schema
            assertInboxOutboxMigrated(first, 2);
            assertObjectsLiveOnlyInMappedSchema();
            assertDecoyDefaultSchemaUntouched();

            // When: the runner is invoked again
            MigrationReport second = await(runner.run(vertx));

            // Then: nothing is applied twice
            assertInboxOutboxMigrated(second, 0);
            assertEquals(List.of("1", "002"), versionedHistory());

            // Then: every concrete and SPI lookup resolves the one scoped repository and its claim identity
            assertScopedRepositoryIdentity(runtime);

            // When: the runtime component serves every outbox, inbox, maintenance and relay operation
            // Then: each one succeeds against the mapped schema, which the decoy shape would not allow
            SettledEntries settled = exerciseOutboxLifecycle(runtime);
            exerciseRepositoryRetentionCleanup(runtime, settled);
            exerciseStaleLeaseRecovery(runtime);
            exerciseInboxOperations(runtime);
            exerciseMaintenanceCleanup(runtime);
            exerciseRelay(vertx, runtime);
            assertDecoyDefaultSchemaUntouched();

            // When: application work, an outbox publish and an inbox record share one failing transaction
            // Then: all three roll back together, and the committed counterpart keeps all three
            assertApplicationWorkRollsBackWithMessaging(runtime);
            assertApplicationWorkCommitsWithMessaging(runtime);
            assertDecoyDefaultSchemaUntouched();

            // When: the adapter module is wired without any placement binding
            // Then: its statements stay unqualified and follow the connection's default schema
            assertCompatibilityGraphKeepsUnqualifiedStatements(vertx, runtime);
            assertDecoyDefaultSchemaUntouched();
        } finally {
            await(runtime.pool().close());
        }
    }

    @AfterEach
    void dropFixtureSchemas() throws SQLException {
        execute("DROP SCHEMA IF EXISTS " + MAPPED_SCHEMA + " CASCADE");
        execute("DROP SCHEMA IF EXISTS " + APP_SCHEMA + " CASCADE");
        execute("DROP TABLE IF EXISTS " + DECOY_SCHEMA + ".outbox, " + DECOY_SCHEMA + ".inbox");
    }

    // --- Components ---

    /** Migration-only graph: the shipped declaration and the named runner, with no Pool and no adapter runtime. */
    @Singleton
    @Component(
            modules = {
                DbModule.class,
                DbFlywayModule.class,
                InboxOutboxMigrationsModule.class,
                ConfigParsingModule.class,
                AbsentPoolProbeModule.class
            })
    interface MigrationComponent {

        FlywayMigrationRunner runner();

        DbSchemas dbSchemas();

        Optional<DbSchemas> optionalDbSchemas();

        Set<MigrationDefinition> migrationDefinitions();

        Optional<Pool> optionalPool();

        @Component.Factory
        interface Factory {
            MigrationComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Runtime-only graph: the PostgreSQL runtime and the shipped inbox/outbox adapter module, deliberately
     * without the Flyway module. The placement binding reaches the adapter only through the adapter module's
     * own includes.
     */
    @Singleton
    @Component(
            modules = {
                DbModule.class,
                DbPostgresqlModule.class,
                TransactionalMessagingPostgresqlModule.class,
                ConfigParsingModule.class,
                AbsentFlywayProbeModule.class
            })
    interface RuntimeComponent {

        Pool pool();

        PgConnectOptions pgConnectOptions();

        DbSchemas dbSchemas();

        Optional<DbSchemas> optionalDbSchemas();

        PgInboxOutboxRepository pgInboxOutboxRepository();

        InboxRepository inboxRepository();

        OutboxRepository outboxRepository();

        InboxService inboxService();

        OutboxService outboxService();

        OutboxMaintenanceService outboxMaintenanceService();

        Optional<FlywayConfig> optionalFlywayConfig();

        Optional<MigrationRunner> optionalMigrationRunner();

        @Component.Factory
        interface Factory {
            RuntimeComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** Pool-plus-mapper graph with no DbModule and no placement binding at all. */
    @Singleton
    @Component(modules = {TransactionalMessagingPostgresqlModule.class, CompatibilityMapperModule.class})
    interface CompatibilityComponent {

        Optional<DbSchemas> optionalDbSchemas();

        PgInboxOutboxRepository pgInboxOutboxRepository();

        OutboxRepository outboxRepository();

        @Component.Factory
        interface Factory {
            CompatibilityComponent create(@BindsInstance Pool pool);
        }
    }

    @Module
    abstract static class AbsentPoolProbeModule {

        @BindsOptionalOf
        abstract Pool pool();
    }

    @Module
    abstract static class AbsentFlywayProbeModule {

        @BindsOptionalOf
        abstract FlywayConfig flywayConfig();

        @BindsOptionalOf
        abstract MigrationRunner migrationRunner();
    }

    @Module
    abstract static class CompatibilityMapperModule {

        @Provides
        @Singleton
        static PgDbExceptionMapper pgDbExceptionMapper() {
            return new PgDbExceptionMapper();
        }
    }

    // --- Graph assertions ---

    private static void assertMigrationGraphHasNoPool(MigrationComponent migration) {
        Set<Class<?>> modules = declaredModules(MigrationComponent.class);

        assertTrue(modules.contains(DbFlywayModule.class), "migration graph must declare the Flyway module");
        assertTrue(
                modules.contains(InboxOutboxMigrationsModule.class),
                "migration graph must declare the inbox/outbox migration declaration");
        assertFalse(modules.contains(DbPostgresqlModule.class), "migration graph must not declare the runtime module");
        assertFalse(
                modules.contains(TransactionalMessagingPostgresqlModule.class),
                "migration graph must not declare the adapter runtime module");
        assertTrue(migration.optionalPool().isEmpty(), "migration graph must not bind a Pool");
    }

    private static void assertRuntimeGraphHasNoFlyway(RuntimeComponent runtime) {
        Set<Class<?>> modules = declaredModules(RuntimeComponent.class);

        assertTrue(modules.contains(TransactionalMessagingPostgresqlModule.class));
        assertTrue(
                modules.contains(InboxOutboxMigrationsModule.class),
                "the adapter module must include its migration declaration: " + modules);
        assertTrue(
                modules.contains(DbSchemasModule.class),
                "the adapter module must include the optional placement declaration: " + modules);
        assertTrue(
                modules.stream().noneMatch(module -> module.getName().startsWith("dev.vertique.db.flyway.")),
                "runtime graph must declare no module from the Flyway package, directly or through includes: "
                        + modules);
        assertTrue(runtime.optionalFlywayConfig().isEmpty(), "runtime graph must have no FlywayConfig binding");
        assertTrue(runtime.optionalMigrationRunner().isEmpty(), "runtime graph must have no MigrationRunner binding");
    }

    private static void assertPlacementEqualsConfiguration(MigrationComponent migration, RuntimeComponent runtime) {
        Map<String, String> configured = Map.of(INSTANCE, MAPPED_SCHEMA);

        assertEquals(configured, runtime.dbSchemas().mappings());
        assertEquals(configured, migration.dbSchemas().mappings());
        assertEquals(Optional.of(MAPPED_SCHEMA), runtime.dbSchemas().schema(INSTANCE));
        assertTrue(runtime.optionalDbSchemas().isPresent(), "the runtime graph must offer the placement binding");
        assertEquals(configured, runtime.optionalDbSchemas().orElseThrow().mappings());
        assertEquals(configured, migration.optionalDbSchemas().orElseThrow().mappings());
    }

    private static void assertShippedDeclarationIsTheOnlyOne(MigrationComponent migration) {
        assertEquals(
                Set.of(new MigrationDefinition(INSTANCE, List.of(LOCATION), List.of())),
                migration.migrationDefinitions(),
                "the shipped declaration contributes exactly the inbox/outbox migration location");
    }

    private static void assertConnectionFreeInstanceView(FlywayMigrationRunner runner) {
        List<MigrationInstanceView> views = runner.instances();

        assertEquals(
                List.of(INSTANCE),
                views.stream().map(MigrationInstanceView::name).toList());
        MigrationInstanceView view = views.get(0);
        assertEquals(MAPPED_SCHEMA, view.schema(), "default and history schema");
        assertEquals(List.of(MAPPED_SCHEMA), view.schemas(), "managed schemas");
        assertEquals(HISTORY, view.table(), "derived history table");
        assertEquals(List.of(LOCATION), view.locations());
        assertEquals(FlywayMode.MIGRATE, view.mode());
    }

    private static Set<Class<?>> declaredModules(Class<?> component) {
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(
                List.of(component.getAnnotation(Component.class).modules()));
        while (!pending.isEmpty()) {
            Class<?> module = pending.pop();
            if (seen.add(module)) {
                Module annotation = module.getAnnotation(Module.class);
                if (annotation != null) {
                    pending.addAll(List.of(annotation.includes()));
                }
            }
        }
        return seen;
    }

    // --- Migration and catalog assertions ---

    private static void assertInboxOutboxMigrated(MigrationReport report, int expectedApplied) {
        List<MigrationInstanceResult> results = report.instances();

        assertEquals(
                List.of(INSTANCE),
                results.stream().map(result -> result.instance().name()).toList());
        MigrationInstanceResult result = results.get(0);
        assertEquals(MigrationStatus.MIGRATED, result.status());
        assertEquals(expectedApplied, result.migrationsApplied(), "applied scripts");
        assertEquals("002", result.currentVersion());
        assertEquals(MAPPED_SCHEMA, result.instance().schema(), "resolved history schema");
        assertEquals(HISTORY, result.instance().table(), "resolved history table");
    }

    private static void assertObjectsLiveOnlyInMappedSchema() throws SQLException {
        // The mapped schema holds the history, both tables, the sequence, the indexes, the notify function and
        // the trigger that calls it
        assertEquals(with(MESSAGING_TABLES, HISTORY), tablesIn(MAPPED_SCHEMA));
        assertEquals(Set.of(OUTBOX_SEQUENCE), sequencesIn(MAPPED_SCHEMA));
        assertTrue(
                indexesIn(MAPPED_SCHEMA).containsAll(MIGRATED_INDEXES),
                "migrated indexes in the mapped schema: " + indexesIn(MAPPED_SCHEMA));
        assertEquals(Set.of(NOTIFY_FUNCTION), functionsIn(MAPPED_SCHEMA));
        assertEquals(
                Set.of(NOTIFY_TRIGGER + " on " + MAPPED_SCHEMA + ".outbox calls " + MAPPED_SCHEMA + "."
                        + NOTIFY_FUNCTION),
                triggersIn(MAPPED_SCHEMA));

        // No other schema received any of them
        assertEquals(Set.of(MAPPED_SCHEMA), schemasHoldingTable(HISTORY));
        assertEquals(Set.of(MAPPED_SCHEMA, DECOY_SCHEMA), schemasHoldingTable("outbox"));
        assertEquals(Set.of(MAPPED_SCHEMA, DECOY_SCHEMA), schemasHoldingTable("inbox"));
        assertEquals(
                Set.of(MAPPED_SCHEMA),
                Set.copyOf(column(
                        "SELECT sequence_schema FROM information_schema.sequences WHERE sequence_name = ?",
                        OUTBOX_SEQUENCE)));
        assertEquals(
                Set.of(MAPPED_SCHEMA),
                Set.copyOf(column(
                        "SELECT n.nspname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                                + " WHERE p.proname = ?",
                        NOTIFY_FUNCTION)));
        assertEquals(
                Set.of(MAPPED_SCHEMA),
                Set.copyOf(column(
                        "SELECT table_schema FROM information_schema.columns"
                                + " WHERE table_name = 'outbox' AND column_name = ?",
                        SCOPE_COLUMN)));
    }

    private static List<String> versionedHistory() throws SQLException {
        return column("SELECT version FROM " + MAPPED_SCHEMA + "." + HISTORY
                + " WHERE version IS NOT NULL AND success ORDER BY installed_rank");
    }

    // --- Scoped identity ---

    private static void assertScopedRepositoryIdentity(RuntimeComponent runtime) {
        PgInboxOutboxRepository concrete = runtime.pgInboxOutboxRepository();

        assertSame(concrete, runtime.pgInboxOutboxRepository(), "repeated concrete lookups share one scoped instance");
        assertSame(concrete, runtime.inboxRepository(), "the inbox SPI aliases the scoped concrete repository");
        assertSame(concrete, runtime.outboxRepository(), "the outbox SPI aliases the scoped concrete repository");
        assertSame(runtime.inboxRepository(), runtime.inboxRepository());
        assertSame(runtime.outboxRepository(), runtime.outboxRepository());
        assertEquals(
                concrete.nodeId(),
                assertInstanceOf(PgInboxOutboxRepository.class, runtime.outboxRepository())
                        .nodeId(),
                "every lookup carries one claim identity");
        assertEquals(
                concrete.nodeId(),
                assertInstanceOf(PgInboxOutboxRepository.class, runtime.inboxRepository())
                        .nodeId());
        assertSame(runtime.inboxService(), runtime.inboxService(), "the inbox service is one singleton");
        assertSame(runtime.outboxService(), runtime.outboxService(), "the outbox service is one singleton");
        assertSame(
                runtime.outboxMaintenanceService(),
                runtime.outboxMaintenanceService(),
                "the maintenance service is one singleton");
    }

    // --- Outbox, inbox, maintenance and relay operations ---

    /** Ids of the lifecycle entries that later retention steps remove. */
    private record SettledEntries(long published, long deadLettered) {}

    private static SettledEntries exerciseOutboxLifecycle(RuntimeComponent runtime) throws Exception {
        PgInboxOutboxRepository repository = runtime.pgInboxOutboxRepository();
        OutboxRepository outbox = runtime.outboxRepository();
        OutboxService service = runtime.outboxService();
        Pool pool = runtime.pool();
        String nodeId = repository.nodeId();
        String target = unique("lifecycle");

        // insert in the caller's transaction, directly and through the publishing service; one entry belongs
        // to an aggregate so the head-of-line predicate selects a real row
        UUID carrier = UUID.randomUUID();
        long toPublish = await(pool.withTransaction(
                tx -> outbox.insert(serviceEntry(target, unique("aggregate")), OutboxMetadata.empty(), carrier, tx)));
        long toRetry = await(pool.withTransaction(tx -> service.publish(tx, serviceEntry(target, null))));
        long toDeadLetter = await(pool.withTransaction(
                tx -> outbox.insert(serviceEntry(target, null), OutboxMetadata.empty(), UUID.randomUUID(), tx)));
        long toUnresolvable = await(pool.withTransaction(tx -> service.publish(tx, kafkaEntry(unique("topic")))));
        assertEquals(carrier.toString(), mappedOutbox(toPublish, "carrier_id"));
        for (long id : List.of(toPublish, toRetry, toDeadLetter, toUnresolvable)) {
            assertEquals("PENDING", mappedOutbox(id, "state"));
        }

        // claimBatch in its own transaction, through both the scoped-target and the all-targets predicate
        RelayCapabilities capabilities = new RelayCapabilities(Map.of(
                DestinationType.SERVICE, ClaimScope.destinations(() -> Set.of(target)),
                DestinationType.KAFKA, ClaimScope.all()));
        List<OutboxRecord> claimed = await(outbox.claimBatch(10, nodeId, capabilities));
        assertEquals(Set.of(toPublish, toRetry, toDeadLetter, toUnresolvable), ids(claimed));
        for (OutboxRecord record : claimed) {
            assertEquals(OutboxEntryState.PROCESSING, record.state());
            assertEquals(nodeId, record.claimedBy());
            assertEquals("PROCESSING", mappedOutbox(record.id(), "state"));
            assertEquals(nodeId, mappedOutbox(record.id(), "claimed_by"));
        }
        assertEquals(
                carrier,
                claimed.stream()
                        .filter(record -> record.id() == toPublish)
                        .findFirst()
                        .orElseThrow()
                        .carrierId());

        // markPublished is guarded by the claim identity
        assertFalse(await(repository.markPublished(toPublish, "another-node")), "only the claiming node may publish");
        assertTrue(await(repository.markPublished(toPublish, nodeId)));
        assertEquals("PUBLISHED", mappedOutbox(toPublish, "state"));
        assertEquals("true", mappedOutbox(toPublish, "published_at IS NOT NULL"));

        // markRetry releases the claim and schedules the next attempt
        Instant retryAt = Instant.now().plus(Duration.ofHours(1));
        assertTrue(await(outbox.markRetry(toRetry, nodeId, 1, retryAt, "transient failure", "java.io.IOException")));
        assertEquals("PENDING", mappedOutbox(toRetry, "state"));
        assertEquals("1", mappedOutbox(toRetry, "attempt"));
        assertEquals("transient failure", mappedOutbox(toRetry, "last_error"));
        assertNull(mappedOutbox(toRetry, "claimed_by"));

        // markDeadLetter keeps the failure
        assertTrue(await(
                outbox.markDeadLetter(toDeadLetter, nodeId, "permanent failure", "java.lang.IllegalStateException")));
        assertEquals("DEAD_LETTER", mappedOutbox(toDeadLetter, "state"));
        assertEquals("permanent failure", mappedOutbox(toDeadLetter, "last_error"));

        // markUnresolvable releases the claim and delays the next claim
        assertTrue(await(outbox.markUnresolvable(toUnresolvable, nodeId, Duration.ofMinutes(5))));
        assertEquals("PENDING", mappedOutbox(toUnresolvable, "state"));
        assertNull(mappedOutbox(toUnresolvable, "claimed_by"));
        assertEquals("true", mappedOutbox(toUnresolvable, "available_at > NOW() + INTERVAL '4 minutes'"));

        return new SettledEntries(toPublish, toDeadLetter);
    }

    private static void exerciseRepositoryRetentionCleanup(RuntimeComponent runtime, SettledEntries settled)
            throws Exception {
        OutboxRepository outbox = runtime.outboxRepository();

        // Given: exactly one published and one dead-letter entry older than their retention
        backdateOutbox(settled.published(), "published_at", "8 days");
        backdateOutbox(settled.deadLettered(), "updated_at", "31 days");

        // When / Then: each batched cleanup removes exactly that entry from the mapped table
        assertEquals(1, await(outbox.cleanupPublished(7, 100)));
        assertEquals(0, mappedOutboxRows(settled.published()));
        assertEquals(1, await(outbox.cleanupDeadLetter(30, 100)));
        assertEquals(0, mappedOutboxRows(settled.deadLettered()));
    }

    private static void exerciseStaleLeaseRecovery(RuntimeComponent runtime) throws Exception {
        OutboxRepository outbox = runtime.outboxRepository();
        String nodeId = runtime.pgInboxOutboxRepository().nodeId();
        String target = unique("stale");
        long first = insertServiceEntry(runtime, target);
        long second = insertServiceEntry(runtime, target);
        assertEquals(Set.of(first, second), ids(await(outbox.claimBatch(10, nodeId, serviceScope(target)))));

        // When: only the first lease is older than the timeout handed to the repository
        backdateOutbox(first, "claimed_at", "2 minutes");
        await(outbox.reclaimStale(Duration.ofMinutes(1)));

        // Then: only that entry is released
        assertReleased(first);
        assertEquals("PROCESSING", mappedOutbox(second, "state"));
        assertEquals(nodeId, mappedOutbox(second, "claimed_by"));

        // When: the second lease is older than the default lease timeout and maintenance recovers leases
        backdateOutbox(second, "claimed_at", "10 minutes");
        await(runtime.outboxMaintenanceService().recoverStaleLeases());

        // Then: it is released as well
        assertReleased(second);
    }

    private static void exerciseInboxOperations(RuntimeComponent runtime) throws Exception {
        InboxRepository inbox = runtime.inboxRepository();
        InboxService service = runtime.inboxService();
        Pool pool = runtime.pool();
        String expired = unique("inbox-expired");
        String retained = unique("inbox-retained");

        // tryInsert in the caller's transaction deduplicates
        assertTrue(await(pool.<Boolean>withTransaction(tx -> inbox.tryInsert(expired, SOURCE, tx))));
        assertFalse(
                await(pool.<Boolean>withTransaction(tx -> inbox.tryInsert(expired, SOURCE, tx))),
                "a repeated message is a duplicate");

        // processOnce runs the work once and reports the repeat as a duplicate
        InboxResult<String> processed = await(pool.<InboxResult<String>>withTransaction(
                tx -> service.processOnce(retained, SOURCE, tx, () -> Future.succeededFuture("handled"))));
        InboxResult<String> repeated = await(pool.<InboxResult<String>>withTransaction(
                tx -> service.processOnce(retained, SOURCE, tx, () -> Future.succeededFuture("handled again"))));
        assertEquals(new InboxResult.Processed<>("handled"), processed);
        assertInstanceOf(InboxResult.Duplicate.class, repeated);
        assertEquals(1, mappedInboxRows(expired));
        assertEquals(1, mappedInboxRows(retained));

        // the batched cleanup removes exactly the expired record
        backdateInbox(expired, "31 days");
        assertEquals(1, await(inbox.cleanup(30, 100)));
        assertEquals(0, mappedInboxRows(expired));
        assertEquals(1, mappedInboxRows(retained));
    }

    private static void exerciseMaintenanceCleanup(RuntimeComponent runtime) throws Exception {
        OutboxRepository outbox = runtime.outboxRepository();
        InboxRepository inbox = runtime.inboxRepository();
        Pool pool = runtime.pool();
        String nodeId = runtime.pgInboxOutboxRepository().nodeId();
        String target = unique("maintenance");

        // Given: an expired published entry, an expired dead-letter entry and an expired inbox record, plus a
        // recent counterpart of each that the default retention keeps
        long expiredPublished = insertServiceEntry(runtime, target);
        long expiredDeadLetter = insertServiceEntry(runtime, target);
        long recentPublished = insertServiceEntry(runtime, target);
        assertEquals(
                Set.of(expiredPublished, expiredDeadLetter, recentPublished),
                ids(await(outbox.claimBatch(10, nodeId, serviceScope(target)))));
        assertTrue(await(outbox.markPublished(expiredPublished, nodeId)));
        assertTrue(await(outbox.markDeadLetter(expiredDeadLetter, nodeId, "permanent failure", "java.lang.Error")));
        assertTrue(await(outbox.markPublished(recentPublished, nodeId)));
        String expiredMessage = unique("maintenance-expired");
        String recentMessage = unique("maintenance-recent");
        assertTrue(await(pool.<Boolean>withTransaction(tx -> inbox.tryInsert(expiredMessage, SOURCE, tx))));
        assertTrue(await(pool.<Boolean>withTransaction(tx -> inbox.tryInsert(recentMessage, SOURCE, tx))));
        backdateOutbox(expiredPublished, "published_at", "8 days");
        backdateOutbox(expiredDeadLetter, "updated_at", "31 days");
        backdateInbox(expiredMessage, "31 days");

        // When: the maintenance service runs its cleanup
        await(runtime.outboxMaintenanceService().cleanup());

        // Then: only the expired rows are gone from the mapped tables
        assertEquals(0, mappedOutboxRows(expiredPublished));
        assertEquals(0, mappedOutboxRows(expiredDeadLetter));
        assertEquals(1, mappedOutboxRows(recentPublished));
        assertEquals(0, mappedInboxRows(expiredMessage));
        assertEquals(1, mappedInboxRows(recentMessage));
    }

    private static void exerciseRelay(Vertx vertx, RuntimeComponent runtime) throws Exception {
        // Given: a relay built from the component's outbox repository, claiming only its own target
        String target = unique("relay");
        Promise<OutboxEnvelope> delivered = Promise.promise();
        Map<DestinationType, OutboxDestinationHandler> handlers =
                OutboxRelay.buildHandlerMap(Set.of(new ScopedServiceHandler(target, delivered)));
        String relayNodeId = runtime.pgInboxOutboxRepository().nodeId() + "-0";
        OutboxRelay relay = new OutboxRelay(
                relayConfig(),
                runtime.outboxRepository(),
                handlers,
                OutboxRelay.deriveCapabilities(handlers),
                runtime.pgConnectOptions(),
                relayNodeId);
        long entryId = await(
                runtime.pool().withTransaction(tx -> runtime.outboxService().publish(tx, serviceEntry(target, null))));

        // When: the relay is deployed
        String deploymentId = await(vertx.deployVerticle(relay));
        try {
            // Then: it claims, delivers and publishes the mapped entry under its own claim identity
            assertEquals(entryId, await(delivered.future()).entryId());
            awaitMappedOutboxState(entryId, "PUBLISHED");
            assertEquals(relayNodeId, mappedOutbox(entryId, "claimed_by"));
        } finally {
            await(vertx.undeploy(deploymentId));
        }
    }

    // --- Shared transaction ---

    private static void assertApplicationWorkRollsBackWithMessaging(RuntimeComponent runtime) throws Exception {
        // Given: an empty application table and identifiers unique to this unit of work
        assertEquals(List.of("0"), column("SELECT count(*) FROM " + APP_TABLE));
        UUID orderId = UUID.randomUUID();
        String destination = unique("rolled-back");
        String messageId = unique("rolled-back-message");
        IllegalStateException forced =
                new IllegalStateException("forced failure after the application and messaging writes");
        AtomicReference<List<Long>> visibleInside = new AtomicReference<>();

        // When: all three writes succeed inside one caller transaction that is then failed
        Throwable failure = failureOf(runtime.pool()
                .withTransaction(tx -> applicationAndMessagingWork(runtime, tx, orderId, destination, messageId)
                        .compose(visible -> {
                            visibleInside.set(visible);
                            return Future.<Void>failedFuture(forced);
                        })));

        // Then: the forced failure, not a statement error, ended the transaction, and nothing survived it
        assertSame(forced, failure, "the transaction must end with the forced failure");
        assertEquals(List.of(1L, 1L, 1L), visibleInside.get(), "all three writes were visible inside the transaction");
        assertEquals(List.of("0"), column("SELECT count(*) FROM " + APP_TABLE));
        assertEquals(0, mappedOutboxRowsTo(destination));
        assertEquals(0, mappedInboxRows(messageId));
    }

    private static void assertApplicationWorkCommitsWithMessaging(RuntimeComponent runtime) throws Exception {
        UUID orderId = UUID.randomUUID();
        String destination = unique("committed");
        String messageId = unique("committed-message");

        // When: the same unit of work commits
        List<Long> visible = await(runtime.pool()
                .withTransaction(tx -> applicationAndMessagingWork(runtime, tx, orderId, destination, messageId)));

        // Then: all three writes are kept together
        assertEquals(List.of(1L, 1L, 1L), visible);
        assertEquals(List.of("1"), column("SELECT count(*) FROM " + APP_TABLE));
        assertEquals(List.of("1"), column("SELECT count(*) FROM " + APP_TABLE + " WHERE id::text = ?", orderId));
        assertEquals(1, mappedOutboxRowsTo(destination));
        assertEquals(1, mappedInboxRows(messageId));
    }

    /**
     * One unit of caller work on the caller's connection: an application row, an outbox publish and a
     * first inbox delivery; completes with how many of the three rows the transaction itself sees.
     */
    private static Future<List<Long>> applicationAndMessagingWork(
            RuntimeComponent runtime, SqlConnection tx, UUID orderId, String destination, String messageId) {
        return tx.preparedQuery("INSERT INTO " + APP_TABLE + " (id, label) VALUES ($1, $2)")
                .execute(Tuple.of(orderId, "order " + orderId))
                .compose(inserted -> runtime.outboxService().publish(tx, serviceEntry(destination, null)))
                .compose(entryId -> runtime.inboxService()
                        .processOnce(messageId, SOURCE, tx, () -> Future.succeededFuture(entryId)))
                .compose(result -> {
                    if (!(result instanceof InboxResult.Processed<?>)) {
                        return Future.failedFuture(new AssertionError("first delivery must be processed: " + result));
                    }
                    return tx.preparedQuery("SELECT (SELECT count(*) FROM " + APP_TABLE + " WHERE id = $1),"
                                    + " (SELECT count(*) FROM " + MAPPED_SCHEMA + ".outbox WHERE destination = $2),"
                                    + " (SELECT count(*) FROM " + MAPPED_SCHEMA + ".inbox WHERE message_id = $3)")
                            .execute(Tuple.of(orderId, destination, messageId))
                            .map(rows -> {
                                Row row = rows.iterator().next();
                                return List.of(row.getLong(0), row.getLong(1), row.getLong(2));
                            });
                });
    }

    // --- Compatibility without placement ---

    private static void assertCompatibilityGraphKeepsUnqualifiedStatements(Vertx vertx, RuntimeComponent runtime)
            throws Exception {
        Pool mappedDefault = poolWithDefaultSchema(vertx, MAPPED_SCHEMA);
        try {
            // Given: connections whose default schema is the mapped schema, and a graph without placement
            assertEquals(
                    MAPPED_SCHEMA,
                    await(mappedDefault.query("SHOW search_path").execute())
                            .iterator()
                            .next()
                            .getString(0));
            CompatibilityComponent compatibility =
                    DaggerPgInboxOutboxSchemaIT_CompatibilityComponent.factory().create(mappedDefault);
            PgInboxOutboxRepository legacy = compatibility.pgInboxOutboxRepository();
            assertTrue(compatibility.optionalDbSchemas().isEmpty(), "a graph without DbModule has no placement");
            assertSame(legacy, compatibility.pgInboxOutboxRepository());
            assertSame(legacy, compatibility.outboxRepository());

            // When: the unqualified statements run on those connections
            String target = unique("legacy");
            long entryId = await(mappedDefault.withTransaction(
                    tx -> legacy.insert(serviceEntry(target, null), OutboxMetadata.empty(), UUID.randomUUID(), tx)));
            assertEquals(Set.of(entryId), ids(await(legacy.claimBatch(10, legacy.nodeId(), serviceScope(target)))));
            assertTrue(await(legacy.markPublished(entryId, legacy.nodeId())));

            // Then: they resolved through the connection's default schema to the mapped objects
            assertEquals("PUBLISHED", mappedOutbox(entryId, "state"));
            assertEquals(legacy.nodeId(), mappedOutbox(entryId, "claimed_by"));

            // When: the same unqualified insert runs on connections whose default is the decoy schema
            PgInboxOutboxRepository decoyDefault = DaggerPgInboxOutboxSchemaIT_CompatibilityComponent.factory()
                    .create(runtime.pool())
                    .pgInboxOutboxRepository();
            String decoyTarget = unique("legacy-decoy");
            Throwable failure = failureOf(runtime.pool()
                    .withTransaction(tx -> decoyDefault.insert(
                            serviceEntry(decoyTarget, null), OutboxMetadata.empty(), UUID.randomUUID(), tx)));

            // Then: it reached the decoy's incompatible table and failed, and nothing reached the mapped table
            assertEquals(
                    UNDEFINED_COLUMN,
                    assertInstanceOf(DataAccessException.class, failure).sqlState());
            assertEquals(0, mappedOutboxRowsTo(decoyTarget));
        } finally {
            await(mappedDefault.close());
        }
    }

    // --- Decoy default schema and application table ---

    private static void createDecoyDefaultSchema() throws SQLException {
        for (String table : MESSAGING_TABLES) {
            execute("CREATE TABLE " + DECOY_SCHEMA + "." + table
                    + " (decoy_key UUID PRIMARY KEY, sentinel TEXT NOT NULL)");
        }
        execute("INSERT INTO " + DECOY_SCHEMA + ".outbox VALUES (gen_random_uuid(), '" + DECOY_OUTBOX_SENTINEL + "')");
        execute("INSERT INTO " + DECOY_SCHEMA + ".inbox VALUES (gen_random_uuid(), '" + DECOY_INBOX_SENTINEL + "')");

        assertEquals(MESSAGING_TABLES, tablesIn(DECOY_SCHEMA), "the decoy carries no migration history");
        assertEquals(
                List.of("0"),
                column("SELECT count(*) FROM pg_namespace WHERE nspname = ?", MAPPED_SCHEMA),
                "the mapped schema does not exist before the first migration");
    }

    private static void createApplicationTable() throws SQLException {
        execute("CREATE SCHEMA " + APP_SCHEMA);
        execute("CREATE TABLE " + APP_TABLE + " (id UUID PRIMARY KEY, label TEXT NOT NULL)");
    }

    private static void assertDecoyDefaultSchemaUntouched() throws SQLException {
        assertEquals(MESSAGING_TABLES, tablesIn(DECOY_SCHEMA), "the decoy keeps exactly its own tables");
        for (String table : MESSAGING_TABLES) {
            assertEquals(
                    DECOY_COLUMNS,
                    column(
                            "SELECT column_name FROM information_schema.columns WHERE table_schema = ?"
                                    + " AND table_name = ? ORDER BY ordinal_position",
                            DECOY_SCHEMA,
                            table),
                    "decoy table keeps its original shape: " + table);
        }
        assertEquals(List.of(DECOY_OUTBOX_SENTINEL), column("SELECT sentinel FROM " + DECOY_SCHEMA + ".outbox"));
        assertEquals(List.of(DECOY_INBOX_SENTINEL), column("SELECT sentinel FROM " + DECOY_SCHEMA + ".inbox"));
        assertEquals(DECOY_INDEXES, indexesIn(DECOY_SCHEMA), "no migrated index may appear in the decoy schema");
        assertTrue(sequencesIn(DECOY_SCHEMA).isEmpty(), "no sequence may appear in the decoy schema");
        assertFalse(functionsIn(DECOY_SCHEMA).contains(NOTIFY_FUNCTION), "no notify function in the decoy schema");
        assertTrue(triggersIn(DECOY_SCHEMA).isEmpty(), "no trigger may appear in the decoy schema");
    }

    // --- Catalog queries ---

    private static Set<String> tablesIn(String schema) throws SQLException {
        return Set.copyOf(column("SELECT table_name FROM information_schema.tables WHERE table_schema = ?", schema));
    }

    private static Set<String> schemasHoldingTable(String table) throws SQLException {
        return Set.copyOf(column("SELECT table_schema FROM information_schema.tables WHERE table_name = ?", table));
    }

    private static Set<String> sequencesIn(String schema) throws SQLException {
        return Set.copyOf(
                column("SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = ?", schema));
    }

    private static Set<String> indexesIn(String schema) throws SQLException {
        return Set.copyOf(column("SELECT indexname FROM pg_indexes WHERE schemaname = ?", schema));
    }

    private static Set<String> functionsIn(String schema) throws SQLException {
        return Set.copyOf(column(
                "SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = ?",
                schema));
    }

    /** Non-internal triggers of the schema's tables, rendered as "trigger on schema.table calls schema.function". */
    private static Set<String> triggersIn(String schema) throws SQLException {
        return Set.copyOf(column(
                "SELECT t.tgname || ' on ' || tn.nspname || '.' || c.relname"
                        + " || ' calls ' || pn.nspname || '.' || p.proname"
                        + " FROM pg_trigger t"
                        + " JOIN pg_class c ON c.oid = t.tgrelid"
                        + " JOIN pg_namespace tn ON tn.oid = c.relnamespace"
                        + " JOIN pg_proc p ON p.oid = t.tgfoid"
                        + " JOIN pg_namespace pn ON pn.oid = p.pronamespace"
                        + " WHERE tn.nspname = ? AND NOT t.tgisinternal",
                schema));
    }

    // --- Mapped row access ---

    /** Reads one expression of one mapped outbox row as text; fails unless exactly that row exists. */
    private static String mappedOutbox(long id, String expression) throws SQLException {
        List<String> values =
                column("SELECT (" + expression + ")::text FROM " + MAPPED_SCHEMA + ".outbox WHERE id = " + id);
        assertEquals(1, values.size(), "exactly one mapped outbox row with id " + id);
        return values.get(0);
    }

    private static int mappedOutboxRows(long id) throws SQLException {
        return Integer.parseInt(column("SELECT count(*) FROM " + MAPPED_SCHEMA + ".outbox WHERE id = " + id)
                .get(0));
    }

    private static int mappedOutboxRowsTo(String destination) throws SQLException {
        return Integer.parseInt(
                column("SELECT count(*) FROM " + MAPPED_SCHEMA + ".outbox WHERE destination = ?", destination)
                        .get(0));
    }

    private static int mappedInboxRows(String messageId) throws SQLException {
        return Integer.parseInt(column(
                        "SELECT count(*) FROM " + MAPPED_SCHEMA + ".inbox WHERE message_id = ? AND source = ?",
                        messageId,
                        SOURCE)
                .get(0));
    }

    private static void assertReleased(long id) throws SQLException {
        assertEquals("PENDING", mappedOutbox(id, "state"));
        assertNull(mappedOutbox(id, "claimed_by"));
        assertNull(mappedOutbox(id, "claimed_at"));
    }

    private static void backdateOutbox(long id, String column, String age) throws SQLException {
        execute("UPDATE " + MAPPED_SCHEMA + ".outbox SET " + column + " = NOW() - INTERVAL '" + age + "' WHERE id = "
                + id);
    }

    private static void backdateInbox(String messageId, String age) throws SQLException {
        try (Connection connection = jdbc();
                PreparedStatement statement = connection.prepareStatement("UPDATE " + MAPPED_SCHEMA
                        + ".inbox SET processed_at = NOW() - INTERVAL '" + age + "' WHERE message_id = ?")) {
            statement.setString(1, messageId);
            assertEquals(1, statement.executeUpdate(), "backdated inbox record " + messageId);
        }
    }

    /** Polls the mapped row until the relay records the state; the relay writes it after delivery completes. */
    private static void awaitMappedOutboxState(long id, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!state.equals(mappedOutbox(id, "state"))) {
            if (System.nanoTime() > deadline) {
                assertEquals(state, mappedOutbox(id, "state"), "relay outcome for entry " + id);
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
    }

    // --- Fixtures ---

    private static JsonObject runtimeConfig() {
        DbPoolConfig pool = db.toPoolConfig();
        return new JsonObject()
                .put(
                        "db",
                        new JsonObject()
                                .put("host", pool.host())
                                .put("port", pool.port())
                                .put("database", pool.database())
                                .put("user", pool.user())
                                .put("password", pool.password())
                                .put("schemas", new JsonObject().put(INSTANCE, MAPPED_SCHEMA)));
    }

    private static JsonObject migrationConfig() {
        return runtimeConfig()
                .put("flyway", new JsonObject().put("mode", "MIGRATE").put("named", true));
    }

    private static Pool poolWithDefaultSchema(Vertx vertx, String schema) {
        DbPoolConfig config = db.toPoolConfig();
        PgConnectOptions options = new PgConnectOptions()
                .setHost(config.host())
                .setPort(config.port())
                .setDatabase(config.database())
                .setUser(config.user())
                .setPassword(config.password());
        options.addProperty("search_path", schema);
        return PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(2))
                .connectingTo(options)
                .using(vertx)
                .build();
    }

    private static OutboxRelayConfig relayConfig() {
        return OutboxRelayConfig.builder()
                .pollingIntervalMs(100L)
                .batchSize(10)
                .strategy(RelayStrategy.LISTEN_NOTIFY)
                .build();
    }

    private static OutboxEntry serviceEntry(String target, String aggregateId) {
        return OutboxEntry.builder()
                .destinationType(DestinationType.SERVICE)
                .destination(target)
                .aggregateType(aggregateId == null ? null : "order")
                .aggregateId(aggregateId)
                .eventType("schema.placement.test")
                .payload(new JsonObject().put("target", target))
                .build();
    }

    private static OutboxEntry kafkaEntry(String topic) {
        return OutboxEntry.builder()
                .destinationType(DestinationType.KAFKA)
                .destination(topic)
                .eventType("schema.placement.test")
                .payload(new JsonObject().put("topic", topic))
                .build();
    }

    private static long insertServiceEntry(RuntimeComponent runtime, String target) throws Exception {
        return await(runtime.pool().withTransaction(tx -> runtime.outboxRepository()
                .insert(serviceEntry(target, null), OutboxMetadata.empty(), UUID.randomUUID(), tx)));
    }

    private static RelayCapabilities serviceScope(String target) {
        return new RelayCapabilities(Map.of(DestinationType.SERVICE, ClaimScope.destinations(() -> Set.of(target))));
    }

    private static Set<Long> ids(List<OutboxRecord> records) {
        return records.stream().map(OutboxRecord::id).collect(Collectors.toSet());
    }

    private static Set<String> with(Set<String> values, String extra) {
        Set<String> combined = new HashSet<>(values);
        combined.add(extra);
        return combined;
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** SERVICE handler that claims a single target and reports the first delivered envelope. */
    static final class ScopedServiceHandler implements OutboxDestinationHandler {

        private final String target;
        private final Promise<OutboxEnvelope> delivered;

        ScopedServiceHandler(String target, Promise<OutboxEnvelope> delivered) {
            this.target = target;
            this.delivered = delivered;
        }

        @Override
        public DestinationType destinationType() {
            return DestinationType.SERVICE;
        }

        @Override
        public ClaimScope claimScope() {
            return ClaimScope.destinations(() -> Set.of(target));
        }

        @Override
        public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
            delivered.tryComplete(envelope);
            return Future.succeededFuture(OutboxPublishResult.success());
        }
    }

    // --- Native database access ---

    private static Connection jdbc() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Runs a query and returns its first column rendered as text, one entry per row. */
    private static List<String> column(String sql, Object... parameters) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = jdbc();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, String.valueOf(parameters[i]));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(Future<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(future));
        return failure.getCause();
    }
}
