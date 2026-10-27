// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.documentText;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.ActivationStatus;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Stored;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a retired definition returns to service only when its executable source is available and no other
 * tenant holds its id, and that the reinstatement is one atomic durable change with a local publication that is
 * compensated, and only its own, when the change fails.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionReinstatementIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    @WorkflowContract
    public interface ReinstatedCodeV1 {}

    @WorkflowContract
    public interface MissingCodeV2 {}

    @WorkflowContract
    public interface DriftCodeV1 {}

    @WorkflowContract
    public interface OwnerDocV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface ReinstatedDocV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface DriftDocV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface Rollback {}

    @WorkflowContract
    public interface Preserved {}

    @WorkflowContract
    public interface StaleDocV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface IsolationDocV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface ReconcileIsolationDocV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface StoreOwnerDocV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface StorePairsDocV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface LowerDoc extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_reinstatement_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("a retired code version whose code is still deployed returns to service and can be retired again")
    void reinstatesCodeAfterRestartUnregisteredIt(Vertx vertx) {
        String id = "reinstatement-it-code";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        WorkflowDefinition<?, ?> deployed = code(id, 1, ReinstatedCodeV1.class, "done");
        String hash = planHashOf(deployed);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a retired code row whose code the application still deploys
            scenario.seedCode(id, 1, hash, "RETIRED");

            // And a restart, after which the retired version is still a retired row
            Boot restarted = scenario.node(none(), unobserved(), List.of(deployed));
            WorkflowRegistry registry = restarted.app().workflowRegistry();
            Stored retired = scenario.stored(id, 1);
            assertThat(retired.status()).isEqualTo("RETIRED");
            WorkflowDefinitionService service = restarted.app().definitionService();

            // When the version is reinstated through the service
            await(service.reinstate(key));

            // Then the row is recomputed to the active version, with its code identity and hash unchanged
            Stored reinstated = scenario.stored(id, 1);
            assertThat(reinstated.status()).isEqualTo("AVAILABLE");
            assertThat(reinstated)
                    .usingRecursiveComparison()
                    .ignoringFields("status", "changedAt")
                    .isEqualTo(retired);
            assertThat(reinstated.planHash()).isEqualTo(hash);

            // And pinned resolution serves the deployed code, with the registered definition restored
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, id, 1))
                            .plan()
                            .planHash())
                    .isEqualTo(hash);

            // And the version is activated, because reinstating a version does not make it current
            await(service.activate(WorkflowScopes.DEFAULT, id, WorkflowScopes.DEFAULT, 1));

            // When a new instance is started through the workflow operations
            WorkflowInstanceId started = await(restarted
                    .app()
                    .workflowOperations()
                    .start(new StartCommand(
                            id,
                            ReinstatedCodeV1.class.getName(),
                            new CodeStart("reinstated"),
                            "reinstated-start",
                            null,
                            null,
                            null,
                            null,
                            null)));
            await(restarted.app().executionProbe().awaitIdle(started, IDLE_TIMEOUT));
            WorkflowView view = await(restarted.app().workflowOperations().query(started));

            // Then it ran on the reinstated version to a terminal status, so no instance is pinned afterwards
            assertThat(view.instance().status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(view.instance().definitionVersion()).isEqualTo(1);
            assertThat(view.instance().planHash()).isEqualTo(hash);
            assertThat(scenario.instanceCount(id)).isEqualTo(1);
            assertThat(scenario.livePinCount(id, 1, hash)).isZero();

            // When the same component deprecates the version, its cooling window passes by the database clock and it
            // is retired with no pin
            long coolingSeconds = restarted.app().workflowDefinitionsConfig().maxStalenessSeconds();
            await(service.deactivateDefault(id));
            await(service.deprecate(key));
            assertThat(scenario.stored(id, 1).status()).isEqualTo("DEPRECATED");
            scenario.ageStatus(key, coolingSeconds + 60);
            await(service.retire(key));

            // Then the removal succeeds and the row is retired
            assertThat(scenario.stored(id, 1).status()).isEqualTo("RETIRED");
        }
    }

    @Test
    @DisplayName("reinstating is refused without the deployed code and is independent of the id in other scopes")
    void refusesMissingCodeAndIgnoresTheSameIdInAnotherScope(Vertx vertx) {
        String missingId = "reinstatement-it-missing-code";
        String ownerId = "reinstatement-it-owner-doc";
        DefinitionKey missingKey = new DefinitionKey(WorkflowScopes.DEFAULT, missingId, 1);
        DefinitionKey tenantOneKey = new DefinitionKey("tenant-one", ownerId, 1);
        DefinitionKey tenantTwoKey = new DefinitionKey("tenant-two", ownerId, 1);
        WorkflowDefinition<?, ?> deployedNeighbour = code(missingId, 2, MissingCodeV2.class, "done");
        String neighbourHash = planHashOf(deployedNeighbour);
        Instant loadedAt = Instant.parse("2026-10-19T09:00:00Z");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(missingId, ownerId);

            // Given a retired code version that no deployed code provides, next to an active deployed version of
            // the same id
            scenario.seedCode(missingId, 1, "a".repeat(64), "RETIRED");
            scenario.seedCode(missingId, 2, neighbourHash, "AVAILABLE");
            Boot node = scenario.node(none(), unobserved(), List.of(deployedNeighbour));
            WorkflowRegistry registry = node.app().workflowRegistry();
            WorkflowDefinitionService service = node.app().definitionService();
            Stored missingBefore = scenario.stored(missingId, 1);
            Stored neighbourBefore = scenario.stored(missingId, 2);

            // When the retired version is reinstated
            Throwable noCode = failureOf(service.reinstate(missingKey));

            // Then it fails typed as unavailable code, naming the key
            assertThat(noCode).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.CODE_UNAVAILABLE);
                assertThat(failure.key()).isEqualTo(missingKey);
                assertThat(failure.getMessage()).contains(missingId);
            });

            // And neither the rows nor the registry changed
            assertThat(scenario.stored(missingId, 1)).isEqualTo(missingBefore);
            assertThat(scenario.stored(missingId, 2)).isEqualTo(neighbourBefore);

            // Given a tenant that retired the id and a second tenant that has since published the same id
            long coolingSeconds = node.app().workflowDefinitionsConfig().maxStalenessSeconds();
            TenantRowSeed.seed(
                    node.app(),
                    "tenant-one",
                    documentBytes(ownerId, 1, OwnerDocV1.class),
                    source("tenant-one", loadedAt),
                    DefinitionStatus.CANDIDATE);
            await(service.publish(tenantOneKey));
            await(service.deprecate(tenantOneKey));
            scenario.ageStatus(tenantOneKey, coolingSeconds + 60);
            await(service.retire(tenantOneKey));
            TenantRowSeed.seed(
                    node.app(),
                    "tenant-two",
                    documentBytes(ownerId, 1, OwnerDocV1.class),
                    source("tenant-two", loadedAt),
                    DefinitionStatus.CANDIDATE);
            await(service.publish(tenantTwoKey));
            Stored retiredBefore = scenario.stored("tenant-one", ownerId, 1);
            Stored holderBefore = scenario.stored("tenant-two", ownerId, 1);
            assertThat(retiredBefore.status()).isEqualTo("RETIRED");
            assertThat(holderBefore.status()).isEqualTo("AVAILABLE");
            RuntimeWorkflow holderRuntime = DefinitionScenario.planOf(registry, tenantTwoKey);

            // When the first tenant's retired version is reinstated
            await(service.reinstate(tenantOneKey));

            // Then it is available again beside the second tenant's version of the same id
            assertThat(scenario.stored("tenant-one", ownerId, 1).status()).isEqualTo("AVAILABLE");
            assertThat(scenario.stored("tenant-one", ownerId, 1))
                    .usingRecursiveComparison()
                    .ignoringFields("status", "changedAt")
                    .isEqualTo(retiredBefore);

            // And the second tenant's row and runtime are untouched, and each tenant resolves its own runtime
            assertThat(scenario.stored("tenant-two", ownerId, 1)).isEqualTo(holderBefore);
            assertThat(DefinitionScenario.planOf(registry, tenantTwoKey)).isSameAs(holderRuntime);
            assertThat(DefinitionScenario.planOf(registry, tenantOneKey))
                    .isNotNull()
                    .isNotSameAs(holderRuntime);
        }
    }

    @Test
    @DisplayName("a retired code version whose stored hash differs from the deployed code's hash fails as drift")
    void refusesCodeWhoseStoredHashDiffersFromDeployedCode(Vertx vertx) {
        String id = "reinstatement-it-drift-code";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        WorkflowDefinition<?, ?> deployed = code(id, 1, DriftCodeV1.class, "done");
        String deployedHash = planHashOf(deployed);
        String storedHash = "b".repeat(64);
        assertThat(deployedHash)
                .as("the stored hash must differ from the deployed code's hash for the drift case")
                .isNotEqualTo(storedHash);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a retired code row, and a restart after which the retired version is not registered
            scenario.seedCode(id, 1, deployedHash, "RETIRED");
            Boot restarted = scenario.node(none(), unobserved(), List.of(deployed));
            WorkflowRegistry registry = restarted.app().workflowRegistry();
            WorkflowDefinitionService service = restarted.app().definitionService();
            assertThat(scenario.stored(id, 1).planHash()).isEqualTo(deployedHash);

            // And the row's stored hash then changes to one that is not the hash of the code this node deploys
            scenario.execute("""
                    UPDATE workflow_definitions SET plan_hash = $2
                    WHERE definition_id = $1 AND definition_version = 1
                    """, Tuple.of(id, storedHash));
            Stored before = scenario.stored(id, 1);
            assertThat(before.status()).isEqualTo("RETIRED");
            assertThat(before.planHash()).isEqualTo(storedHash);
            ActivationStatus cacheBefore = ManagedDefinitionDatabase.currentStatus(restarted.app(), id, 1);

            // When the version is reinstated
            Throwable drifted = failureOf(service.reinstate(key));

            // Then it fails typed as drift naming the key and both hashes
            assertThat(drifted).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                assertThat(failure.key()).isEqualTo(key);
                assertThat(failure.getMessage())
                        .contains(id)
                        .contains(storedHash)
                        .contains(deployedHash);
            });

            // And the row is still retired, unchanged, and the cached status is as it was
            assertThat(scenario.stored(id, 1)).isEqualTo(before);
            assertThat(ManagedDefinitionDatabase.currentStatus(restarted.app(), id, 1))
                    .isEqualTo(cacheBefore);
        }
    }

    @Test
    @DisplayName("a retired document returns to service from its stored text, and a text that no longer matches fails")
    void reinstatesDocumentOrFailsClosedOnDrift(Vertx vertx) {
        String id = "reinstatement-it-doc";
        String driftId = "reinstatement-it-drift-doc";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        DefinitionKey driftKey = new DefinitionKey(WorkflowScopes.DEFAULT, driftId, 1);
        Instant loadedAt = Instant.parse("2026-10-19T09:00:00Z");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id, driftId);

            // Given a document that a first node loaded, activated, deprecated, cooled and retired
            Boot first = scenario.node(none());
            WorkflowDefinitionService firstService = first.app().definitionService();
            long coolingSeconds = first.app().workflowDefinitionsConfig().maxStalenessSeconds();
            String loadedHash = await(firstService.load(
                            WorkflowScopes.DEFAULT,
                            documentBytes(id, 1, ReinstatedDocV1.class),
                            DocumentFormat.YAML,
                            source("retired-doc", loadedAt)))
                    .planHash();
            await(firstService.publish(key));
            await(firstService.deprecate(key));
            scenario.ageStatus(key, coolingSeconds + 60);
            await(firstService.retire(key));
            assertThat(scenario.stored(id, 1).status()).isEqualTo("RETIRED");
            assertThat(scenario.stored(id, 1).planHash()).isEqualTo(loadedHash);

            // And, for the same restart, a retired document whose stored hash is not the hash of its stored text
            String driftText = documentText(driftId, 1, DriftDocV1.class);
            String storedDriftHash = "f".repeat(64);
            String compiledDriftHash = first.app()
                    .definitionPipeline()
                    .load(new DefinitionResource(
                            driftText.getBytes(StandardCharsets.UTF_8),
                            DocumentFormat.YAML,
                            source("drift-probe", loadedAt)))
                    .planHash();
            assertThat(compiledDriftHash).matches("[0-9a-f]{64}");
            assertThat(compiledDriftHash)
                    .as("the stored hash must differ from the hash of the stored text for the drift case")
                    .isNotEqualTo(storedDriftHash);
            scenario.seedDocument(WorkflowScopes.DEFAULT, driftId, 1, storedDriftHash, "RETIRED", driftText);
            first.close();

            // And a restart, after which neither retired document is registered
            Boot restarted = scenario.node(none());
            WorkflowRegistry registry = restarted.app().workflowRegistry();
            WorkflowDefinitionService service = restarted.app().definitionService();
            Stored retired = scenario.stored(id, 1);
            Stored driftBefore = scenario.stored(driftId, 1);
            assertThat(retired.status()).isEqualTo("RETIRED");
            assertThat(driftBefore.status()).isEqualTo("RETIRED");
            assertThat(ManagedDefinitionDatabase.currentStatus(restarted.app(), id, 1))
                    .isEqualTo(ActivationStatus.RETIRED);
            ActivationStatus driftCacheBefore = ManagedDefinitionDatabase.currentStatus(restarted.app(), driftId, 1);

            // When the document whose stored text no longer compiles to the stored hash is reinstated
            Throwable drifted = failureOf(service.reinstate(driftKey));

            // Then it fails typed as drift naming the key and both hashes, without the document text
            assertThat(drifted).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.DRIFT);
                assertThat(failure.key()).isEqualTo(driftKey);
                assertThat(failure.getMessage())
                        .contains(driftId)
                        .contains(storedDriftHash)
                        .contains(compiledDriftHash)
                        .doesNotContain(driftText);
            });

            // And it stays retired, unregistered and in the cache as it was
            assertThat(scenario.stored(driftId, 1)).isEqualTo(driftBefore);
            assertThat(ManagedDefinitionDatabase.currentStatus(restarted.app(), driftId, 1))
                    .isEqualTo(driftCacheBefore);

            // When the document with the matching hash is reinstated
            await(service.reinstate(key));

            // Then its row is active again with the stored text and hash unchanged
            Stored reinstated = scenario.stored(id, 1);
            assertThat(reinstated.status()).isEqualTo("AVAILABLE");
            assertThat(reinstated)
                    .usingRecursiveComparison()
                    .ignoringFields("status", "changedAt")
                    .isEqualTo(retired);

            // And the membership and the cache are restored
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, id, 1))
                            .plan()
                            .planHash())
                    .isEqualTo(loadedHash);
            assertThat(ManagedDefinitionDatabase.currentStatus(restarted.app(), id, 1))
                    .isEqualTo(ActivationStatus.AVAILABLE);

            // When the same component deprecates it, its cooling window passes by the database clock and it is
            // retired with no pin
            assertThat(scenario.instanceCount(id)).isZero();
            await(service.deprecate(key));
            scenario.ageStatus(key, coolingSeconds + 60);
            await(service.retire(key));

            // Then the removal succeeds and the row is retired
            assertThat(scenario.stored(id, 1).status()).isEqualTo("RETIRED");
        }
    }

    @Test
    @DisplayName("a reinstatement whose database change fails leaves the old rows and plans as they were")
    void rollsBackReinstateWhoseChangeFails(Vertx vertx) {
        String id = "reinstatement-it-rollback";
        String preservedId = "reinstatement-it-preserved";
        String promotionMarker = "reinstatement-promotion-gate";
        String restoreMarker = "reinstatement-restore-gate";
        DefinitionKey v1 = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        DefinitionKey v2 = new DefinitionKey(WorkflowScopes.DEFAULT, id, 2);
        DefinitionKey v3 = new DefinitionKey(WorkflowScopes.DEFAULT, id, 3);
        DefinitionKey preservedV1 = new DefinitionKey(WorkflowScopes.DEFAULT, preservedId, 1);
        WorkflowDefinition<?, ?> codeV1 = code(id, 1, Rollback.class, "done");
        WorkflowDefinition<?, ?> codeV2 = code(id, 2, Rollback.class, "done");
        WorkflowDefinition<?, ?> codeV3 = code(id, 3, Rollback.class, "done");
        WorkflowDefinition<?, ?> preservedCodeV1 = code(preservedId, 1, Preserved.class, "done");
        WorkflowDefinition<?, ?> preservedCodeV2 = code(preservedId, 2, Preserved.class, "done");
        String hash1 = planHashOf(codeV1);
        String hash2 = planHashOf(codeV2);
        String hash3 = planHashOf(codeV3);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id, preservedId);

            // Given version 2 active and versions 1 and 3 retired, all provided by the deployed code
            scenario.seedCode(id, 1, hash1, "RETIRED");
            scenario.seedCode(id, 2, hash2, "AVAILABLE");
            scenario.seedCode(id, 3, hash3, "RETIRED");

            // And a second id whose version 1 is superseded under an active version 2
            scenario.seedCode(preservedId, 1, planHashOf(preservedCodeV1), "AVAILABLE");
            scenario.seedCode(preservedId, 2, planHashOf(preservedCodeV2), "AVAILABLE");

            // And a node whose database access is observed
            WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
            Boot boot = scenario.attempt(
                    none(),
                    unobserved(),
                    List.of(codeV1, codeV2, codeV3, preservedCodeV1, preservedCodeV2),
                    observer,
                    10);
            assertThat(boot.failure()).as("startup with retired code versions").isNull();
            WorkflowRegistry registry = boot.app().workflowRegistry();
            WorkflowDefinitionService service = boot.app().definitionService();
            TransactionalWorkflowOperations<SqlClient> operations = boot.app().transactionalOperations();
            RuntimeWorkflow activeRuntime =
                    DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, id, 2));
            assertThat(scenario.stored(id, 3).status()).isEqualTo("RETIRED");

            // When the lower retired version is reinstated
            await(service.reinstate(v1));

            // Then it is available beside the higher version
            assertThat(scenario.stored(id, 1).status()).isEqualTo("AVAILABLE");
            assertThat(scenario.stored(id, 2).status()).isEqualTo("AVAILABLE");
            assertThat(scenario.stored(id, 3).status()).isEqualTo("RETIRED");
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, id, 2)))
                    .isSameAs(activeRuntime);
            RuntimeWorkflow lowerRuntime =
                    DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, id, 1));
            assertThat(lowerRuntime.plan().planHash()).isEqualTo(hash1);

            // Given a database fault that fails the change of version 3 from retired to available
            installReinstatementFailure(scenario, id, 3, promotionMarker);
            Stored lowerBefore = scenario.stored(id, 1);
            Stored activeBefore = scenario.stored(id, 2);
            Stored higherBefore = scenario.stored(id, 3);
            WorkflowDefinitionSqlObserver.CompletionFailureGate gate =
                    observer.holdNextTransactionFailure(promotionMarker);
            scenario.onClose(gate::release);

            // And an admission transaction that holds the shared admission lock of the id
            Tx racer = Tx.begin(scenario, id);

            // When the higher version is reinstated while that admission is open
            Future<Void> failing = service.reinstate(v3);
            scenario.awaitAdvisory(id, "ExclusiveLock", false, 1);

            // Then the reinstatement waits behind the admission, with its local publication already visible
            assertThat(failing.isComplete()).isFalse();
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, id, 3))
                            .plan()
                            .planHash())
                    .isEqualTo(hash3);

            // And the admission still sees only the committed, retired row and is refused
            Throwable raced =
                    failureOf(operations.start(startCommand(id, Rollback.class, "race-v3", 3), racer.connection()));
            assertThat(raced).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.key()).isEqualTo(v3);
            });
            racer.rollback();

            // When the change of version 3 fails
            Throwable atGate = await(gate.observed());

            // Then the database raised the injected fault
            assertThat(causeContains(atGate, promotionMarker))
                    .as("the fault must come from the change of version 3")
                    .isTrue();

            // And the old active row and the retired rows are restored while the failure is still held
            assertThat(failing.isComplete()).isFalse();
            assertThat(scenario.stored(id, 1)).isEqualTo(lowerBefore);
            assertThat(scenario.stored(id, 2)).isEqualTo(activeBefore);
            assertThat(scenario.stored(id, 3)).isEqualTo(higherBefore);

            // And admission still sees only committed eligibility: the retired version is refused although its
            // publication is still local, and the active version is admitted
            Tx refusedAdmission = Tx.begin(scenario, null);
            Throwable stillRetired = failureOf(
                    operations.start(startCommand(id, Rollback.class, "gate-v3", 3), refusedAdmission.connection()));
            refusedAdmission.rollback();
            assertThat(stillRetired).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.key()).isEqualTo(v3);
            });
            Tx admitted = Tx.begin(scenario, null);
            WorkflowInstanceId admittedId =
                    await(operations.start(startCommand(id, Rollback.class, "gate-v2", 2), admitted.connection()));
            admitted.commit();
            await(boot.app().executionProbe().awaitIdle(admittedId, IDLE_TIMEOUT));
            assertThat(await(boot.app().workflowOperations().query(admittedId))
                            .instance()
                            .definitionVersion())
                    .isEqualTo(2);

            // When the failure is released
            gate.release();
            Throwable failed = failureOf(failing);

            // Then the reinstatement fails with the database fault
            assertThat(failed).isNotNull();
            assertThat(causeContains(failed, promotionMarker)).isTrue();

            // And the rows are exactly as before the attempt
            assertThat(scenario.stored(id, 1)).isEqualTo(lowerBefore);
            assertThat(scenario.stored(id, 2)).isEqualTo(activeBefore);
            assertThat(scenario.stored(id, 3)).isEqualTo(higherBefore);

            // And the plans of the other versions are the ones the node already held
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, id, 2)))
                    .isSameAs(activeRuntime);
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                    .isSameAs(lowerRuntime);

            // When the fault is gone and the higher version is reinstated again
            removeFailure(scenario, "fail_test_reinstate_promotion");
            await(service.reinstate(v3));

            // Then the higher version is available beside versions 1 and 2
            assertThat(scenario.stored(id, 3).status()).isEqualTo("AVAILABLE");
            assertThat(scenario.stored(id, 2).status()).isEqualTo("AVAILABLE");
            assertThat(scenario.stored(id, 1).status()).isEqualTo("AVAILABLE");
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, id, 3))
                            .plan()
                            .planHash())
                    .isEqualTo(hash3);
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, id, 2)))
                    .isSameAs(activeRuntime);

            // And admission now accepts the committed version
            Tx afterCommit = Tx.begin(scenario, null);
            WorkflowInstanceId pinned =
                    await(operations.start(startCommand(id, Rollback.class, "after-v3", 3), afterCommit.connection()));
            afterCommit.commit();
            await(boot.app().executionProbe().awaitIdle(pinned, IDLE_TIMEOUT));
            assertThat(await(boot.app().workflowOperations().query(pinned))
                            .instance()
                            .definitionVersion())
                    .isEqualTo(3);

            // Given another node retired version 1 of the second id while this node still serves its registration
            RuntimeWorkflow preservedRuntime =
                    DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, preservedId, 1));
            scenario.execute("""
                    UPDATE workflow_definitions
                    SET status = 'RETIRED', status_changed_at = clock_timestamp()
                    WHERE definition_id = $1 AND definition_version = 1
                    """, Tuple.of(preservedId));
            Stored preservedRetiredBefore = scenario.stored(preservedId, 1);
            Stored preservedActiveBefore = scenario.stored(preservedId, 2);
            assertThat(preservedRetiredBefore.status()).isEqualTo("RETIRED");
            installRestoreFailure(scenario, preservedId, 1, restoreMarker);

            // When this node reinstates it and the database fails the change
            Throwable preservedFailure = failureOf(service.reinstate(preservedV1));

            // Then the reinstatement fails with the database fault and both rows are as they were
            assertThat(preservedFailure).isNotNull();
            assertThat(causeContains(preservedFailure, restoreMarker)).isTrue();
            assertThat(scenario.stored(preservedId, 1)).isEqualTo(preservedRetiredBefore);
            assertThat(scenario.stored(preservedId, 2)).isEqualTo(preservedActiveBefore);

            // And the registration that existed before the attempt is preserved, with its registered definition
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, preservedId, 1)))
                    .isSameAs(preservedRuntime);
        }
    }

    @Test
    @DisplayName("a claim another scope holds for the id never blocks a reinstatement")
    void aClaimInAnotherScopeDoesNotBlockReinstatement(Vertx vertx) {
        String id = "reinstatement-it-stale-doc";
        DefinitionKey tenantOneKey = new DefinitionKey("tenant-one", id, 1);
        DefinitionKey tenantTwoKey = new DefinitionKey("tenant-two", id, 1);
        Instant loadedAt = Instant.parse("2026-10-19T09:00:00Z");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);
            Boot node = scenario.node(none());
            WorkflowRegistry registry = node.app().workflowRegistry();
            WorkflowDefinitionService service = node.app().definitionService();
            long coolingSeconds = node.app().workflowDefinitionsConfig().maxStalenessSeconds();

            // Given a tenant that retired the id
            byte[] staleDocument = documentBytes(id, 1, StaleDocV1.class);
            TenantRowSeed.seed(
                    node.app(),
                    "tenant-one",
                    staleDocument,
                    source("tenant-one", loadedAt),
                    DefinitionStatus.CANDIDATE);
            String loadedHash = node.app()
                    .definitionPipeline()
                    .load(new DefinitionResource(staleDocument, DocumentFormat.YAML, source("tenant-one", loadedAt)))
                    .planHash();
            await(service.publish(tenantOneKey));
            await(service.deprecate(tenantOneKey));
            scenario.ageStatus(tenantOneKey, coolingSeconds + 60);
            await(service.retire(tenantOneKey));
            Stored retiredBefore = scenario.stored("tenant-one", id, 1);
            assertThat(retiredBefore.status()).isEqualTo("RETIRED");

            // And a second tenant that has just claimed the id for a publication that nobody completed
            scenario.seedDocument(
                    "tenant-two", id, 1, "e".repeat(64), "PUBLISHING", documentText(id, 1, StaleDocV1.class));
            Stored claimBefore = scenario.stored("tenant-two", id, 1);
            assertThat(claimBefore.status()).isEqualTo("PUBLISHING");

            // When the first tenant's version is reinstated
            await(service.reinstate(tenantOneKey));

            // Then it is available again, from its stored text, and the other tenant's claim is untouched
            Stored reinstated = scenario.stored("tenant-one", id, 1);
            assertThat(reinstated.status()).isEqualTo("AVAILABLE");
            assertThat(reinstated)
                    .usingRecursiveComparison()
                    .ignoringFields("status", "changedAt")
                    .isEqualTo(retiredBefore);
            assertThat(DefinitionScenario.planOf(registry, tenantOneKey).plan().planHash())
                    .isEqualTo(loadedHash);
            assertThat(scenario.stored("tenant-two", id, 1)).isEqualTo(claimBefore);
        }
    }

    @Test
    @DisplayName("the store reinstates only deprecated and retired rows and refuses every other change")
    void storeRefusesUnsupportedPairsAndRowsThatAreNotRetired(Vertx vertx) {
        String id = "reinstatement-it-store-pairs";
        String hash = "d".repeat(64);
        String text = documentText(id, 1, StorePairsDocV1.class);
        DefinitionKey retired = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        record Pair(DefinitionStatus from, DefinitionStatus to) {}

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);
            WorkflowDefinitionRegistryStore store = scenario.node(none()).app().definitionRegistryStore();

            // Given one retired version, and one version in each other status that a reinstatement could meet
            scenario.seedDocument(WorkflowScopes.DEFAULT, id, 1, hash, "RETIRED", text);
            scenario.seedDocument(WorkflowScopes.DEFAULT, id, 2, hash, "AVAILABLE", text);
            scenario.seedDocument(WorkflowScopes.DEFAULT, id, 3, hash, "PUBLISHING", text);
            scenario.seedDocument(WorkflowScopes.DEFAULT, id, 4, hash, "AVAILABLE", text);
            scenario.seedDocument(WorkflowScopes.DEFAULT, id, 5, hash, "CANDIDATE", text);
            List<Stored> before = List.of(
                    scenario.stored(id, 1),
                    scenario.stored(id, 2),
                    scenario.stored(id, 3),
                    scenario.stored(id, 4),
                    scenario.stored(id, 5));

            // When status pairs other than the supported ones are requested on the retired version
            List<Pair> pairs = List.of(
                    new Pair(DefinitionStatus.AVAILABLE, DefinitionStatus.AVAILABLE),
                    new Pair(DefinitionStatus.CANDIDATE, DefinitionStatus.AVAILABLE),
                    new Pair(DefinitionStatus.PUBLISHING, DefinitionStatus.AVAILABLE),
                    new Pair(DefinitionStatus.RETIRED, DefinitionStatus.DEPRECATED),
                    new Pair(DefinitionStatus.RETIRED, DefinitionStatus.RETIRED),
                    new Pair(DefinitionStatus.RETIRED, DefinitionStatus.CANDIDATE));
            for (Pair pair : pairs) {
                Throwable refused = failureOf(store.transition(retired, pair.from(), pair.to()));

                // Then each is refused as an invalid transition naming the key, and the version stays retired
                assertThat(refused)
                        .as("transition %s to %s", pair.from(), pair.to())
                        .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                            assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                            assertThat(failure.key()).isEqualTo(retired);
                        });
                assertThat(scenario.stored(id, 1)).isEqualTo(before.get(0));
            }

            // When the retired to available change is requested on each row that is not retired
            for (int version = 2; version <= 5; version++) {
                DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, version);
                String found = before.get(version - 1).status();
                Throwable refused =
                        failureOf(store.transition(key, DefinitionStatus.RETIRED, DefinitionStatus.AVAILABLE));

                // Then it is refused as an invalid transition that names the status found, and the row is unchanged
                assertThat(refused)
                        .as("row in status %s", found)
                        .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                            assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                            assertThat(failure.key()).isEqualTo(key);
                            assertThat(failure.getMessage()).contains("found " + found);
                        });
                assertThat(scenario.stored(id, version)).isEqualTo(before.get(version - 1));
            }

            // When the change is requested on a version that does not exist
            DefinitionKey missing = new DefinitionKey(WorkflowScopes.DEFAULT, id, 99);
            Throwable absent =
                    failureOf(store.transition(missing, DefinitionStatus.RETIRED, DefinitionStatus.AVAILABLE));

            // Then it is refused as an invalid transition naming the key, and no row appeared
            assertThat(absent).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(failure.key()).isEqualTo(missing);
            });
            assertThat(scenario.findStored(id, 99)).isEmpty();
            assertThat(scenario.rowCount(id)).isEqualTo(5);

            // When the retired version is reinstated directly through the store
            await(store.transition(retired, DefinitionStatus.RETIRED, DefinitionStatus.AVAILABLE));

            // Then the retired version is available again and no other row changed
            assertThat(scenario.stored(id, 1).status()).isEqualTo("AVAILABLE");
            for (int version = 2; version <= 5; version++) {
                assertThat(scenario.stored(id, version)).isEqualTo(before.get(version - 1));
            }
        }
    }

    @Test
    @DisplayName("the store reinstates a retired row whatever the rows of other scopes hold for the id")
    void storeReinstatesWhateverOtherScopesHoldForTheId(Vertx vertx) {
        String id = "reinstatement-it-store-owner";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        DefinitionKey holderKey = new DefinitionKey("tenant-two", id, 1);
        String hash = "d".repeat(64);
        String text = documentText(id, 1, StoreOwnerDocV1.class);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);
            AppComponent app = scenario.node(none()).app();
            WorkflowDefinitionRegistryStore store = app.definitionRegistryStore();
            long coolingSeconds = app.workflowDefinitionsConfig().maxStalenessSeconds();

            for (String holding : List.of("AVAILABLE", "DEPRECATED", "PUBLISHING", "STALE PUBLISHING")) {
                // Given a retired global document and a second tenant that holds the id in the status under test
                scenario.seedDocument(WorkflowScopes.DEFAULT, id, 1, hash, "RETIRED", text);
                scenario.seedDocument("tenant-two", id, 1, hash, holding.replace("STALE ", ""), text);
                if (holding.startsWith("STALE")) scenario.ageStatus(holderKey, 2 * coolingSeconds + 60);
                Stored holderBefore = scenario.stored("tenant-two", id, 1);

                // When the store is asked to reinstate the retired version, bypassing the lifecycle
                await(store.transition(key, DefinitionStatus.RETIRED, DefinitionStatus.AVAILABLE));

                // Then the version is back in service and the other tenant's row is untouched
                assertThat(scenario.stored(id, 1).status())
                        .as("holder %s", holding)
                        .isEqualTo("AVAILABLE");
                assertThat(scenario.stored("tenant-two", id, 1)).isEqualTo(holderBefore);
                scenario.execute("""
                        DELETE FROM workflow_definition_activations WHERE definition_id = $1
                        """, Tuple.of(id));
                scenario.execute("""
                        DELETE FROM workflow_definitions WHERE definition_id = $1
                        """, Tuple.of(id));
            }
        }
    }

    @Test
    @DisplayName("a reinstated lower document version is available beside the higher version")
    void reinstatedLowerDocumentIsAvailableBesideTheHigherVersion(Vertx vertx) {
        String id = "reinstatement-it-lower-doc";
        DefinitionKey v1 = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        DefinitionKey v2 = new DefinitionKey(WorkflowScopes.DEFAULT, id, 2);
        Instant loadedAt = Instant.parse("2026-10-19T09:00:00Z");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);
            Boot node = scenario.node(none());
            WorkflowRegistry registry = node.app().workflowRegistry();
            WorkflowDefinitionService service = node.app().definitionService();
            long coolingSeconds = node.app().workflowDefinitionsConfig().maxStalenessSeconds();

            // Given version 1 retired and version 2 active, both documents
            String hash1 = await(service.load(
                            WorkflowScopes.DEFAULT,
                            documentBytes(id, 1, LowerDoc.class),
                            DocumentFormat.YAML,
                            source("lower-v1", loadedAt)))
                    .planHash();
            await(service.publish(v1));
            await(service.load(
                    WorkflowScopes.DEFAULT,
                    documentBytes(id, 2, LowerDoc.class),
                    DocumentFormat.YAML,
                    source("lower-v2", loadedAt)));
            await(service.publish(v2));
            await(service.deprecate(v1));
            scenario.ageStatus(v1, coolingSeconds + 60);
            await(service.retire(v1));
            RuntimeWorkflow activeRuntime =
                    DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, id, 2));
            assertThat(scenario.stored(id, 1).status()).isEqualTo("RETIRED");
            assertThat(scenario.stored(id, 2).status()).isEqualTo("AVAILABLE");

            // When the lower version is reinstated
            await(service.reinstate(v1));

            // Then it is available beside the higher version
            assertThat(scenario.stored(id, 1).status()).isEqualTo("AVAILABLE");
            assertThat(scenario.stored(id, 2).status()).isEqualTo("AVAILABLE");
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, id, 2)))
                    .isSameAs(activeRuntime);

            // And the lower version is served again from its stored text, and the cache agrees with the table
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, id, 1))
                            .plan()
                            .planHash())
                    .isEqualTo(hash1);
            assertThat(ManagedDefinitionDatabase.currentStatus(node.app(), id, 1))
                    .isEqualTo(ActivationStatus.AVAILABLE);
            assertThat(ManagedDefinitionDatabase.currentStatus(node.app(), id, 2))
                    .isEqualTo(ActivationStatus.AVAILABLE);
        }
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }

    private static BootObservation unobserved() {
        return new BootObservation("reinstatement-it-unused", 1, "unused", false);
    }

    private static byte[] documentBytes(String definitionId, long version, Class<?> contract) {
        return documentText(definitionId, version, contract).getBytes(StandardCharsets.UTF_8);
    }

    private static StartCommand startCommand(
            String definitionId, Class<?> contract, String idempotencyKey, long version) {
        return new StartCommand(
                definitionId,
                contract.getName(),
                new CodeStart(idempotencyKey),
                idempotencyKey,
                null,
                null,
                version,
                null,
                null);
    }

    private static boolean causeContains(Throwable failure, String marker) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(marker)) return true;
        }
        return false;
    }

    /**
     * Fails the promotion of one version to active, and only when the version it replaces has already been demoted
     * inside the same transaction. A promotion that precedes the demotion raises a different message.
     */
    private static void installReinstatementFailure(
            DefinitionScenario scenario, String definitionId, long version, String marker) {
        removeFailure(scenario, "fail_test_reinstate_promotion");
        scenario.onClose(() -> removeFailure(scenario, "fail_test_reinstate_promotion"));
        await(scenario.admin()
                .query("""
                CREATE FUNCTION fail_test_reinstate_promotion() RETURNS trigger AS $$
                BEGIN
                    IF NEW.definition_id = '%s' AND NEW.definition_version = %d
                       AND OLD.status = 'RETIRED' AND NEW.status = 'AVAILABLE' THEN
                        RAISE EXCEPTION '%s' USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(definitionId, version, marker))
                .execute());
        await(scenario.admin().query("""
                CREATE TRIGGER fail_test_reinstate_promotion
                BEFORE UPDATE ON workflow_definitions
                FOR EACH ROW EXECUTE FUNCTION fail_test_reinstate_promotion()
                """).execute());
    }

    /** Fails the first change of a retired version back to a live status. */
    private static void installRestoreFailure(
            DefinitionScenario scenario, String definitionId, long version, String marker) {
        removeFailure(scenario, "fail_test_reinstate_restore");
        scenario.onClose(() -> removeFailure(scenario, "fail_test_reinstate_restore"));
        await(scenario.admin()
                .query("""
                CREATE FUNCTION fail_test_reinstate_restore() RETURNS trigger AS $$
                BEGIN
                    IF NEW.definition_id = '%s' AND NEW.definition_version = %d
                       AND OLD.status = 'RETIRED' AND NEW.status <> 'RETIRED' THEN
                        RAISE EXCEPTION '%s' USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(definitionId, version, marker))
                .execute());
        await(scenario.admin().query("""
                CREATE TRIGGER fail_test_reinstate_restore
                BEFORE UPDATE ON workflow_definitions
                FOR EACH ROW EXECUTE FUNCTION fail_test_reinstate_restore()
                """).execute());
    }

    private static void removeFailure(DefinitionScenario scenario, String name) {
        await(scenario.admin()
                .query("DROP TRIGGER IF EXISTS " + name + " ON workflow_definitions")
                .execute());
        await(scenario.admin().query("DROP FUNCTION IF EXISTS " + name + "()").execute());
    }

    private static WorkflowDefinition<?, ?> code(String definitionId, long version, Class<?> contract, String endStep) {
        return new CodeWorkflow(definitionId, version, contract, endStep);
    }

    /** The plan hash that registering the definition produces, derived as the document loader derives it. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String planHashOf(WorkflowDefinition<?, ?> definition) {
        WorkflowBuilder builder = new WorkflowBuilder();
        definition.define(builder);
        return builder.build(
                        definition.definitionId(),
                        definition.definitionVersion(),
                        definition.stateType().getName())
                .planHash();
    }

    private static long lockKey(String definitionId) {
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                            .digest(("vertique.workflow.definition:" + definitionId).getBytes(StandardCharsets.UTF_8)))
                    .getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record CodeState(String value) {}

    public record CodeStart(String value) {}

    /** A code-first definition whose only step is its end step, so the workflow completes as soon as it starts. */
    private static final class CodeWorkflow implements WorkflowDefinition<CodeState, Object> {

        private final String definitionId;
        private final long version;
        private final Class<?> contract;
        private final String endStep;

        private CodeWorkflow(String definitionId, long version, Class<?> contract, String endStep) {
            this.definitionId = definitionId;
            this.version = version;
            this.contract = contract;
            this.endStep = endStep;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<Object> contract() {
            return (Class<Object>) contract;
        }

        @Override
        public Class<CodeState> stateType() {
            return CodeState.class;
        }

        @Override
        public String definitionId() {
            return definitionId;
        }

        @Override
        public long definitionVersion() {
            return version;
        }

        @Override
        public void define(WorkflowBuilder<CodeState> workflow) {
            workflow.init(CodeStart.class, start -> new CodeState(start.value()))
                    .initialStep(endStep)
                    .complete(endStep);
        }
    }

    /** A caller-owned transaction on its own connection, optionally holding the shared admission lock of an id. */
    private static final class Tx {

        private final SqlConnection connection;
        private final Transaction transaction;
        private boolean done;

        private Tx(SqlConnection connection, Transaction transaction) {
            this.connection = connection;
            this.transaction = transaction;
        }

        static Tx begin(DefinitionScenario scenario, String sharedAdmissionId) {
            return begin(scenario, sharedAdmissionId, "pg_advisory_xact_lock_shared");
        }

        /** Begins a transaction that holds the exclusive lock the store takes for the id, until it ends. */
        static Tx beginExclusive(DefinitionScenario scenario, String definitionId) {
            return begin(scenario, definitionId, "pg_advisory_xact_lock");
        }

        private static Tx begin(DefinitionScenario scenario, String lockedId, String lockFunction) {
            SqlConnection connection = await(scenario.admin().getConnection());
            Transaction transaction;
            try {
                transaction = await(connection.begin());
                if (lockedId != null) {
                    await(connection
                            .preparedQuery("SELECT " + lockFunction + "($1)")
                            .execute(Tuple.of(lockKey(lockedId))));
                }
            } catch (RuntimeException | Error failure) {
                await(connection.close());
                throw failure;
            }
            Tx tx = new Tx(connection, transaction);
            scenario.onClose(tx::rollback);
            return tx;
        }

        SqlConnection connection() {
            return connection;
        }

        void commit() {
            if (done) return;
            done = true;
            try {
                await(transaction.commit());
            } finally {
                await(connection.close());
            }
        }

        void rollback() {
            if (done) return;
            done = true;
            try {
                await(transaction.rollback().recover(ignored -> Future.succeededFuture()));
            } finally {
                await(connection.close());
            }
        }
    }
}
