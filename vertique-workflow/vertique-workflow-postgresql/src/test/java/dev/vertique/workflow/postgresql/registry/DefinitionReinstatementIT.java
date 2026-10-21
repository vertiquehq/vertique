// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.documentText;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowContractMetadata;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.ActivationStatus;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowProxyContractException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
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
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    @WorkflowContract(definitionId = "reinstatement-it-code", definitionVersion = 1)
    public interface ReinstatedCodeV1 {}

    @WorkflowContract(definitionId = "reinstatement-it-missing-code", definitionVersion = 2)
    public interface MissingCodeV2 {}

    @WorkflowContract(definitionId = "reinstatement-it-drift-code", definitionVersion = 1)
    public interface DriftCodeV1 {}

    @WorkflowContract(definitionId = "reinstatement-it-owner-doc", definitionVersion = 1)
    public interface OwnerDocV1 {}

    @WorkflowContract(definitionId = "reinstatement-it-doc", definitionVersion = 1)
    public interface ReinstatedDocV1 {}

    @WorkflowContract(definitionId = "reinstatement-it-drift-doc", definitionVersion = 1)
    public interface DriftDocV1 {}

    @WorkflowContract(definitionId = "reinstatement-it-rollback", definitionVersion = 1)
    public interface RollbackV1 {}

    @WorkflowContract(definitionId = "reinstatement-it-rollback", definitionVersion = 2)
    public interface RollbackV2 {}

    @WorkflowContract(definitionId = "reinstatement-it-rollback", definitionVersion = 3)
    public interface RollbackV3 {}

    @WorkflowContract(definitionId = "reinstatement-it-preserved", definitionVersion = 1)
    public interface PreservedV1 {}

    @WorkflowContract(definitionId = "reinstatement-it-preserved", definitionVersion = 2)
    public interface PreservedV2 {}

    @WorkflowContract(definitionId = "reinstatement-it-stale-doc", definitionVersion = 1)
    public interface StaleDocV1 {}

    @WorkflowContract(definitionId = "reinstatement-it-isolation", definitionVersion = 1)
    public interface IsolationDocV1 {}

    @WorkflowContract(definitionId = "reinstatement-it-reconcile-isolation", definitionVersion = 1)
    public interface ReconcileIsolationDocV1 {}

    @WorkflowContract(definitionId = "reinstatement-it-store-owner", definitionVersion = 1)
    public interface StoreOwnerDocV1 {}

    @WorkflowContract(definitionId = "reinstatement-it-store-pairs", definitionVersion = 1)
    public interface StorePairsDocV1 {}

    @WorkflowContract(definitionId = "reinstatement-it-lower-doc", definitionVersion = 1)
    public interface LowerDocV1 {}

    @WorkflowContract(definitionId = "reinstatement-it-lower-doc", definitionVersion = 2)
    public interface LowerDocV2 {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_reinstatement_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("a retired code version whose code is still deployed returns to service and can be retired again")
    void reinstatesCodeAfterRestartUnregisteredIt(Vertx vertx) {
        String id = "reinstatement-it-code";
        DefinitionKey key = new DefinitionKey(null, id, 1);
        WorkflowDefinition<?, ?> deployed = code(id, 1, ReinstatedCodeV1.class, "done");
        String hash = planHashOf(deployed);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a retired code row whose code the application still deploys
            scenario.seedCode(id, 1, hash, "RETIRED");

            // And a restart, after which the retired version has neither an executable registration nor a contract
            // mapping
            Boot restarted = scenario.node(none(), unobserved(), List.of(deployed));
            WorkflowRegistry registry = restarted.app().workflowRegistry();
            assertThatThrownBy(() -> registry.resolvePinned(id, 1))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
            assertThatThrownBy(() -> registry.contractMetadata(ReinstatedCodeV1.class))
                    .isInstanceOf(WorkflowProxyContractException.class);
            Stored retired = scenario.stored(id, 1);
            assertThat(retired.status()).isEqualTo("RETIRED");
            WorkflowDefinitionService service = restarted.app().definitionService();

            // When the version is reinstated through the service
            await(service.reinstate(key));

            // Then the row is recomputed to the active version, with its code identity and hash unchanged
            Stored reinstated = scenario.stored(id, 1);
            assertThat(reinstated.status()).isEqualTo("ACTIVE");
            assertThat(reinstated)
                    .usingRecursiveComparison()
                    .ignoringFields("status", "changedAt")
                    .isEqualTo(retired);
            assertThat(reinstated.planHash()).isEqualTo(hash);

            // And both current and pinned resolution serve the deployed code, with its contract mapping restored
            assertThat(registry.resolvePinned(id, 1).plan().planHash()).isEqualTo(hash);
            assertThat(registry.resolveCurrent(id).plan().definitionVersion()).isEqualTo(1);
            assertThat(registry.isDeprecated(id, 1)).isFalse();
            WorkflowContractMetadata mapping = registry.contractMetadata(ReinstatedCodeV1.class);
            assertThat(mapping.definitionId()).isEqualTo(id);
            assertThat(mapping.definitionVersion()).isEqualTo(1);

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
            await(service.deprecate(key));
            assertThat(scenario.stored(id, 1).status()).isEqualTo("DEPRECATED");
            assertThat(registry.isDeprecated(id, 1)).isTrue();
            scenario.ageStatus(key, coolingSeconds + 60);
            await(service.retire(key));

            // Then the removal succeeds and clears the runtime and its contract mapping
            assertThat(scenario.stored(id, 1).status()).isEqualTo("RETIRED");
            assertThatThrownBy(() -> registry.resolvePinned(id, 1))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
            assertThatThrownBy(() -> registry.contractMetadata(ReinstatedCodeV1.class))
                    .isInstanceOf(WorkflowProxyContractException.class);
            assertThat(registeredVersions(registry, id)).isEmpty();
        }
    }

    @Test
    @DisplayName("reinstating is refused without the deployed code and while another tenant holds the id")
    void refusesMissingCodeAndAnotherOwner(Vertx vertx) {
        String missingId = "reinstatement-it-missing-code";
        String ownerId = "reinstatement-it-owner-doc";
        DefinitionKey missingKey = new DefinitionKey(null, missingId, 1);
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
            scenario.seedCode(missingId, 2, neighbourHash, "ACTIVE");
            Boot node = scenario.node(none(), unobserved(), List.of(deployedNeighbour));
            WorkflowRegistry registry = node.app().workflowRegistry();
            WorkflowDefinitionService service = node.app().definitionService();
            Stored missingBefore = scenario.stored(missingId, 1);
            Stored neighbourBefore = scenario.stored(missingId, 2);
            assertThat(registeredVersions(registry, missingId)).containsExactly(2L);

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
            assertThat(registeredVersions(registry, missingId)).containsExactly(2L);
            assertThatThrownBy(() -> registry.resolvePinned(missingId, 1))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);

            // Given a tenant that retired the id and a second tenant that has since activated the same id
            long coolingSeconds = node.app().workflowDefinitionsConfig().maxStalenessSeconds();
            TenantRowSeed.seed(
                    node.app(),
                    "tenant-one",
                    documentBytes(ownerId, 1, OwnerDocV1.class),
                    source("tenant-one", loadedAt),
                    DefinitionStatus.CANDIDATE);
            await(service.activate(tenantOneKey));
            await(service.deprecate(tenantOneKey));
            scenario.ageStatus(tenantOneKey, coolingSeconds + 60);
            await(service.retire(tenantOneKey));
            TenantRowSeed.seed(
                    node.app(),
                    "tenant-two",
                    documentBytes(ownerId, 1, OwnerDocV1.class),
                    source("tenant-two", loadedAt),
                    DefinitionStatus.CANDIDATE);
            await(service.activate(tenantTwoKey));
            Stored retiredBefore = scenario.stored("tenant-one", ownerId, 1);
            Stored holderBefore = scenario.stored("tenant-two", ownerId, 1);
            assertThat(retiredBefore.status()).isEqualTo("RETIRED");
            assertThat(holderBefore.status()).isEqualTo("ACTIVE");
            RuntimeWorkflow holderRuntime = registry.resolvePinned(ownerId, 1);

            // When the first tenant's retired version is reinstated
            Throwable held = failureOf(service.reinstate(tenantOneKey));

            // Then it fails typed as an ownership conflict naming the key and the holding tenant
            assertThat(held).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                assertThat(failure.key()).isEqualTo(tenantOneKey);
                assertThat(failure.getMessage()).contains(ownerId).contains("tenant-two");
            });

            // And both rows are unchanged and the holder's runtime is still the only one registered for the id
            assertThat(scenario.stored("tenant-one", ownerId, 1)).isEqualTo(retiredBefore);
            assertThat(scenario.stored("tenant-two", ownerId, 1)).isEqualTo(holderBefore);
            assertThat(registry.resolvePinned(ownerId, 1)).isSameAs(holderRuntime);
            assertThat(registeredVersions(registry, ownerId)).containsExactly(1L);
        }
    }

    @Test
    @DisplayName("a retired code version whose stored hash differs from the deployed code's hash fails as drift")
    void refusesCodeWhoseStoredHashDiffersFromDeployedCode(Vertx vertx) {
        String id = "reinstatement-it-drift-code";
        DefinitionKey key = new DefinitionKey(null, id, 1);
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
            ActivationStatus cacheBefore =
                    restarted.app().workflowDefinitionStore().currentStatus(id, 1);
            assertThat(registeredVersions(registry, id)).isEmpty();

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
            assertThat(restarted.app().workflowDefinitionStore().currentStatus(id, 1))
                    .isEqualTo(cacheBefore);

            // And nothing was left registered or published for the key
            assertThat(registeredVersions(registry, id)).isEmpty();
            assertThatThrownBy(() -> registry.resolvePinned(id, 1))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
            assertThatThrownBy(() -> registry.contractMetadata(DriftCodeV1.class))
                    .isInstanceOf(WorkflowProxyContractException.class);
        }
    }

    @Test
    @DisplayName("a retired document returns to service from its stored text, and a text that no longer matches fails")
    void reinstatesDocumentOrFailsClosedOnDrift(Vertx vertx) {
        String id = "reinstatement-it-doc";
        String driftId = "reinstatement-it-drift-doc";
        DefinitionKey key = new DefinitionKey(null, id, 1);
        DefinitionKey driftKey = new DefinitionKey(null, driftId, 1);
        Instant loadedAt = Instant.parse("2026-10-19T09:00:00Z");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id, driftId);

            // Given a document that a first node loaded, activated, deprecated, cooled and retired
            Boot first = scenario.node(none());
            WorkflowDefinitionService firstService = first.app().definitionService();
            long coolingSeconds = first.app().workflowDefinitionsConfig().maxStalenessSeconds();
            String loadedHash = await(firstService.load(
                            documentBytes(id, 1, ReinstatedDocV1.class),
                            DocumentFormat.YAML,
                            source("retired-doc", loadedAt),
                            null))
                    .planHash();
            await(firstService.activate(key));
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
            scenario.seedDocument(null, driftId, 1, storedDriftHash, "RETIRED", driftText);
            first.close();

            // And a restart, after which neither retired document is registered
            Boot restarted = scenario.node(none());
            WorkflowRegistry registry = restarted.app().workflowRegistry();
            WorkflowDefinitionService service = restarted.app().definitionService();
            Stored retired = scenario.stored(id, 1);
            Stored driftBefore = scenario.stored(driftId, 1);
            assertThat(retired.status()).isEqualTo("RETIRED");
            assertThat(driftBefore.status()).isEqualTo("RETIRED");
            assertThat(restarted.app().workflowDefinitionStore().currentStatus(id, 1))
                    .isEqualTo(ActivationStatus.RETIRED);
            assertThatThrownBy(() -> registry.resolvePinned(id, 1))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
            assertThatThrownBy(() -> registry.resolvePinned(driftId, 1))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
            ActivationStatus driftCacheBefore =
                    restarted.app().workflowDefinitionStore().currentStatus(driftId, 1);

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
            assertThat(registeredVersions(registry, driftId)).isEmpty();
            assertThat(restarted.app().workflowDefinitionStore().currentStatus(driftId, 1))
                    .isEqualTo(driftCacheBefore);

            // When the document with the matching hash is reinstated
            await(service.reinstate(key));

            // Then its row is active again with the stored text and hash unchanged
            Stored reinstated = scenario.stored(id, 1);
            assertThat(reinstated.status()).isEqualTo("ACTIVE");
            assertThat(reinstated)
                    .usingRecursiveComparison()
                    .ignoringFields("status", "changedAt")
                    .isEqualTo(retired);

            // And the membership and the cache are restored
            assertThat(registry.resolvePinned(id, 1).plan().planHash()).isEqualTo(loadedHash);
            assertThat(registry.resolveCurrent(id).plan().definitionVersion()).isEqualTo(1);
            assertThat(registry.contractMetadata(ReinstatedDocV1.class).definitionId())
                    .isEqualTo(id);
            assertThat(restarted.app().workflowDefinitionStore().currentStatus(id, 1))
                    .isEqualTo(ActivationStatus.ACTIVE);

            // When the same component deprecates it, its cooling window passes by the database clock and it is
            // retired with no pin
            assertThat(scenario.instanceCount(id)).isZero();
            await(service.deprecate(key));
            scenario.ageStatus(key, coolingSeconds + 60);
            await(service.retire(key));

            // Then the removal succeeds and clears the runtime and its contract mapping
            assertThat(scenario.stored(id, 1).status()).isEqualTo("RETIRED");
            assertThatThrownBy(() -> registry.resolvePinned(id, 1))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
            assertThatThrownBy(() -> registry.contractMetadata(ReinstatedDocV1.class))
                    .isInstanceOf(WorkflowProxyContractException.class);
            assertThat(registeredVersions(registry, id)).isEmpty();
        }
    }

    @Test
    @DisplayName("a reinstatement that fails before its promotion leaves the old rows and registrations as they were")
    void rollsBackReinstateBeforePromotion(Vertx vertx) {
        String id = "reinstatement-it-rollback";
        String preservedId = "reinstatement-it-preserved";
        String promotionMarker = "reinstatement-promotion-gate";
        String restoreMarker = "reinstatement-restore-gate";
        DefinitionKey v1 = new DefinitionKey(null, id, 1);
        DefinitionKey v2 = new DefinitionKey(null, id, 2);
        DefinitionKey v3 = new DefinitionKey(null, id, 3);
        DefinitionKey preservedV1 = new DefinitionKey(null, preservedId, 1);
        WorkflowDefinition<?, ?> codeV1 = code(id, 1, RollbackV1.class, "done");
        WorkflowDefinition<?, ?> codeV2 = code(id, 2, RollbackV2.class, "done");
        WorkflowDefinition<?, ?> codeV3 = code(id, 3, RollbackV3.class, "done");
        WorkflowDefinition<?, ?> preservedCodeV1 = code(preservedId, 1, PreservedV1.class, "done");
        WorkflowDefinition<?, ?> preservedCodeV2 = code(preservedId, 2, PreservedV2.class, "done");
        String hash1 = planHashOf(codeV1);
        String hash2 = planHashOf(codeV2);
        String hash3 = planHashOf(codeV3);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id, preservedId);

            // Given version 2 active and versions 1 and 3 retired, all provided by the deployed code
            scenario.seedCode(id, 1, hash1, "RETIRED");
            scenario.seedCode(id, 2, hash2, "ACTIVE");
            scenario.seedCode(id, 3, hash3, "RETIRED");

            // And a second id whose version 1 is superseded under an active version 2
            scenario.seedCode(preservedId, 1, planHashOf(preservedCodeV1), "SUPERSEDED");
            scenario.seedCode(preservedId, 2, planHashOf(preservedCodeV2), "ACTIVE");

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
            RuntimeWorkflow activeRuntime = registry.resolvePinned(id, 2);
            assertThat(registeredVersions(registry, id)).containsExactly(2L);
            assertThat(scenario.stored(id, 3).status()).isEqualTo("RETIRED");

            // When the lower retired version is reinstated
            await(service.reinstate(v1));

            // Then it is superseded under the active version, which stays active and current
            assertThat(scenario.stored(id, 1).status()).isEqualTo("SUPERSEDED");
            assertThat(scenario.stored(id, 2).status()).isEqualTo("ACTIVE");
            assertThat(scenario.stored(id, 3).status()).isEqualTo("RETIRED");
            assertThat(activeCount(scenario, id)).isEqualTo(1);
            assertThat(registry.resolveCurrent(id).plan().definitionVersion()).isEqualTo(2);
            assertThat(registry.resolvePinned(id, 2)).isSameAs(activeRuntime);
            RuntimeWorkflow lowerRuntime = registry.resolvePinned(id, 1);
            assertThat(lowerRuntime.plan().planHash()).isEqualTo(hash1);
            assertThat(registry.contractMetadata(RollbackV1.class).definitionVersion())
                    .isEqualTo(1);

            // Given a database fault that fails the promotion of version 3, but only after the previous winner has
            // been demoted in the same transaction
            installPromotionFailure(scenario, id, 3, 2, promotionMarker);
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
            assertThat(registry.resolvePinned(id, 3).plan().planHash()).isEqualTo(hash3);

            // And the admission still sees only the committed, retired row and is refused
            Throwable raced =
                    failureOf(operations.start(startCommand(id, RollbackV3.class, "race-v3", 3), racer.connection()));
            assertThat(raced).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.key()).isEqualTo(v3);
            });
            racer.rollback();

            // When the transaction then fails between demoting version 2 and promoting version 3
            Throwable atGate = await(gate.observed());

            // Then the database raised the fault only after observing version 2 demoted
            assertThat(causeContains(atGate, promotionMarker))
                    .as("the fault must come from the promotion after the demotion")
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
                    operations.start(startCommand(id, RollbackV3.class, "gate-v3", 3), refusedAdmission.connection()));
            refusedAdmission.rollback();
            assertThat(stillRetired).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.key()).isEqualTo(v3);
            });
            Tx admitted = Tx.begin(scenario, null);
            WorkflowInstanceId admittedId =
                    await(operations.start(startCommand(id, RollbackV2.class, "gate-v2", 2), admitted.connection()));
            admitted.commit();
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

            // And only the attempt's own publication was compensated, with its contract mapping
            assertThatThrownBy(() -> registry.resolvePinned(id, 3))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
            assertThatThrownBy(() -> registry.contractMetadata(RollbackV3.class))
                    .isInstanceOf(WorkflowProxyContractException.class);
            assertThat(registry.resolvePinned(id, 2)).isSameAs(activeRuntime);
            assertThat(registry.resolvePinned(id, 1)).isSameAs(lowerRuntime);
            assertThat(registry.resolveCurrent(id).plan().definitionVersion()).isEqualTo(2);

            // When the fault is gone and the higher version is reinstated again
            removeFailure(scenario, "fail_test_reinstate_promotion");
            await(service.reinstate(v3));

            // Then the higher version won in one step: it is the only active row and version 2 is superseded
            assertThat(scenario.stored(id, 3).status()).isEqualTo("ACTIVE");
            assertThat(scenario.stored(id, 2).status()).isEqualTo("SUPERSEDED");
            assertThat(scenario.stored(id, 1).status()).isEqualTo("SUPERSEDED");
            assertThat(activeCount(scenario, id)).isEqualTo(1);
            assertThat(registry.resolveCurrent(id).plan().definitionVersion()).isEqualTo(3);
            assertThat(registry.resolvePinned(id, 3).plan().planHash()).isEqualTo(hash3);
            assertThat(registry.resolvePinned(id, 2)).isSameAs(activeRuntime);

            // And admission now accepts the committed version
            Tx afterCommit = Tx.begin(scenario, null);
            WorkflowInstanceId pinned = await(
                    operations.start(startCommand(id, RollbackV3.class, "after-v3", 3), afterCommit.connection()));
            afterCommit.commit();
            assertThat(await(boot.app().workflowOperations().query(pinned))
                            .instance()
                            .definitionVersion())
                    .isEqualTo(3);

            // Given another node retired version 1 of the second id while this node still serves its registration
            RuntimeWorkflow preservedRuntime = registry.resolvePinned(preservedId, 1);
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

            // And the registration that existed before the attempt is preserved, with its contract mapping
            assertThat(registry.resolvePinned(preservedId, 1)).isSameAs(preservedRuntime);
            assertThat(registry.contractMetadata(PreservedV1.class).definitionVersion())
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a claim that is older than the staleness bound does not hold the id against a reinstatement")
    void staleActivatingRowOfAnotherOwnerDoesNotBlockReinstatement(Vertx vertx) {
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
            await(service.activate(tenantOneKey));
            await(service.deprecate(tenantOneKey));
            scenario.ageStatus(tenantOneKey, coolingSeconds + 60);
            await(service.retire(tenantOneKey));
            Stored retiredBefore = scenario.stored("tenant-one", id, 1);
            assertThat(retiredBefore.status()).isEqualTo("RETIRED");

            // And a second tenant that has just claimed the id for an activation that nobody completed
            scenario.seedDocument(
                    "tenant-two", id, 1, "e".repeat(64), "ACTIVATING", documentText(id, 1, StaleDocV1.class));
            assertThat(scenario.stored("tenant-two", id, 1).status()).isEqualTo("ACTIVATING");

            // When the first tenant's version is reinstated
            Throwable fresh = failureOf(service.reinstate(tenantOneKey));

            // Then the fresh claim still holds the id: an ownership conflict naming the holder, with nothing changed
            assertThat(fresh).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                assertThat(failure.key()).isEqualTo(tenantOneKey);
                assertThat(failure.getMessage()).contains("tenant-two");
            });
            assertThat(scenario.stored("tenant-one", id, 1)).isEqualTo(retiredBefore);
            assertThat(registeredVersions(registry, id)).isEmpty();

            // Given that claim has since grown older than twice the staleness bound by the database clock
            scenario.ageStatus(tenantTwoKey, 2 * coolingSeconds + 60);
            Stored staleBefore = scenario.stored("tenant-two", id, 1);
            assertThat(staleBefore.status()).isEqualTo("ACTIVATING");

            // When the first tenant's version is reinstated again
            Throwable stale = failureOf(service.reinstate(tenantOneKey));

            // Then the stale claim holds nothing, so the reinstatement succeeds
            assertThat(stale)
                    .as("a claim older than the staleness bound must not hold the id")
                    .isNull();
            Stored reinstated = scenario.stored("tenant-one", id, 1);
            assertThat(reinstated.status()).isEqualTo("ACTIVE");
            assertThat(reinstated)
                    .usingRecursiveComparison()
                    .ignoringFields("status", "changedAt")
                    .isEqualTo(retiredBefore);
            assertThat(registry.resolvePinned(id, 1).plan().planHash()).isEqualTo(loadedHash);

            // And the other tenant's claim is untouched
            assertThat(scenario.stored("tenant-two", id, 1)).isEqualTo(staleBefore);
        }
    }

    @Test
    @DisplayName("the store settles the owner check on current rows whatever the server default isolation is")
    void storeSeesAnOwnerThatCommitsWhileItWaitsEvenWithRepeatableReadServerDefault(Vertx vertx) {
        String id = "reinstatement-it-isolation";
        DefinitionKey key = new DefinitionKey(null, id, 1);
        String hash = "c".repeat(64);
        String text = documentText(id, 1, IsolationDocV1.class);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a database whose default isolation is REPEATABLE READ, restored when the scenario ends
            String database = await(scenario.admin()
                            .query("SELECT current_database() AS name")
                            .execute())
                    .iterator()
                    .next()
                    .getString("name");
            await(scenario.admin()
                    .query("ALTER DATABASE \"" + database + "\" SET default_transaction_isolation = 'repeatable read'")
                    .execute());
            scenario.onClose(() -> await(scenario.admin()
                    .query("ALTER DATABASE \"" + database + "\" RESET default_transaction_isolation")
                    .execute()));

            // And a node started afterwards, whose fresh pool connections inherit that default
            AppComponent app = scenario.node(none()).app();
            Row serverDefault = await(app.pool()
                            .query("SHOW default_transaction_isolation")
                            .execute())
                    .iterator()
                    .next();
            assertThat(serverDefault.getString(0)).isEqualTo("repeatable read");
            WorkflowDefinitionRegistryStore store = app.definitionRegistryStore();

            // And a retired global document, and a transaction that holds the exclusive lock of the id
            scenario.seedDocument(null, id, 1, hash, "RETIRED", text);
            Stored before = scenario.stored(id, 1);
            Tx holder = Tx.beginExclusive(scenario, id);

            // When the store reinstates the version directly, and is proven queued behind that lock
            Future<Void> change = store.transition(key, DefinitionStatus.RETIRED, DefinitionStatus.SUPERSEDED);
            scenario.awaitAdvisory(id, "ExclusiveLock", false, 1);
            assertThat(change.isComplete()).isFalse();

            // And another owner's superseded version of the id commits while it waits, and the lock is released
            scenario.seedDocument("tenant-two", id, 1, hash, "SUPERSEDED", text);
            holder.commit();
            Throwable refused = failureOf(change);

            // Then the owner check sees the committed row: an ownership conflict naming the holder
            assertThat(refused)
                    .as("the owner check must read the rows that are current after the lock wait")
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                        assertThat(failure.key()).isEqualTo(key);
                        assertThat(failure.getMessage()).contains("tenant-two");
                    });

            // And the retired row is unchanged
            assertThat(scenario.stored(id, 1)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("a startup reconciliation sees an owner that commits while it waits, whatever the server default is")
    void reconciliationSeesAnOwnerThatCommitsWhileItWaitsEvenWithRepeatableReadServerDefault(Vertx vertx) {
        String id = "reinstatement-it-reconcile-isolation";
        DefinitionKey globalKey = new DefinitionKey(null, id, 1);
        String hash = "e".repeat(64);
        String text = documentText(id, 1, ReconcileIsolationDocV1.class);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a database whose default isolation is REPEATABLE READ, restored when the scenario ends
            String database = await(scenario.admin()
                            .query("SELECT current_database() AS name")
                            .execute())
                    .iterator()
                    .next()
                    .getString("name");
            await(scenario.admin()
                    .query("ALTER DATABASE \"" + database + "\" SET default_transaction_isolation = 'repeatable read'")
                    .execute());
            scenario.onClose(() -> await(scenario.admin()
                    .query("ALTER DATABASE \"" + database + "\" RESET default_transaction_isolation")
                    .execute()));

            // And a node started afterwards, whose fresh pool connections inherit that default
            AppComponent app = scenario.node(none()).app();
            WorkflowDefinitionRegistryStore store = app.definitionRegistryStore();

            // And a transaction that holds the exclusive lock of an id without rows
            Tx holder = Tx.beginExclusive(scenario, id);

            // When the store reconciles a global document row, and is proven queued behind that lock
            Instant now = Instant.now();
            WorkflowDefinitionRecord record = new WorkflowDefinitionRecord(
                    globalKey,
                    DefinitionKind.DOCUMENT,
                    hash,
                    DefinitionStatus.SUPERSEDED,
                    new DefinitionProvenance("static-source", "memory:static", "revision-static", null, null, now),
                    "YAML",
                    now,
                    now);
            Future<ReconcileOutcome> reconcile =
                    store.upsertDocument(record, new WorkflowDefinitionDocument(globalKey, text, "YAML"));
            scenario.awaitAdvisory(id, "ExclusiveLock", false, 1);
            assertThat(reconcile.isComplete()).isFalse();

            // And another owner's version of the id commits while it waits, and the lock is released
            scenario.seedDocument("tenant-two", id, 1, hash, "SUPERSEDED", text);
            holder.commit();

            // Then the owner check sees the committed row, so no global row is inserted beside it
            assertThat(await(reconcile)).isEqualTo(ReconcileOutcome.OWNER_CONFLICT);
            assertThat(scenario.rowCount(id)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the store itself refuses to reinstate while another owner holds the id")
    void storeRefusesToReinstateWhileAnotherOwnerHoldsTheId(Vertx vertx) {
        String id = "reinstatement-it-store-owner";
        DefinitionKey key = new DefinitionKey(null, id, 1);
        DefinitionKey holderKey = new DefinitionKey("tenant-two", id, 1);
        String hash = "d".repeat(64);
        String text = documentText(id, 1, StoreOwnerDocV1.class);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);
            AppComponent app = scenario.node(none()).app();
            WorkflowDefinitionRegistryStore store = app.definitionRegistryStore();
            long coolingSeconds = app.workflowDefinitionsConfig().maxStalenessSeconds();

            // Given a retired global document
            scenario.seedDocument(null, id, 1, hash, "RETIRED", text);
            Stored retiredBefore = scenario.stored(id, 1);

            for (String holding : List.of("ACTIVE", "SUPERSEDED", "DEPRECATED", "ACTIVATING")) {
                // And a second tenant that holds the id in each status that holds it
                scenario.seedDocument("tenant-two", id, 1, hash, holding, text);
                Stored holderBefore = scenario.stored("tenant-two", id, 1);

                // When the store is asked to reinstate the retired version, bypassing the lifecycle
                Throwable held =
                        failureOf(store.transition(key, DefinitionStatus.RETIRED, DefinitionStatus.SUPERSEDED));

                // Then it refuses as an ownership conflict naming the holder, and both rows are unchanged
                assertThat(held)
                        .as("holder %s", holding)
                        .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                            assertThat(failure.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                            assertThat(failure.key()).isEqualTo(key);
                            assertThat(failure.getMessage()).contains(id).contains("tenant-two");
                        });
                assertThat(scenario.stored(id, 1))
                        .as("retired row, holder %s", holding)
                        .isEqualTo(retiredBefore);
                assertThat(scenario.stored("tenant-two", id, 1)).isEqualTo(holderBefore);
                scenario.execute("""
                        DELETE FROM workflow_definitions
                        WHERE tenant_key = 'tenant-two' AND definition_id = $1
                        """, Tuple.of(id));
            }

            // Given a second tenant whose claim is older than twice the staleness bound
            scenario.seedDocument("tenant-two", id, 1, hash, "ACTIVATING", text);
            scenario.ageStatus(holderKey, 2 * coolingSeconds + 60);
            Stored staleBefore = scenario.stored("tenant-two", id, 1);
            assertThat(staleBefore.status()).isEqualTo("ACTIVATING");

            // When the store is asked to reinstate the retired version
            await(store.transition(key, DefinitionStatus.RETIRED, DefinitionStatus.SUPERSEDED));

            // Then the stale claim holds nothing: the version is back in service and the claim is untouched
            assertThat(scenario.stored(id, 1).status()).isEqualTo("ACTIVE");
            assertThat(scenario.stored("tenant-two", id, 1)).isEqualTo(staleBefore);
        }
    }

    @Test
    @DisplayName("the store reinstates only a retired row and refuses every other change as an invalid transition")
    void storeRefusesUnsupportedPairsAndRowsThatAreNotRetired(Vertx vertx) {
        String id = "reinstatement-it-store-pairs";
        String hash = "d".repeat(64);
        String text = documentText(id, 1, StorePairsDocV1.class);
        DefinitionKey retired = new DefinitionKey(null, id, 1);
        record Pair(DefinitionStatus from, DefinitionStatus to) {}

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);
            WorkflowDefinitionRegistryStore store = scenario.node(none()).app().definitionRegistryStore();

            // Given one retired version, and one version in each other status that a reinstatement could meet
            scenario.seedDocument(null, id, 1, hash, "RETIRED", text);
            scenario.seedDocument(null, id, 2, hash, "ACTIVE", text);
            scenario.seedDocument(null, id, 3, hash, "DEPRECATED", text);
            scenario.seedDocument(null, id, 4, hash, "SUPERSEDED", text);
            scenario.seedDocument(null, id, 5, hash, "CANDIDATE", text);
            List<Stored> before = List.of(
                    scenario.stored(id, 1),
                    scenario.stored(id, 2),
                    scenario.stored(id, 3),
                    scenario.stored(id, 4),
                    scenario.stored(id, 5));

            // When status pairs other than retired to superseded are requested on the retired version
            List<Pair> pairs = List.of(
                    new Pair(DefinitionStatus.DEPRECATED, DefinitionStatus.SUPERSEDED),
                    new Pair(DefinitionStatus.ACTIVE, DefinitionStatus.SUPERSEDED),
                    new Pair(DefinitionStatus.SUPERSEDED, DefinitionStatus.SUPERSEDED),
                    new Pair(DefinitionStatus.RETIRED, DefinitionStatus.ACTIVE),
                    new Pair(DefinitionStatus.RETIRED, DefinitionStatus.DEPRECATED),
                    new Pair(DefinitionStatus.RETIRED, DefinitionStatus.RETIRED));
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

            // When the retired to superseded change is requested on each row that is not retired
            for (int version = 2; version <= 5; version++) {
                DefinitionKey key = new DefinitionKey(null, id, version);
                String found = before.get(version - 1).status();
                Throwable refused =
                        failureOf(store.transition(key, DefinitionStatus.RETIRED, DefinitionStatus.SUPERSEDED));

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
            DefinitionKey missing = new DefinitionKey(null, id, 99);
            Throwable absent =
                    failureOf(store.transition(missing, DefinitionStatus.RETIRED, DefinitionStatus.SUPERSEDED));

            // Then it is refused as an invalid transition naming the key, and no row appeared
            assertThat(absent).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(failure.key()).isEqualTo(missing);
            });
            assertThat(scenario.findStored(id, 99)).isEmpty();
            assertThat(scenario.rowCount(id)).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("a reinstated lower document version stays superseded under the active higher version")
    void reinstatedLowerDocumentStaysSupersededUnderTheActiveHigherVersion(Vertx vertx) {
        String id = "reinstatement-it-lower-doc";
        DefinitionKey v1 = new DefinitionKey(null, id, 1);
        DefinitionKey v2 = new DefinitionKey(null, id, 2);
        Instant loadedAt = Instant.parse("2026-10-19T09:00:00Z");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);
            Boot node = scenario.node(none());
            WorkflowRegistry registry = node.app().workflowRegistry();
            WorkflowDefinitionService service = node.app().definitionService();
            long coolingSeconds = node.app().workflowDefinitionsConfig().maxStalenessSeconds();

            // Given version 1 retired and version 2 active, both documents
            String hash1 = await(service.load(
                            documentBytes(id, 1, LowerDocV1.class),
                            DocumentFormat.YAML,
                            source("lower-v1", loadedAt),
                            null))
                    .planHash();
            await(service.activate(v1));
            await(service.load(
                    documentBytes(id, 2, LowerDocV2.class), DocumentFormat.YAML, source("lower-v2", loadedAt), null));
            await(service.activate(v2));
            await(service.deprecate(v1));
            scenario.ageStatus(v1, coolingSeconds + 60);
            await(service.retire(v1));
            RuntimeWorkflow activeRuntime = registry.resolvePinned(id, 2);
            assertThat(scenario.stored(id, 1).status()).isEqualTo("RETIRED");
            assertThat(scenario.stored(id, 2).status()).isEqualTo("ACTIVE");
            assertThat(registeredVersions(registry, id)).containsExactly(2L);

            // When the lower version is reinstated
            await(service.reinstate(v1));

            // Then it is superseded under the active higher version, which stays the only active row and current
            assertThat(scenario.stored(id, 1).status()).isEqualTo("SUPERSEDED");
            assertThat(scenario.stored(id, 2).status()).isEqualTo("ACTIVE");
            assertThat(activeCount(scenario, id)).isEqualTo(1);
            assertThat(registry.resolveCurrent(id).plan().definitionVersion()).isEqualTo(2);
            assertThat(registry.resolvePinned(id, 2)).isSameAs(activeRuntime);

            // And the lower version is served again from its stored text, and the cache agrees with the table
            assertThat(registry.resolvePinned(id, 1).plan().planHash()).isEqualTo(hash1);
            assertThat(registry.contractMetadata(LowerDocV1.class).definitionVersion())
                    .isEqualTo(1);
            assertThat(node.app().workflowDefinitionStore().currentStatus(id, 1))
                    .isEqualTo(ActivationStatus.SUPERSEDED);
            assertThat(node.app().workflowDefinitionStore().currentStatus(id, 2))
                    .isEqualTo(ActivationStatus.ACTIVE);
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

    private static List<Long> registeredVersions(WorkflowRegistry registry, String definitionId) {
        return registry.allRegistered().stream()
                .filter(runtime -> runtime.plan().definitionId().equals(definitionId))
                .map(runtime -> runtime.plan().definitionVersion())
                .sorted()
                .toList();
    }

    private static long activeCount(DefinitionScenario scenario, String definitionId) {
        return await(scenario.admin().preparedQuery("""
                                SELECT count(*) AS total FROM workflow_definitions
                                WHERE definition_id = $1 AND status = 'ACTIVE'
                                """).execute(Tuple.of(definitionId)))
                .iterator()
                .next()
                .getLong("total");
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
    private static void installPromotionFailure(
            DefinitionScenario scenario, String definitionId, long promoted, long demoted, String marker) {
        removeFailure(scenario, "fail_test_reinstate_promotion");
        scenario.onClose(() -> removeFailure(scenario, "fail_test_reinstate_promotion"));
        await(scenario.admin()
                .query("""
                CREATE FUNCTION fail_test_reinstate_promotion() RETURNS trigger AS $$
                BEGIN
                    IF NEW.definition_id = '%s' AND NEW.definition_version = %d
                       AND OLD.status = 'SUPERSEDED' AND NEW.status = 'ACTIVE' THEN
                        IF (
                            SELECT status FROM workflow_definitions
                            WHERE tenant_key IS NOT DISTINCT FROM NEW.tenant_key
                              AND definition_id = NEW.definition_id AND definition_version = %d
                        ) = 'SUPERSEDED' THEN
                            RAISE EXCEPTION '%s' USING ERRCODE = '23514';
                        END IF;
                        RAISE EXCEPTION 'the version was promoted before the previous winner was demoted'
                            USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(definitionId, promoted, demoted, marker))
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
