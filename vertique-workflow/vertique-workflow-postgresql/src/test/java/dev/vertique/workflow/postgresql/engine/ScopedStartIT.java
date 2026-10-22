// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.ACME;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.DEFAULT;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.GLOBEX;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.ID;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.PURCHASE_CONTRACT;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.await;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.failureOf;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.instanceRows;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.startDedupRows;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.startDedupRowsOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.exception.UniqueConstraintViolationException;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.client.WorkflowClientBuilder;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.exception.WorkflowBusinessKeyConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.engine.ScopedStartFixture.Harness;
import dev.vertique.workflow.postgresql.engine.ScopedStartFixture.OtherContract;
import dev.vertique.workflow.postgresql.engine.ScopedStartFixture.Purchase;
import dev.vertique.workflow.postgresql.engine.ScopedStartFixture.PurchaseContract;
import dev.vertique.workflow.postgresql.engine.ScopedStartFixture.StartPurchase;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Real PostgreSQL proof that a start runs in a start scope: it stamps the scope on the instance, scopes business
 * keys and start idempotency keys by it, resolves explicit versions against it, and refuses what its scope
 * cannot start before anything is written.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ScopedStartIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_scoped_start_test")
            .withMigration("classpath:db/migration/workflow");

    static Vertx vertxRef;
    static Pool pool;

    @BeforeAll
    static void setUp(Vertx vertx) {
        vertxRef = vertx;
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
    }

    @BeforeEach
    void truncateTables() throws Exception {
        ScopedStartFixture.truncate(pool);
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("a start records the start scope of its client, and null and default are one scope")
    void startRecordsTheClientStartScope() throws Exception {
        // Given: version 1 of a definition in the default scope, and a client factory over the engine
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done"));
        PurchaseContract unscoped = harness.factory().create(PurchaseContract.class, ID);
        PurchaseContract inAcme =
                harness.factory().client(PurchaseContract.class, ID).scope(ACME).create();

        // When: one start without a scope and one in acme
        WorkflowInstanceId defaultId = await(unscoped.start("k-default", null, new StartPurchase("a")));
        WorkflowInstanceId acmeId = await(inAcme.start("k-acme", null, new StartPurchase("b")));

        // Then: the first instance has the default start scope and the second the acme start scope
        WorkflowInstance defaultRow = instance(harness, defaultId);
        WorkflowInstance acmeRow = instance(harness, acmeId);
        assertThat(defaultRow.scopeKey()).isEqualTo(DEFAULT);
        assertThat(acmeRow.scopeKey()).isEqualTo(ACME);

        // and: both are pinned to version 1 of the default scope's definition
        assertThat(defaultRow.definitionScopeKey()).isEqualTo(DEFAULT);
        assertThat(defaultRow.definitionVersion()).isEqualTo(1L);
        assertThat(acmeRow.definitionScopeKey()).isEqualTo(DEFAULT);
        assertThat(acmeRow.definitionVersion()).isEqualTo(1L);

        // When: the engine starts a command with a null start scope and one with the default scope, both with K0
        WorkflowInstanceId fromNull = await(harness.engine().start(command("K0", null, null, null, null)));
        WorkflowInstanceId fromDefault = await(harness.engine().start(command("K0", null, null, DEFAULT, null)));

        // Then: both are the same start, and one start dedup row exists for K0, in the default scope
        assertThat(fromDefault).isEqualTo(fromNull);
        assertThat(startDedupRowsOf(pool, "K0")).containsExactly(DEFAULT + "|" + ID);
        assertThat(instance(harness, fromNull).scopeKey()).isEqualTo(DEFAULT);
    }

    @Test
    @DisplayName("a business key is unique per start scope")
    void businessKeyIsUniquePerStartScope() throws Exception {
        // Given: version 1 of a definition in the default scope, and clients in two start scopes
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done"));
        PurchaseContract inAcme =
                harness.factory().client(PurchaseContract.class, ID).scope(ACME).create();
        PurchaseContract inGlobex = harness.factory()
                .client(PurchaseContract.class, ID)
                .scope(GLOBEX)
                .create();

        // When: business key PO-42 starts in acme, then in globex
        WorkflowInstanceId acmeId = await(inAcme.start("k-1", "PO-42", new StartPurchase("a")));
        WorkflowInstanceId globexId = await(inGlobex.start("k-2", "PO-42", new StartPurchase("b")));

        // Then: both start, as different instances
        assertThat(globexId).isNotEqualTo(acmeId);
        assertThat(instanceRows(pool)).isEqualTo(2L);

        // When: PO-42 starts again in acme with another idempotency key
        Throwable duplicate = failureOf(() -> inAcme.start("k-3", "PO-42", new StartPurchase("c")));

        // Then: the duplicate business key is refused by the unique index, and no third instance exists
        assertThat(duplicate)
                .isInstanceOf(WorkflowBusinessKeyConflictException.class)
                .hasCauseInstanceOf(UniqueConstraintViolationException.class)
                .hasMessageNotContaining("PO-42");
        assertThat(instanceRows(pool)).isEqualTo(2L);
    }

    @Test
    @DisplayName("an idempotency key is scoped per start scope")
    void idempotencyKeyIsScopedPerStartScope() throws Exception {
        // Given: version 1 of a definition in the default scope, and clients in two start scopes
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done"));
        PurchaseContract inAcme =
                harness.factory().client(PurchaseContract.class, ID).scope(ACME).create();
        PurchaseContract inGlobex = harness.factory()
                .client(PurchaseContract.class, ID)
                .scope(GLOBEX)
                .create();

        // When: idempotency key K starts in acme, in globex, and again in acme
        WorkflowInstanceId firstAcme = await(inAcme.start("K", null, new StartPurchase("a")));
        WorkflowInstanceId globex = await(inGlobex.start("K", null, new StartPurchase("b")));
        WorkflowInstanceId secondAcme = await(inAcme.start("K", null, new StartPurchase("c")));

        // Then: acme and globex are two instances, and the second acme start is the first acme instance
        assertThat(globex).isNotEqualTo(firstAcme);
        assertThat(secondAcme).isEqualTo(firstAcme);
        assertThat(instanceRows(pool)).isEqualTo(2L);

        // and: the start dedup rows are one per scope, in the namespace of the definition id
        assertThat(startDedupRowsOf(pool, "K")).containsExactly(ACME + "|" + ID, GLOBEX + "|" + ID);
    }

    @Test
    @DisplayName("an explicit version resolves the start scope's own definition, then the default scope's")
    void explicitVersionResolvesOwnScopeThenDefault() throws Exception {
        // Given: version 1 in the default scope and, with another step graph, in acme
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done-default"));
        harness.registry().register(ACME, new Purchase(ID, 1L, "done-acme"));
        String defaultHash = planHash(harness, DEFAULT);
        String acmeHash = planHash(harness, ACME);
        assertThat(acmeHash).isNotEqualTo(defaultHash);

        // When: acme starts version 1, globex starts version 1, and acme starts version 1 of the default scope
        PurchaseContract acmeOwn = harness.factory()
                .client(PurchaseContract.class, ID)
                .scope(ACME)
                .version(1)
                .create();
        PurchaseContract globexOwn = harness.factory()
                .client(PurchaseContract.class, ID)
                .scope(GLOBEX)
                .version(1)
                .create();
        PurchaseContract acmeNamingDefault = harness.factory()
                .client(PurchaseContract.class, ID)
                .scope(ACME)
                .version(DEFAULT, 1)
                .create();
        WorkflowInstance fromAcme = instance(harness, await(acmeOwn.start("k-1", null, new StartPurchase("a"))));
        WorkflowInstance fromGlobex = instance(harness, await(globexOwn.start("k-2", null, new StartPurchase("b"))));
        WorkflowInstance fromAcmeNamingDefault =
                instance(harness, await(acmeNamingDefault.start("k-3", null, new StartPurchase("c"))));

        // Then: the pins are (start scope owns it, acme), (default, default) and (default, default), with their plans
        assertThat(fromAcme.scopeKey()).isEqualTo(ACME);
        assertThat(fromAcme.definitionScopeKey()).isEqualTo(ACME);
        assertThat(fromAcme.definitionVersion()).isEqualTo(1L);
        assertThat(fromAcme.planHash()).isEqualTo(acmeHash);

        assertThat(fromGlobex.scopeKey()).isEqualTo(GLOBEX);
        assertThat(fromGlobex.definitionScopeKey()).isEqualTo(DEFAULT);
        assertThat(fromGlobex.definitionVersion()).isEqualTo(1L);
        assertThat(fromGlobex.planHash()).isEqualTo(defaultHash);

        assertThat(fromAcmeNamingDefault.scopeKey()).isEqualTo(ACME);
        assertThat(fromAcmeNamingDefault.definitionScopeKey()).isEqualTo(DEFAULT);
        assertThat(fromAcmeNamingDefault.definitionVersion()).isEqualTo(1L);
        assertThat(fromAcmeNamingDefault.planHash()).isEqualTo(defaultHash);
    }

    @Test
    @DisplayName("an explicit start of a candidate fails and writes nothing")
    void explicitVersionOfCandidateFails(Vertx vertx) throws Exception {
        // Given: version 1 of a document definition active in the default scope, and version 2 of it loaded into
        // acme and not published
        String documentId = "order-fulfillment-doc";
        Pool managedPool = ManagedDefinitionDatabase.openPool(vertx, db);
        AppComponent app =
                ManagedDefinitionDatabase.app(vertx, managedPool, new BootObservation(documentId, 1, "unused", false));
        VertiqueApplicationHandle<AppComponent> handle = null;
        try {
            await(managedPool
                    .preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                    .execute(Tuple.of(documentId)));
            handle = await(
                    VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, new JsonObject()), ignored -> app));
            SourceMetadata metadata =
                    new SourceMetadata("scoped-start-test", "fixture", "r1", null, null, Instant.now());
            DefinitionKey activeVersion = new DefinitionKey(DEFAULT, documentId, 1);
            await(app.definitionService()
                    .load(DEFAULT, ManagedDefinitionDatabase.yamlFixture(), DocumentFormat.YAML, metadata));
            await(app.definitionService().publish(activeVersion));
            await(app.definitionService()
                    .load(ACME, ManagedDefinitionDatabase.jsonFixture(), DocumentFormat.JSON, metadata));
            String contractName = app.workflowRegistry().resolve(activeVersion).contractName();
            long instancesBefore = instanceRows(managedPool);

            // When: acme starts version 2, and acme starts version 2 of acme
            Throwable viaVersion = failureOf(() -> app.workflowOperations()
                    .start(new StartCommand(documentId, contractName, Map.of(), "k-1", null, null, 2L, ACME, null)));
            Throwable viaScopeAndVersion = failureOf(() -> app.workflowOperations()
                    .start(new StartCommand(documentId, contractName, Map.of(), "k-2", null, null, 2L, ACME, ACME)));

            // Then: both fail as an unavailable version naming the definition, and no instance row was written
            assertThat(viaVersion)
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class)
                    .hasMessageContaining(documentId);
            assertThat(viaScopeAndVersion)
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class)
                    .hasMessageContaining(documentId);
            assertThat(instanceRows(managedPool)).isEqualTo(instancesBefore);
            assertThat(startDedupRows(managedPool)).isZero();
        } finally {
            if (handle != null) {
                await(handle.shutdown());
            }
            await(managedPool.close());
        }
    }

    @Test
    @DisplayName("a version in a scope that is neither the start scope nor the default scope is refused")
    void versionInForeignScopeIsRefused() throws Exception {
        // Given: version 1 in the default scope and, with another step graph, in globex
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done-default"));
        harness.registry().register(GLOBEX, new Purchase(ID, 1L, "done-globex"));
        PurchaseContract acmeNamingGlobex = harness.factory()
                .client(PurchaseContract.class, ID)
                .scope(ACME)
                .version(GLOBEX, 1)
                .create();

        // When: acme starts version 1 of globex with a new idempotency key
        Throwable failure = failureOf(() -> acmeNamingGlobex.start("k-foreign", null, new StartPurchase("a")));

        // Then: it is refused as a missing definition that names the definition id and the named scope
        assertThat(failure)
                .isInstanceOf(WorkflowDefinitionMissingException.class)
                .hasMessageContaining(ID)
                .hasMessageContaining(GLOBEX);

        // and: no instance row and no start dedup row exist
        assertThat(instanceRows(pool)).isZero();
        assertThat(startDedupRows(pool)).isZero();
        assertThat(startDedupRowsOf(pool, "k-foreign")).isEmpty();
    }

    @Test
    @DisplayName("a client of another contract is refused at start and writes nothing")
    void contractMismatchAtStartIsRefusedBeforeAnyWrite() throws Exception {
        // Given: a definition of the purchase contract, and a client of a contract that no definition implements
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done"));
        OtherContract foreign =
                harness.factory().client(OtherContract.class, ID).version(1).create();
        PurchaseContract own = harness.factory().create(PurchaseContract.class, ID);

        // When: the foreign client starts version 1 of the definition
        Throwable failure = failureOf(() -> foreign.start("k-foreign", "bk-1", new StartPurchase("payload-marker-9")));

        // Then: it is refused with the contract mismatch, naming the definition and both contracts, never the payload
        assertThat(failure)
                .isInstanceOf(WorkflowDefinitionContractMismatchException.class)
                .hasMessageContaining(ID)
                .hasMessageContaining(PURCHASE_CONTRACT)
                .hasMessageContaining("other")
                .hasMessageNotContaining("payload-marker-9");
        DefinitionKey addressed = ((WorkflowDefinitionContractMismatchException) failure).definitionKey();
        assertThat(addressed.definitionId()).isEqualTo(ID);
        assertThat(addressed.definitionVersion()).isEqualTo(1L);

        // and: no instance row and no start dedup row exist
        assertThat(instanceRows(pool)).isZero();
        assertThat(startDedupRows(pool)).isZero();

        // When: the definition's own client starts it with the same idempotency key and business key
        WorkflowInstanceId id = await(own.start("k-foreign", "bk-1", new StartPurchase("a")));

        // Then: the start succeeds, so the refused start had claimed neither key
        assertThat(instance(harness, id).businessKey()).isEqualTo("bk-1");
        assertThat(instanceRows(pool)).isEqualTo(1L);
    }

    @Test
    @DisplayName("an invalid start scope or target scope is refused before any write")
    void invalidScopeIsRefusedBeforeAnyWrite() throws Exception {
        // Given: version 1 of a definition in the default scope
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done"));
        String controlScope = "ac" + (char) 7 + "me";
        String blankScope = "  ";
        String longScope = "a".repeat(256);

        // When: starts name a start scope or a target scope that breaks the scope key rules
        // Then: every one is refused as an illegal argument
        assertThat(failureOf(() -> startIn(harness, controlScope, null, "k-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(failureOf(() -> startIn(harness, blankScope, null, "k-2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(failureOf(() -> startIn(harness, ACME, longScope, "k-3")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(failureOf(() -> startIn(harness, ACME, controlScope, "k-4")))
                .isInstanceOf(IllegalArgumentException.class);

        // and: nothing was written for any of them
        assertThat(instanceRows(pool)).isZero();
        assertThat(startDedupRows(pool)).isZero();
    }

    // --- Helpers ---

    /** Builds a client for the start scope, naming version 1 of the target scope when one is given, and starts. */
    private static Future<WorkflowInstanceId> startIn(
            Harness harness, String startScope, String targetScope, String idempotencyKey) {
        WorkflowClientBuilder<PurchaseContract> builder =
                harness.factory().client(PurchaseContract.class, ID).scope(startScope);
        if (targetScope != null) {
            builder = builder.version(targetScope, 1);
        }
        return builder.create().start(idempotencyKey, null, new StartPurchase("p"));
    }

    private static StartCommand command(
            String idempotencyKey, String businessKey, Long version, String startScope, String targetScope) {
        return new StartCommand(
                ID,
                PURCHASE_CONTRACT,
                new StartPurchase("p"),
                idempotencyKey,
                businessKey,
                null,
                version,
                startScope,
                targetScope);
    }

    private static WorkflowInstance instance(Harness harness, WorkflowInstanceId id) throws Exception {
        return await(harness.engine().query(id)).instance();
    }

    private static String planHash(Harness harness, String scopeKey) {
        return harness.registry()
                .resolve(new DefinitionKey(scopeKey, ID, 1L))
                .plan()
                .planHash();
    }
}
