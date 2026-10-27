// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.ActivationFixtures.DEFAULT;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.VertxModule;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocState;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParserProbe;
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservationModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.CodeCatalogModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.DefinitionSourceModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.LargeUnicodeMapperModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.PostgresInfrastructureModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.TypeCatalogModule;
import dev.vertique.workflow.postgresql.registry.PlanCacheFixture.PlanCacheGraph;
import dev.vertique.workflow.postgresql.registry.WorkflowDefinitionSqlObserver.Execution;
import dev.vertique.workflow.postgresql.registry.failing.v1.FinanceDecision;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry.Kind;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that an executor's startup builds a plan only for what it must: the code definitions it deploys and the
 * packaged documents, and, when asked to, the live documents of the default scope. It reads only the default scope's
 * rows and activation records and the rows being published, however many rows other scopes hold, and it seals the
 * registry when it ends.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 180, unit = TimeUnit.SECONDS)
public class BoundedStartupIT {

    /** The contract of the code definitions and of the default scope's documents. */
    @WorkflowContract("bounded-startup-c")
    public interface ContractC extends OrderDocumentSignals {}

    /** The contract of the documents that other scopes hold. */
    @WorkflowContract("bounded-startup-t")
    public interface ContractT extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_bounded_startup_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String CODE_A = "bounded-code-a";
    private static final String CODE_B = "bounded-code-b";
    private static final String DOCUMENT_A = "bounded-document-a";
    private static final String DOCUMENT_B = "bounded-document-b";
    private static final String TENANT_DOCUMENT = "bounded-tenant-document";
    private static final String BROKEN_DOCUMENT = "bounded-broken-document";
    private static final String LATE_CODE = "bounded-late-code";
    private static final String[] OWNED_IDS = {CODE_A, CODE_B, DOCUMENT_A, DOCUMENT_B, TENANT_DOCUMENT, BROKEN_DOCUMENT
    };

    private static final int SCOPES = 1_000;
    private static final int VERSIONS_PER_SCOPE = 10;
    private static final Instant LOADED_AT = Instant.parse("2026-10-27T12:00:00Z");
    private static final Duration SETTLE = Duration.ofSeconds(30);
    private static final long START_TIMEOUT_SECONDS = 60;

    @Test
    @DisplayName("startup builds only the code definitions and reads no row of another scope")
    void startupBuildsOnlyCodeAndDefaultDocumentsAndReadsNoScopedRows(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database);

            // Given two code definitions, two runtime-published default scope documents, and ten thousand available
            // rows across a thousand other scopes, each scope with an activation record
            Node first = start(database, vertx, config(null), List.of(code(CODE_A), code(CODE_B)), true, null, null);
            publishDefaultDocument(first, DOCUMENT_A, false);
            publishDefaultDocument(first, DOCUMENT_B, false);
            seedOtherScopes(database, first);
            first.close();
            assertThat(count(database, "SELECT count(*) FROM workflow_definitions WHERE scope_key <> 'default'"))
                    .isEqualTo(SCOPES * VERSIONS_PER_SCOPE);
            assertThat(count(
                            database,
                            "SELECT count(*) FROM workflow_definition_activations WHERE scope_key <> 'default'"))
                    .isEqualTo(SCOPES);

            // When an executor starts on that database with default configuration, with the statements and the
            // document parses of its startup observed
            WorkflowDefinitionSqlObserver sql = new WorkflowDefinitionSqlObserver();
            WorkflowDefinitionParserProbe parses = new WorkflowDefinitionParserProbe();
            Node node = start(database, vertx, config(null), List.of(code(CODE_A), code(CODE_B)), true, sql, parses);
            List<Execution> scopedReads = StartupStatements.scopedReads(sql);
            int documentParses = parses.calls();

            // Then startup succeeded and read the definition tables at all, so the check below is not vacuous
            assertThat(node.failure())
                    .as(
                            "the failure of startup (document builds so far: %s, unbounded reads so far: %s)",
                            documentParses, scopedReads.size())
                    .isNull();
            assertThat(StartupStatements.definitionReads(sql)).isNotEmpty();

            // And no document was built: the code definitions never pass through the document parser and neither
            // the default scope's documents nor the other scopes' rows were built
            assertThat(documentParses).as("document builds during startup").isZero();
            assertThat(node.app().planCache().size()).isZero();

            // And both code definitions are registered
            assertThat(node.app().workflowRegistry().find(new DefinitionKey(DEFAULT, CODE_A, 1)))
                    .isPresent();
            assertThat(node.app().workflowRegistry().find(new DefinitionKey(DEFAULT, CODE_B, 1)))
                    .isPresent();

            // And no statement selected definition or activation rows beyond the default scope, the rows being
            // published or one definition id
            assertThat(scopedReads)
                    .as("startup reads of definition or activation rows that are not bounded to the default scope")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("a broken default scope document fails startup only when the option is set")
    void brokenDefaultScopeDocumentFailsStartupOnlyWithTheOption(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database);

            // Given a runtime-published default scope document whose name binding names a class that no longer
            // resolves
            DefinitionKey broken = seedBrokenDefaultDocument(database, vertx);

            // When an executor starts with the option set to build the default scope's documents
            Node strict = start(database, vertx, config(true), List.of(), false, null, null);

            // Then startup fails with the unknown type name naming the default scope and the id
            assertThat(strict.failure()).isNotNull();
            WorkflowUnknownTypeNameException unknown =
                    causeOf(strict.failure(), WorkflowUnknownTypeNameException.class);
            assertThat(unknown.getMessage()).contains(DEFAULT, BROKEN_DOCUMENT);

            // When another executor starts with the option off and a start of that version is recorded
            Node lenient = start(database, vertx, config(false), List.of(), false, null, null);
            assertThat(lenient.failure()).isNull();
            WorkflowInstanceId instance = recordStart(vertx, database, lenient, broken);
            PlanCacheFixture.awaitCondition(
                    "the command to be deferred", SETTLE, () -> command(database, instance, "deferral_count") >= 1);

            // Then the command is deferred with no attempt spent
            assertThat(commandState(database, instance)).isEqualTo("DEFERRED");
            assertThat(command(database, instance, "attempts")).isZero();
        }
    }

    @Test
    @DisplayName("registering a definition after startup is refused and the registry stays as it was")
    void registerAfterStartupIsRefused(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database);

            // Given a started executor graph
            Node node = start(database, vertx, config(null), List.of(code(CODE_A)), true, null, null);
            assertThat(node.failure()).isNull();

            // When a new code definition is registered on its registry
            WorkflowDefinition<?, ?> late = code(LATE_CODE);
            Throwable refused =
                    catchThrowable(() -> node.app().workflowRegistry().register(late));

            // Then the registration is refused naming the definition, and the registry does not hold it
            assertThat(refused).isInstanceOf(IllegalStateException.class).hasMessageContaining(LATE_CODE);
            assertThat(node.app().workflowRegistry().find(new DefinitionKey(DEFAULT, LATE_CODE, 1)))
                    .isEmpty();
        }

        // Given a registry that no engine started
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(code(CODE_A));

        // When it is sealed twice and another definition is registered
        registry.seal();
        registry.seal();
        Throwable refused = catchThrowable(() -> registry.register(code(CODE_B)));

        // Then the one registered before the seal stays, the other is refused naming it, and the second seal did
        // nothing
        assertThat(registry.find(new DefinitionKey(DEFAULT, CODE_A, 1))).isPresent();
        assertThat(refused).isInstanceOf(IllegalStateException.class).hasMessageContaining(CODE_B);
        assertThat(registry.find(new DefinitionKey(DEFAULT, CODE_B, 1))).isEmpty();
    }

    // --- Graphs ---

    /** A started application graph, or the failure of its start, which includes not having started in time. */
    private static final class Node {
        private final Pool pool;
        private final PlanCacheGraph app;
        private final VertiqueApplicationHandle<AppComponent> handle;
        private final Throwable failure;
        private boolean closed;

        private Node(Pool pool, PlanCacheGraph app, VertiqueApplicationHandle<AppComponent> handle, Throwable failure) {
            this.pool = pool;
            this.app = app;
            this.handle = handle;
            this.failure = failure;
        }

        PlanCacheGraph app() {
            return app;
        }

        /** The startup failure, or {@code null} when the graph started. */
        Throwable failure() {
            return failure;
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                if (handle != null) await(handle.shutdown());
            } finally {
                await(pool.close());
            }
        }
    }

    /**
     * Starts a graph over a pool of its own, optionally observed, and waits for its start to end. The catalog lists the
     * finance decision class when asked to, so a document that names it by its binary name resolves.
     */
    private static Node start(
            DefinitionScenario database,
            Vertx vertx,
            JsonObject config,
            List<WorkflowDefinition<?, ?>> code,
            boolean decisionListed,
            WorkflowDefinitionSqlObserver observer,
            WorkflowDefinitionParserProbe parserProbe) {
        Pool raw = ManagedDefinitionDatabase.openPool(vertx, db, 10);
        Pool pool = observer == null ? raw : observer.observe(raw);
        WorkflowDefinitionSource none = List::of;
        List<WorkflowTypeCatalogEntry> catalog = decisionListed
                ? List.of(
                        new WorkflowTypeCatalogEntry(Kind.TYPE, FinanceDecision.class.getName(), FinanceDecision.class))
                : List.of();
        PlanCacheGraph app = DaggerPlanCacheFixture_PlanCacheApp.builder()
                .vertxModule(new VertxModule(vertx, config))
                .postgresInfrastructureModule(new PostgresInfrastructureModule(pool))
                .bootObservationModule(
                        new BootObservationModule(new BootObservation("bounded-unused", 1, "unused", false)))
                .definitionSourceModule(new DefinitionSourceModule(none))
                .codeCatalogModule(new CodeCatalogModule(false, code))
                .largeUnicodeMapperModule(new LargeUnicodeMapperModule(false))
                .parserModule((parserProbe == null ? new WorkflowDefinitionParserProbe() : parserProbe).module())
                .typeCatalogModule(new TypeCatalogModule(BoundedStartupIT.class, catalog))
                .observerModule(new PlanCacheFixture.ObserverModule(new RecordingCacheObserver()))
                .build();
        Future<VertiqueApplicationHandle<AppComponent>> starting =
                VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, new JsonObject()), ignored -> app);
        Node node;
        try {
            node = new Node(
                    raw,
                    app,
                    starting.toCompletionStage().toCompletableFuture().get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    null);
        } catch (ExecutionException failure) {
            node = new Node(raw, app, null, failure.getCause());
        } catch (TimeoutException failure) {
            node = new Node(raw, app, null, failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while the application graph was starting", failure);
        }
        Node started = node;
        database.onClose(started::close);
        return started;
    }

    /** The configuration with the propagation period raised so the poller never runs in a test. */
    private static JsonObject config(Boolean buildDefaultScopeDocuments) {
        JsonObject definitions = new JsonObject().put("maxStalenessSeconds", 3600);
        if (buildDefaultScopeDocuments != null)
            definitions.put("buildDefaultScopeDocumentsAtStartup", buildDefaultScopeDocuments);
        return new JsonObject()
                .put(
                        "workflow",
                        new JsonObject()
                                .put("definitions", definitions)
                                .put("executor", new JsonObject().put("pollIntervalMs", 100)));
    }

    // --- Rows ---

    /** Loads and publishes a default scope document of the id and version 1. */
    private static void publishDefaultDocument(Node node, String id, boolean financeReview) {
        await(node.app()
                .definitionService()
                .load(
                        DEFAULT,
                        document(id, 1, ContractC.class, financeReview),
                        DocumentFormat.YAML,
                        source("default-" + id, LOADED_AT)));
        await(node.app().definitionService().publish(new DefinitionKey(DEFAULT, id, 1)));
    }

    /**
     * Publishes a default scope document that names the finance decision class, while the graph's catalog lists it,
     * and leaves the database for a graph whose catalog does not.
     */
    private static DefinitionKey seedBrokenDefaultDocument(DefinitionScenario database, Vertx vertx) {
        Node publisher = start(database, vertx, config(false), List.of(), true, null, null);
        assertThat(publisher.failure()).isNull();
        publishDefaultDocument(publisher, BROKEN_DOCUMENT, true);
        publisher.close();
        return new DefinitionKey(DEFAULT, BROKEN_DOCUMENT, 1);
    }

    /**
     * Writes ten versions of one document into each of a thousand scopes, available, with one activation record per
     * scope, in batches. The plan hash of each version is that of the real compilation of its text, so a graph that
     * restored the rows would find them intact.
     */
    private static void seedOtherScopes(DefinitionScenario database, Node node) {
        List<Tuple> rows = new ArrayList<>();
        for (int version = 1; version <= VERSIONS_PER_SCOPE; version++) {
            byte[] text = document(TENANT_DOCUMENT, version, ContractT.class, false);
            CompiledDefinition compiled = node.app()
                    .definitionPipeline()
                    .load(new DefinitionResource(text, DocumentFormat.YAML, source("tenant-" + version, LOADED_AT)));
            String body = new String(text, StandardCharsets.UTF_8);
            for (int scope = 1; scope <= SCOPES; scope++) {
                rows.add(Tuple.of(
                        scopeKey(scope),
                        TENANT_DOCUMENT,
                        (long) version,
                        compiled.contractName(),
                        compiled.planHash(),
                        body,
                        DefinitionScenario.sha256Hex(body)));
            }
        }
        awaitLong(database.admin().preparedQuery("""
                        INSERT INTO workflow_definitions
                            (scope_key, definition_id, definition_version, kind, contract_name, state_type_name,
                             plan_hash, status, document, document_format, document_digest, source_type, loaded_at)
                        VALUES ($1, $2, $3, 'DOCUMENT', $4, 'example.State', $5, 'AVAILABLE', $6, 'YAML', $7,
                                'bounded-startup', now())
                        """).executeBatch(rows));
        List<Tuple> activations = new ArrayList<>();
        for (int scope = 1; scope <= SCOPES; scope++)
            activations.add(Tuple.of(scopeKey(scope), TENANT_DOCUMENT, scopeKey(scope), (long) VERSIONS_PER_SCOPE));
        awaitLong(database.admin().preparedQuery("""
                        INSERT INTO workflow_definition_activations
                            (scope_key, definition_id, target_scope_key, definition_version, activated_at)
                        VALUES ($1, $2, $3, $4, now())
                        """).executeBatch(activations));
    }

    private static String scopeKey(int scope) {
        return "bounded-scope-" + scope;
    }

    private static long count(DefinitionScenario database, String sql) {
        return awaitLong(database.admin().query(sql).execute())
                .iterator()
                .next()
                .getLong(0);
    }

    /** Clears the rows of the ids now and again when the scenario closes. */
    private static void own(DefinitionScenario database) {
        ActivationFixtures.clearActivations(database.admin(), OWNED_IDS);
        database.ownRows(OWNED_IDS);
        database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), OWNED_IDS));
    }

    // --- Documents and code ---

    /**
     * A document that waits for the approval signal and then completes, or, with a finance review, goes on to a human
     * task whose decision payload is the finance decision class named by its binary name.
     */
    private static byte[] document(String id, long version, Class<?> contract, boolean financeReview) {
        String afterApproval = financeReview ? "finance-review" : "done";
        String review = financeReview ? """
                  - id: finance-review
                    type: human-task
                    taskType: approval
                    assignment:
                      mode: role
                      value: reviewers
                    decisions:
                      - name: approve
                        payloadType: %s
                        applicator: doc.order.keepState
                        next: done
                """.formatted(FinanceDecision.class.getName()) : "";
        return """
                definitionId: %s
                definitionVersion: %d
                stateType: dev.vertique.workflow.definition.it.fixture.OrderDocState
                contract: %s
                startPayloadType: dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc
                initialStateMapper: doc.order.fromPlaceOrder
                initialStep: wait-approval
                steps:
                  - id: wait-approval
                    type: wait-signal
                    signal: inventory.reserved
                    payloadType: dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc
                    stateReducer: doc.order.applyInventoryReserved
                    next: %s
                %s  - id: done
                    type: complete
                """.formatted(id, version, contract.getName(), afterApproval, review)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static WorkflowDefinition<?, ?> code(String id) {
        return new CodeDefinition(id);
    }

    /** A default scope code definition that completes at once. */
    private static final class CodeDefinition implements WorkflowDefinition<OrderDocState, ContractC> {

        private final String definitionId;

        CodeDefinition(String definitionId) {
            this.definitionId = definitionId;
        }

        @Override
        public Class<ContractC> contract() {
            return ContractC.class;
        }

        @Override
        public Class<OrderDocState> stateType() {
            return OrderDocState.class;
        }

        @Override
        public String definitionId() {
            return definitionId;
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<OrderDocState> workflow) {
            workflow.init(PlaceOrderDoc.class, start -> new OrderDocState(start.orderId(), null, null, null))
                    .initialStep("done")
                    .complete("done");
        }
    }

    // --- Recorded starts ---

    /** Records a pending instance pinned to the row, with its start command, and commits. */
    private static WorkflowInstanceId recordStart(
            Vertx vertx, DefinitionScenario database, Node node, DefinitionKey key) {
        String planHash = awaitLong(database.admin()
                        .preparedQuery("SELECT plan_hash FROM workflow_definitions WHERE scope_key = $1 AND"
                                + " definition_id = $2 AND definition_version = $3")
                        .execute(Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion())))
                .iterator()
                .next()
                .getString("plan_hash");
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
        String payload = new JsonObject().put("orderId", "order-1").encode();
        PlanCacheGraph app = node.app();
        await(onCallerContext(vertx, () -> database.admin().withTransaction(tx -> app.commandRepository()
                .lockInstance(id, tx)
                .compose(locked -> app.instanceRepository().insert(pending, tx))
                .compose(inserted ->
                        app.commandRepository().recordStart(id, "idem-" + id.value(), payload, null, tx)))));
        return id;
    }

    /** Runs the work on a duplicated context of the Vert.x instance, as a caller's transaction would. */
    private static <T> Future<T> onCallerContext(Vertx vertx, Supplier<Future<T>> work) {
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

    private static String commandState(DefinitionScenario database, WorkflowInstanceId id) {
        return commandRow(database, id).getString("state");
    }

    private static long command(DefinitionScenario database, WorkflowInstanceId id, String column) {
        Row row = commandRow(database, id);
        return row.getInteger(column);
    }

    private static Row commandRow(DefinitionScenario database, WorkflowInstanceId id) {
        return awaitLong(database.admin()
                        .preparedQuery(
                                "SELECT state, attempts, deferral_count FROM workflow_commands WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next();
    }

    // --- Waiting ---

    /** Waits for the future, which may take longer than the shared helper's ten seconds. */
    private static <T> T awaitLong(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting a future", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("A future did not complete within two minutes", e);
        }
    }

    private static <T extends Throwable> T causeOf(Throwable failure, Class<T> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return type.cast(cause);
        }
        throw new AssertionError("Expected a " + type.getSimpleName() + " in the cause chain", failure);
    }
}
