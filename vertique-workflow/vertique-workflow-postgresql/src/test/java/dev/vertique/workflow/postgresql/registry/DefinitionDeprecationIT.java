// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.ActivationStatus;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionFilter.TenantScope;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies that deprecating a loaded and activated document through the public service commits the
 * status, projects it into the local cache and registry, refuses new explicit starts, and survives a
 * restart of the application graph.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionDeprecationIT {

    @WorkflowContract(definitionId = "deprecation-it-active-doc", definitionVersion = 1)
    public interface ActiveCaseV1 {}

    @WorkflowContract(definitionId = "deprecation-it-active-doc", definitionVersion = 2)
    public interface ActiveCaseV2 {}

    @WorkflowContract(definitionId = "deprecation-it-superseded-doc", definitionVersion = 1)
    public interface SupersededCaseV1 {}

    @WorkflowContract(definitionId = "deprecation-it-superseded-doc", definitionVersion = 2)
    public interface SupersededCaseV2 {}

    @WorkflowContract(definitionId = "deprecation-it-transition-doc", definitionVersion = 1)
    public interface TransitionCaseV1 {}

    @WorkflowContract(definitionId = "deprecation-it-transition-doc", definitionVersion = 2)
    public interface TransitionCaseV2 {}

    @WorkflowContract(definitionId = "deprecation-it-owner-doc", definitionVersion = 1)
    public interface OwnerCaseV1 {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_deprecation_it")
            .withMigration("classpath:db/migration/workflow");

    @ParameterizedTest(name = "target is {0} before it is deprecated")
    @EnumSource(
            value = DefinitionStatus.class,
            names = {"ACTIVE", "SUPERSEDED"})
    void persistsDeprecationThroughService(DefinitionStatus targetStatusBefore, Vertx vertx) {
        // Version 2 is activated last, so it is ACTIVE and version 1 is SUPERSEDED
        String id = "deprecation-it-" + targetStatusBefore.name().toLowerCase() + "-doc";
        boolean targetIsActive = targetStatusBefore == DefinitionStatus.ACTIVE;
        long targetVersion = targetIsActive ? 2 : 1;
        long otherVersion = targetIsActive ? 1 : 2;
        DefinitionStatus otherStatusBefore = targetIsActive ? DefinitionStatus.SUPERSEDED : DefinitionStatus.ACTIVE;
        Class<?> contractV1 = targetIsActive ? ActiveCaseV1.class : SupersededCaseV1.class;
        Class<?> contractV2 = targetIsActive ? ActiveCaseV2.class : SupersededCaseV2.class;
        DefinitionKey targetKey = new DefinitionKey(null, id, targetVersion);
        byte[] documentV1 = document(id, 1, contractV1);
        byte[] documentV2 = document(id, 2, contractV2);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(id);
            Node node = fixture.node();
            AppComponent app = node.app();

            // Given two versions loaded and activated through the service, so both are registered and cached
            CompiledDefinitionRef loadedV1 = await(
                    app.definitionService().load(documentV1, DocumentFormat.YAML, source("deprecation-v1"), null));
            await(app.definitionService().activate(new DefinitionKey(null, id, 1)));
            CompiledDefinitionRef loadedV2 = await(
                    app.definitionService().load(documentV2, DocumentFormat.YAML, source("deprecation-v2"), null));
            await(app.definitionService().activate(new DefinitionKey(null, id, 2)));
            CompiledDefinitionRef loadedTarget = targetIsActive ? loadedV2 : loadedV1;

            // And the committed predecessor status of the target and of the other version is the one under test
            Stored targetBefore = fixture.stored(id, targetVersion);
            Stored otherBefore = fixture.stored(id, otherVersion);
            assertThat(targetBefore.status()).isEqualTo(targetStatusBefore.name());
            assertThat(otherBefore.status()).isEqualTo(otherStatusBefore.name());
            assertThat(targetBefore.planHash()).isEqualTo(loadedTarget.planHash());
            assertThat(targetBefore.document()).isNotBlank();
            assertThat(app.workflowRegistry()
                            .resolvePinned(id, targetVersion)
                            .plan()
                            .planHash())
                    .isEqualTo(loadedTarget.planHash());
            assertThat(app.workflowRegistry().resolvePinned(id, otherVersion)).isNotNull();
            assertThat(app.workflowDefinitionStore().currentStatus(id, targetVersion))
                    .isEqualTo(ActivationStatus.valueOf(targetStatusBefore.name()));
            assertThat(app.workflowDefinitionStore().currentStatus(id, otherVersion))
                    .isEqualTo(ActivationStatus.valueOf(otherStatusBefore.name()));
            assertThat(app.workflowRegistry().isDeprecated(id, targetVersion)).isFalse();

            // When the target version is deprecated through the public service
            await(app.definitionService().deprecate(targetKey));

            // Then the target row is committed as DEPRECATED with its original key, hash and source
            Stored targetAfter = fixture.stored(id, targetVersion);
            assertThat(targetAfter.status()).isEqualTo("DEPRECATED");
            assertThat(targetAfter.planHash()).isEqualTo(loadedTarget.planHash());
            assertThat(targetAfter)
                    .usingRecursiveComparison()
                    .ignoringFields("status", "changedAt")
                    .isEqualTo(targetBefore);
            assertThat(targetAfter.changedAt()).isAfter(targetBefore.changedAt());

            // And the other version's row is exactly as it was before the deprecation
            assertThat(fixture.stored(id, otherVersion)).isEqualTo(otherBefore);

            // And the service metadata reports the target as DEPRECATED and the other version unchanged
            List<WorkflowDefinitionRecord> serviceRecords =
                    await(app.definitionService().list(new DefinitionFilter(TenantScope.GLOBAL, null, null))).stream()
                            .filter(record -> record.key().definitionId().equals(id))
                            .toList();
            assertThat(serviceRecords)
                    .extracting(record -> record.key().definitionVersion(), WorkflowDefinitionRecord::status)
                    .containsExactlyInAnyOrder(
                            tuple(targetVersion, DefinitionStatus.DEPRECATED), tuple(otherVersion, otherStatusBefore));

            // And the local cache itself reports the target as DEPRECATED and the other version unchanged
            assertThat(app.workflowDefinitionStore().currentStatus(id, targetVersion))
                    .isEqualTo(ActivationStatus.DEPRECATED);
            assertThat(app.workflowDefinitionStore().currentStatus(id, otherVersion))
                    .isEqualTo(ActivationStatus.valueOf(otherStatusBefore.name()));
            assertThat(app.workflowDefinitionStore().list())
                    .filteredOn(ref -> ref.definitionId().equals(id))
                    .extracting(CompiledDefinitionRef::definitionVersion, CompiledDefinitionRef::status)
                    .containsExactlyInAnyOrder(
                            tuple(targetVersion, ActivationStatus.DEPRECATED),
                            tuple(otherVersion, ActivationStatus.valueOf(otherStatusBefore.name())));

            // And the registry marks only the target, skips it for current resolution and still serves it pinned
            assertThat(app.workflowRegistry().isDeprecated(id, targetVersion)).isTrue();
            assertThat(app.workflowRegistry().isDeprecated(id, otherVersion)).isFalse();
            assertThat(app.workflowRegistry().resolveCurrent(id).plan().definitionVersion())
                    .isEqualTo(otherVersion);
            assertThat(app.workflowRegistry()
                            .resolvePinned(id, targetVersion)
                            .plan()
                            .planHash())
                    .isEqualTo(loadedTarget.planHash());

            // And an explicit new start of the target version fails typed and creates no instance
            Throwable startFailure = failureOf(app.workflowOperations()
                    .start(new StartCommand(
                            id, new PlaceOrderDoc("deprecation-" + id), "start-" + id, null, null, targetVersion)));
            assertThat(startFailure).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.DEPRECATED);
                assertThat(failure.key().definitionId()).isEqualTo(id);
                assertThat(failure.key().definitionVersion()).isEqualTo(targetVersion);
            });
            assertThat(fixture.instanceCount(id)).isZero();

            // When the application graph is closed and a fresh one starts on the same database
            node.close();
            fixture.node();

            // Then the target row is still DEPRECATED with its original key, hash and source
            Stored targetAfterRestart = fixture.stored(id, targetVersion);
            assertThat(targetAfterRestart.status()).isEqualTo("DEPRECATED");
            assertThat(targetAfterRestart.planHash()).isEqualTo(loadedTarget.planHash());
            assertThat(targetAfterRestart)
                    .usingRecursiveComparison()
                    .ignoringFields("changedAt")
                    .isEqualTo(targetAfter);
        }
    }

    private record TransitionAttempt(DefinitionKey key, DefinitionStatus from, DefinitionStatus to) {}

    @Test
    void rejectsUnsupportedOrMismatchedTransitions(Vertx vertx) {
        String id = "deprecation-it-transition-doc";
        DefinitionKey supersededKey = new DefinitionKey(null, id, 1);
        DefinitionKey activeKey = new DefinitionKey(null, id, 2);
        DefinitionKey missingKey = new DefinitionKey(null, id, 99);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(id);
            Node node = fixture.node();
            AppComponent app = node.app();
            WorkflowDefinitionRegistryStore store = app.definitionRegistryStore();

            // Given version 1 SUPERSEDED and version 2 ACTIVE, committed through the service
            await(app.definitionService()
                    .load(document(id, 1, TransitionCaseV1.class), DocumentFormat.YAML, source("transition-v1"), null));
            await(app.definitionService().activate(supersededKey));
            await(app.definitionService()
                    .load(document(id, 2, TransitionCaseV2.class), DocumentFormat.YAML, source("transition-v2"), null));
            await(app.definitionService().activate(activeKey));
            Stored supersededBefore = fixture.stored(id, 1);
            Stored activeBefore = fixture.stored(id, 2);
            assertThat(supersededBefore.status()).isEqualTo("SUPERSEDED");
            assertThat(activeBefore.status()).isEqualTo("ACTIVE");

            // When each unsupported status pair is requested on the ACTIVE row
            List<TransitionAttempt> unsupported = List.of(
                    new TransitionAttempt(activeKey, DefinitionStatus.CANDIDATE, DefinitionStatus.DEPRECATED),
                    new TransitionAttempt(activeKey, DefinitionStatus.ACTIVE, DefinitionStatus.ACTIVE),
                    new TransitionAttempt(activeKey, DefinitionStatus.ACTIVE, DefinitionStatus.SUPERSEDED),
                    new TransitionAttempt(activeKey, DefinitionStatus.DEPRECATED, DefinitionStatus.RETIRED),
                    new TransitionAttempt(activeKey, DefinitionStatus.ACTIVE, DefinitionStatus.RETIRED));
            for (TransitionAttempt attempt : unsupported) {
                int before = node.observer().statements().size();
                Throwable failure = failureOf(store.transition(attempt.key(), attempt.from(), attempt.to()));
                int after = node.observer().statements().size();

                // Then each is refused as an invalid transition before any statement reaches the database
                assertThat(failure)
                        .as("transition %s to %s", attempt.from(), attempt.to())
                        .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                            assertThat(refusal.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                            assertThat(refusal.key()).isEqualTo(attempt.key());
                        });
                assertThat(after - before)
                        .as("statements issued for %s to %s", attempt.from(), attempt.to())
                        .isZero();
            }
            assertThat(fixture.stored(id, 2)).isEqualTo(activeBefore);

            // When the expected status does not match the committed status of the row
            Throwable activeAsSuperseded =
                    failureOf(store.transition(activeKey, DefinitionStatus.SUPERSEDED, DefinitionStatus.DEPRECATED));
            Throwable supersededAsActive =
                    failureOf(store.transition(supersededKey, DefinitionStatus.ACTIVE, DefinitionStatus.DEPRECATED));

            // Then both are refused and neither row changes, including its status change time
            assertThat(activeAsSuperseded)
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                        assertThat(refusal.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                        assertThat(refusal.key()).isEqualTo(activeKey);
                    });
            assertThat(supersededAsActive)
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                        assertThat(refusal.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                        assertThat(refusal.key()).isEqualTo(supersededKey);
                    });
            assertThat(fixture.stored(id, 1)).isEqualTo(supersededBefore);
            assertThat(fixture.stored(id, 2)).isEqualTo(activeBefore);

            // When a version without a row is transitioned
            Throwable missing =
                    failureOf(store.transition(missingKey, DefinitionStatus.ACTIVE, DefinitionStatus.DEPRECATED));

            // Then it is refused as an invalid transition and no row appears or changes
            assertThat(missing).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                assertThat(refusal.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(refusal.key()).isEqualTo(missingKey);
            });
            assertThat(fixture.rowCount(id)).isEqualTo(2);
            assertThat(fixture.stored(id, 1)).isEqualTo(supersededBefore);
            assertThat(fixture.stored(id, 2)).isEqualTo(activeBefore);
        }
    }

    @Test
    void refusesTransitionWhileAnotherOwnerHoldsTheId(Vertx vertx) {
        String id = "deprecation-it-owner-doc";
        DefinitionKey globalKey = new DefinitionKey(null, id, 1);
        DefinitionKey tenantKey = new DefinitionKey("tenant-a", id, 1);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(id);
            Node node = fixture.node();
            AppComponent app = node.app();
            WorkflowDefinitionRegistryStore store = app.definitionRegistryStore();

            // Given the global owner holds the id as ACTIVE and tenant-a has a candidate of the same version
            await(app.definitionService()
                    .load(document(id, 1, OwnerCaseV1.class), DocumentFormat.YAML, source("owner-global"), null));
            await(app.definitionService().activate(globalKey));
            await(app.definitionService()
                    .load(document(id, 1, OwnerCaseV1.class), DocumentFormat.YAML, source("owner-tenant"), "tenant-a"));

            // And tenant-a is also forced to ACTIVE by SQL, because the public operations never let two owners hold an
            // id
            fixture.forceStatus(tenantKey, DefinitionStatus.ACTIVE);
            Stored globalBefore = fixture.stored(null, id, 1);
            Stored tenantBefore = fixture.stored("tenant-a", id, 1);
            assertThat(globalBefore.status()).isEqualTo("ACTIVE");
            assertThat(tenantBefore.status()).isEqualTo("ACTIVE");

            // When each owner's ACTIVE row is deprecated while the other owner holds the id
            Throwable tenantFailure =
                    failureOf(store.transition(tenantKey, DefinitionStatus.ACTIVE, DefinitionStatus.DEPRECATED));
            Throwable globalFailure =
                    failureOf(store.transition(globalKey, DefinitionStatus.ACTIVE, DefinitionStatus.DEPRECATED));

            // Then both are refused with an owner conflict naming the other holder
            assertThat(tenantFailure).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                assertThat(refusal.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                assertThat(refusal.key()).isEqualTo(tenantKey);
                assertThat(refusal.getMessage()).contains("global");
            });
            assertThat(globalFailure).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                assertThat(refusal.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                assertThat(refusal.key()).isEqualTo(globalKey);
                assertThat(refusal.getMessage()).contains("tenant-a");
            });

            // And neither row changed
            assertThat(fixture.stored(null, id, 1)).isEqualTo(globalBefore);
            assertThat(fixture.stored("tenant-a", id, 1)).isEqualTo(tenantBefore);
        }
    }

    private static byte[] document(String definitionId, long version, Class<?> contract) {
        String yaml = new String(ManagedDefinitionDatabase.yamlFixture(), StandardCharsets.UTF_8)
                .replace("definitionId: order-fulfillment-doc", "definitionId: " + definitionId)
                .replace("definitionVersion: 1", "definitionVersion: " + version)
                .replace(
                        "dev.vertique.workflow.definition.it.fixture.OrderFulfillmentDocumentWorkflowV1",
                        contract.getName());
        if (!yaml.contains("definitionId: " + definitionId + "\n")
                || !yaml.contains("definitionVersion: " + version + "\n")
                || !yaml.contains("contract: " + contract.getName() + "\n")) {
            throw new IllegalStateException("The workflow fixture did not take the scenario identity");
        }
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    private static SourceMetadata source(String label) {
        return new SourceMetadata(
                "deprecation-test", "memory:" + label, "revision-" + label, null, null, Instant.now());
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting a future", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("A future did not complete within ten seconds", e);
        }
    }

    private static Throwable failureOf(Future<?> future) {
        Future<Throwable> outcome =
                future.transform(result -> Future.succeededFuture(result.failed() ? result.cause() : null));
        return await(outcome);
    }

    /** One committed row of the registry table, read directly from the database. */
    private record Stored(
            long version,
            String status,
            String planHash,
            String document,
            String documentFormat,
            String sourceType,
            String sourceUri,
            String sourceRevision,
            OffsetDateTime loadedAt,
            OffsetDateTime changedAt) {}

    /** A started application graph with its own pool on the shared database; closing it is idempotent. */
    private static final class Node {
        private final Pool pool;
        private final WorkflowDefinitionSqlObserver observer;
        private final VertiqueApplicationHandle<?> handle;
        private final AppComponent app;
        private boolean closed;

        private Node(
                Pool pool,
                WorkflowDefinitionSqlObserver observer,
                VertiqueApplicationHandle<?> handle,
                AppComponent app) {
            this.pool = pool;
            this.observer = observer;
            this.handle = handle;
            this.app = app;
        }

        WorkflowDefinitionSqlObserver observer() {
            return observer;
        }

        AppComponent app() {
            return app;
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                await(handle.shutdown());
            } finally {
                await(pool.close());
            }
        }
    }

    @FunctionalInterface
    private interface Cleanup {
        void run() throws Exception;
    }

    /** Owns the application graphs and pools of one scenario and closes them in reverse order. */
    private static final class Fixture implements AutoCloseable {
        private final Vertx vertx;
        private final Deque<Cleanup> cleanups = new ArrayDeque<>();
        private Pool admin;

        Fixture(Vertx vertx) {
            this.vertx = vertx;
        }

        void onClose(Cleanup cleanup) {
            cleanups.push(cleanup);
        }

        /** Starts an application graph with its own pool and registers its closing. */
        Node node() {
            WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
            Pool pool = observer.observe(ManagedDefinitionDatabase.openPool(vertx, db));
            VertiqueApplicationHandle<?> handle;
            AppComponent app;
            try {
                app = ManagedDefinitionDatabase.app(
                        vertx, pool, new BootObservation("deprecation-unused", 1, "unused", false));
                handle = await(VertiqueApplicationBootstrap.start(
                        VertiqueRuntime.of(vertx, new JsonObject()), ignoredRuntime -> app));
            } catch (RuntimeException | Error failure) {
                await(pool.close());
                throw failure;
            }
            Node node = new Node(pool, observer, handle, app);
            onClose(node::close);
            return node;
        }

        /** Returns the pool used for test-owned SQL. */
        Pool admin() {
            if (admin == null) {
                admin = ManagedDefinitionDatabase.openPool(vertx, db);
                Pool opened = admin;
                onClose(() -> await(opened.close()));
            }
            return admin;
        }

        /** Clears the id's rows and instances now and again on close, so none reaches a later start. */
        void ownRows(String definitionId) {
            deleteRows(definitionId);
            onClose(() -> deleteRows(definitionId));
        }

        private void deleteRows(String definitionId) {
            await(admin().preparedQuery("DELETE FROM workflow_instances WHERE definition_id = $1")
                    .execute(Tuple.of(definitionId)));
            await(admin().preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                    .execute(Tuple.of(definitionId)));
        }

        long instanceCount(String definitionId) {
            return await(admin().preparedQuery(
                                    "SELECT count(*) AS total FROM workflow_instances WHERE definition_id = $1")
                            .execute(Tuple.of(definitionId)))
                    .iterator()
                    .next()
                    .getLong("total");
        }

        Stored stored(String definitionId, long version) {
            return stored(null, definitionId, version);
        }

        long rowCount(String definitionId) {
            return await(admin().preparedQuery(
                                    "SELECT count(*) AS total FROM workflow_definitions WHERE definition_id = $1")
                            .execute(Tuple.of(definitionId)))
                    .iterator()
                    .next()
                    .getLong("total");
        }

        /** Moves a row to a status that the public operations cannot reach for it. */
        void forceStatus(DefinitionKey key, DefinitionStatus status) {
            await(admin().preparedQuery("""
                            UPDATE workflow_definitions SET status = $4
                            WHERE tenant_key IS NOT DISTINCT FROM $1 AND definition_id = $2 AND definition_version = $3
                            """)
                    .execute(Tuple.of(key.tenantKey(), key.definitionId(), key.definitionVersion(), status.name())));
        }

        Stored stored(String tenantKey, String definitionId, long version) {
            List<Stored> rows = new ArrayList<>();
            for (Row row : await(admin().preparedQuery("""
                            SELECT definition_version, status, plan_hash, document, document_format, source_type,
                                   source_uri, source_revision, loaded_at, status_changed_at
                            FROM workflow_definitions
                            WHERE tenant_key IS NOT DISTINCT FROM $1 AND definition_id = $2
                              AND definition_version = $3
                            """).execute(Tuple.of(tenantKey, definitionId, version)))) {
                rows.add(new Stored(
                        row.getLong("definition_version"),
                        row.getString("status"),
                        row.getString("plan_hash"),
                        row.getString("document"),
                        row.getString("document_format"),
                        row.getString("source_type"),
                        row.getString("source_uri"),
                        row.getString("source_revision"),
                        row.getOffsetDateTime("loaded_at"),
                        row.getOffsetDateTime("status_changed_at")));
            }
            assertThat(rows).as("rows for %s v%s", definitionId, version).hasSize(1);
            return rows.getFirst();
        }

        @Override
        public void close() {
            AssertionError failure = null;
            while (!cleanups.isEmpty()) {
                try {
                    cleanups.pop().run();
                } catch (Throwable cleanupFailure) {
                    if (failure == null) failure = new AssertionError("Scenario cleanup failed", cleanupFailure);
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
