// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowDefinitionPropagationPoller;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Stored;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that a node's registry converges on the committed definition table through its periodic reconciliation,
 * which the tests trigger directly: lifecycle changes made on one node reach another, rows that fail to apply are
 * counted and retried until they apply, a current owner is never evicted by a retired row of another owner, and a
 * reinstated code version is restored on a peer from that peer's own deployed code, hash-verified.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionPropagationIT {

    @WorkflowContract
    public interface LifecycleDocV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface DeprecatedFirstDocV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface OversizedDocV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface OwnerDocV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface PeerCode {}

    /** A contract that no document or deployed code of these tests names. */
    public interface ForeignContractCode {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_propagation_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("a peer converges on every lifecycle state, and rows that cannot apply are counted and retried")
    void convergesTwoNodesAndRetriesFailedRows(Vertx vertx) {
        String id = "propagation-it-lifecycle";
        String deprecatedId = "propagation-it-deprecated-first";
        String oversizedId = "propagation-it-oversized";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        DefinitionKey deprecatedKey = new DefinitionKey(WorkflowScopes.DEFAULT, deprecatedId, 1);
        DefinitionKey oversizedKey = new DefinitionKey(WorkflowScopes.DEFAULT, oversizedId, 1);
        Instant loadedAt = Instant.parse("2026-10-19T09:00:00Z");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db);
                PollerLog log = new PollerLog()) {
            scenario.ownRows(id, deprecatedId, oversizedId);

            // Given node A, and node B on the same database with its database access observed
            WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
            Boot a = scenario.node(none());
            Boot b = scenario.attempt(none(), unobserved(), List.of(), observer, 10);
            assertThat(b.failure()).as("startup of node B").isNull();
            WorkflowDefinitionService aService = a.app().definitionService();
            WorkflowRegistry bRegistry = b.app().workflowRegistry();
            long coolingSeconds = a.app().workflowDefinitionsConfig().maxStalenessSeconds();

            // And a valid runtime on B for version 2 of the first document's id under another contract, which
            // the registry refuses to mix with the contract the document names
            bRegistry.register(code(id, 2, ForeignContractCode.class, "done"));

            // And a document that A loaded and activated
            String hash = await(aService.load(
                            WorkflowScopes.DEFAULT,
                            documentBytes(id, 1, LifecycleDocV1.class),
                            DocumentFormat.YAML,
                            source("lifecycle", loadedAt)))
                    .planHash();
            await(aService.publish(key));
            Stored activated = scenario.stored(id, 1);
            assertThat(activated.status()).isEqualTo("AVAILABLE");

            // When B reconciles while its registry holds the id under another contract
            await(b.app().definitionPropagationPoller().pollNow());

            // Then the row is counted as failing, is not in the view or the registry, and the committed row is intact
            assertThat(b.app().definitionRegistryView().applyFailures()).isEqualTo(1);
            assertThat(viewed(b, key)).isEmpty();
            assertThat(registeredVersions(bRegistry, id)).containsExactly(2L);
            assertThat(scenario.stored(id, 1)).isEqualTo(activated);
            assertThat(log.problems()).anySatisfy(text -> assertThat(text).contains(id));

            // And reconciling again keeps counting the one row that still fails, not a running total
            await(b.app().definitionPropagationPoller().pollNow());
            assertThat(b.app().definitionRegistryView().applyFailures()).isEqualTo(1);

            // When the other runtime is removed and B reconciles again
            bRegistry.unregister(new DefinitionKey(WorkflowScopes.DEFAULT, id, 2));
            await(b.app().definitionPropagationPoller().pollNow());

            // Then the retry applies the row: nothing fails, and the view reports its key, status, hash and change time
            assertThat(b.app().definitionRegistryView().applyFailures()).isZero();
            assertThat(viewed(b, key)).hasValueSatisfying(record -> {
                assertThat(record.status()).isEqualTo(DefinitionStatus.AVAILABLE);
                assertThat(record.planHash()).isEqualTo(hash);
                assertThat(record.statusChangedAt())
                        .isEqualTo(activated.changedAt().toInstant());
            });
            assertThat(bRegistry
                            .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1))
                            .plan()
                            .planHash())
                    .isEqualTo(hash);
            assertThat(bRegistry.registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                    .isPresent();
            assertThat(log.informational()).anySatisfy(text -> assertThat(text).contains(id));

            // When A deprecates the document and B reconciles
            await(aService.deprecate(key));
            await(b.app().definitionPropagationPoller().pollNow());

            // Then B reports it deprecated, still resolvable by pin and no longer current
            Stored deprecated = scenario.stored(id, 1);
            assertThat(deprecated.status()).isEqualTo("DEPRECATED");
            assertThat(viewed(b, key)).hasValueSatisfying(record -> {
                assertThat(record.status()).isEqualTo(DefinitionStatus.DEPRECATED);
                assertThat(record.planHash()).isEqualTo(hash);
                assertThat(record.statusChangedAt())
                        .isEqualTo(deprecated.changedAt().toInstant());
            });
            assertThat(bRegistry.isDeprecated(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                    .isTrue();
            assertThat(bRegistry
                            .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1))
                            .plan()
                            .planHash())
                    .isEqualTo(hash);
            assertThat(b.app().definitionRegistryView().applyFailures()).isZero();

            // When the deprecation has cooled by the database clock, A retires the document and B reconciles
            scenario.ageStatus(key, coolingSeconds + 60);
            await(aService.retire(key));
            await(b.app().definitionPropagationPoller().pollNow());

            // Then B no longer serves it, with its registered definition, and the view no longer lists it
            assertThat(scenario.stored(id, 1).status()).isEqualTo("RETIRED");
            assertThat(registeredVersions(bRegistry, id)).isEmpty();
            assertThat(bRegistry.registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                    .isEmpty();
            assertThat(viewed(b, key)).isEmpty();
            assertThat(b.app().definitionRegistryView().applyFailures()).isZero();

            // Given a document that A loaded, activated and deprecated before B ever reconciled it
            String deprecatedHash = await(aService.load(
                            WorkflowScopes.DEFAULT,
                            documentBytes(deprecatedId, 1, DeprecatedFirstDocV1.class),
                            DocumentFormat.YAML,
                            source("deprecated-first", loadedAt)))
                    .planHash();
            await(aService.publish(deprecatedKey));
            await(aService.deprecate(deprecatedKey));
            Stored deprecatedFirst = scenario.stored(deprecatedId, 1);
            assertThat(deprecatedFirst.status()).isEqualTo("DEPRECATED");

            // When B reconciles
            await(b.app().definitionPropagationPoller().pollNow());

            // Then B restored it and marked it deprecated, so instances pinned to it can resume
            assertThat(bRegistry
                            .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, deprecatedId, 1))
                            .plan()
                            .planHash())
                    .isEqualTo(deprecatedHash);
            assertThat(bRegistry.isDeprecated(new DefinitionKey(WorkflowScopes.DEFAULT, deprecatedId, 1)))
                    .isTrue();
            assertThat(viewed(b, deprecatedKey)).hasValueSatisfying(record -> {
                assertThat(record.status()).isEqualTo(DefinitionStatus.DEPRECATED);
                assertThat(record.planHash()).isEqualTo(deprecatedHash);
                assertThat(record.statusChangedAt())
                        .isEqualTo(deprecatedFirst.changedAt().toInstant());
            });
            assertThat(b.app().definitionRegistryView().applyFailures()).isZero();

            // Given an active row whose stored text is larger than the configured policy allows
            int oversizedBytes = b.app().workflowDefinitionsConfig().maxStoredDocumentSizeBytes() + 1;
            scenario.execute("""
                    INSERT INTO workflow_definitions
                        (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status,
                         document, document_format, document_digest, source_type, loaded_at)
                    VALUES ('default', $1, 1, 'DOCUMENT', 'example.Contract', 'example.State', repeat('a', 64), 'AVAILABLE', repeat('x', $2),
                            'YAML', encode(sha256(convert_to(repeat('x', $2), 'UTF8')), 'hex'),
                            'propagation-test', now())
                    """, Tuple.of(oversizedId, oversizedBytes));

            // When B reconciles
            await(b.app().definitionPropagationPoller().pollNow());

            // Then the row is refused before its body was projected, counted, kept out of the registry and logged
            assertThat(b.app().definitionRegistryView().applyFailures()).isEqualTo(1);
            assertThat(observer.queries())
                    .anySatisfy(query -> assertThat(query.rows()).anySatisfy(row -> {
                        assertThat(row.definitionId()).isEqualTo(oversizedId);
                        assertThat(row.documentBody()).isNull();
                        assertThat(((Number) row.documentSizeBytes()).intValue())
                                .isEqualTo(oversizedBytes);
                    }));
            assertThat(observer.queries())
                    .flatExtracting(WorkflowDefinitionSqlObserver.ObservedQuery::rows)
                    .filteredOn(row -> oversizedId.equals(row.definitionId()))
                    .allSatisfy(row -> assertThat(row.documentBody()).isNull());
            assertThat(viewed(b, oversizedKey)).isEmpty();
            assertThat(registeredVersions(bRegistry, oversizedId)).isEmpty();
            assertThat(log.problems()).anySatisfy(text -> assertThat(text).contains(oversizedId));

            // And it is retried and still counted once on the next reconciliation
            await(b.app().definitionPropagationPoller().pollNow());
            assertThat(b.app().definitionRegistryView().applyFailures()).isEqualTo(1);

            // When the stored row is brought within the policy and B reconciles
            String oversizedText = DefinitionScenario.documentText(oversizedId, 1, OversizedDocV1.class);
            String oversizedHash = a.app()
                    .definitionPipeline()
                    .load(new DefinitionResource(
                            oversizedText.getBytes(StandardCharsets.UTF_8),
                            DocumentFormat.YAML,
                            source("oversized", loadedAt)))
                    .planHash();
            scenario.execute("""
                    UPDATE workflow_definitions
                    SET document = $2, plan_hash = $3,
                        document_digest = encode(sha256(convert_to($2::text, 'UTF8')), 'hex')
                    WHERE scope_key = 'default' AND definition_id = $1 AND definition_version = 1
                    """, Tuple.of(oversizedId, oversizedText, oversizedHash));
            await(b.app().definitionPropagationPoller().pollNow());

            // Then the retry applies it and nothing is failing any more
            assertThat(b.app().definitionRegistryView().applyFailures()).isZero();
            assertThat(viewed(b, oversizedKey)).hasValueSatisfying(record -> {
                assertThat(record.status()).isEqualTo(DefinitionStatus.AVAILABLE);
                assertThat(record.planHash()).isEqualTo(oversizedHash);
            });
            assertThat(bRegistry
                            .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, oversizedId, 1))
                            .plan()
                            .planHash())
                    .isEqualTo(oversizedHash);

            // And no diagnostic carried any source text
            assertThat(log.allText()).noneSatisfy(text -> assertThat(text)
                    .containsAnyOf("reserve-inventory", "xxxxxxxxxx", "stateType"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownerCases")
    @DisplayName("repeated replay of a retired row never evicts the current owner of the same id, version and hash")
    void preservesCurrentOwnerDuringRetiredReplay(
            String label, String retiredTenant, String currentTenant, boolean reinstates, Vertx vertx) {
        String id = "propagation-it-owner";
        DefinitionKey retiredKey = new DefinitionKey(retiredTenant, id, 1);
        DefinitionKey currentKey = new DefinitionKey(currentTenant, id, 1);
        Instant loadedAt = Instant.parse("2026-10-19T09:00:00Z");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);
            Boot a = scenario.node(none());
            Boot b = scenario.node(none());
            WorkflowRegistry aRegistry = a.app().workflowRegistry();
            WorkflowDefinitionService aService = a.app().definitionService();
            WorkflowDefinitionService bService = b.app().definitionService();
            long coolingSeconds = a.app().workflowDefinitionsConfig().maxStalenessSeconds();

            // Given, when the current owner is reinstated, that node B had already retired that owner's version
            if (reinstates) {
                TenantRowSeed.seed(
                        b.app(),
                        currentTenant,
                        documentBytes(id, 1, OwnerDocV1.class),
                        source("current-owner", loadedAt),
                        DefinitionStatus.CANDIDATE);
                await(bService.publish(currentKey));
                await(bService.deprecate(currentKey));
                scenario.ageStatus(currentKey, coolingSeconds + 60);
                await(bService.retire(currentKey));
                assertThat(scenario.stored(currentTenant, id, 1).status()).isEqualTo("RETIRED");
            }

            // And node A loaded and activated the other owner's version, so A holds its real runtime
            TenantRowSeed.seed(
                    a.app(),
                    retiredTenant,
                    documentBytes(id, 1, OwnerDocV1.class),
                    source("retiring-owner", loadedAt),
                    DefinitionStatus.CANDIDATE);
            String hash = a.app()
                    .definitionPipeline()
                    .load(new DefinitionResource(
                            documentBytes(id, 1, OwnerDocV1.class),
                            DocumentFormat.YAML,
                            source("retiring-owner", loadedAt)))
                    .planHash();
            await(aService.publish(retiredKey));
            RuntimeWorkflow staleRuntime = aRegistry.resolve(retiredKey);

            // And node B, which never held it, durably retired that version
            await(bService.deprecate(retiredKey));
            scenario.ageStatus(retiredKey, coolingSeconds + 60);
            await(bService.retire(retiredKey));
            assertThat(scenario.stored(retiredTenant, id, 1).status()).isEqualTo("RETIRED");

            // And node B then gave the same id, version and hash to the current owner
            if (reinstates) {
                await(bService.reinstate(currentKey));
            } else {
                TenantRowSeed.seed(
                        b.app(),
                        currentTenant,
                        documentBytes(id, 1, OwnerDocV1.class),
                        source("current-owner", loadedAt),
                        DefinitionStatus.CANDIDATE);
                await(bService.publish(currentKey));
            }
            Stored current = scenario.stored(currentTenant, id, 1);
            assertThat(current.status()).isEqualTo("AVAILABLE");
            assertThat(current.planHash()).isEqualTo(hash);

            // And before A reconciles it still serves the stale runtime of the retired owner
            assertThat(aRegistry.resolve(retiredKey)).isSameAs(staleRuntime);

            // When A reconciles
            await(a.app().definitionPropagationPoller().pollNow());

            // Then the genuinely stale runtime is gone and the current owner's is published in its place
            RuntimeWorkflow currentRuntime = aRegistry.resolve(currentKey);
            assertThat(currentRuntime).isNotSameAs(staleRuntime);
            assertThat(currentRuntime.plan().planHash()).isEqualTo(hash);
            assertThat(aRegistry.find(retiredKey)).isEmpty();
            var registeredDefinition = aRegistry.registeredDefinition(currentKey);
            assertThat(registeredDefinition).isPresent();

            // And the view reports the current owner only, with nothing failing
            assertThat(recordsOf(a, id))
                    .extracting(WorkflowDefinitionRecord::key)
                    .containsExactly(currentKey);
            assertThat(recordsOf(a, id)).singleElement().satisfies(record -> {
                assertThat(record.status()).isEqualTo(DefinitionStatus.AVAILABLE);
                assertThat(record.planHash()).isEqualTo(hash);
                assertThat(record.statusChangedAt())
                        .isEqualTo(current.changedAt().toInstant());
            });
            assertThat(a.app().definitionRegistryView().applyFailures()).isZero();

            // When A reconciles again twice, meeting the retired row and the current row each time
            await(a.app().definitionPropagationPoller().pollNow());
            await(a.app().definitionPropagationPoller().pollNow());

            // Then the retired row never evicts the current owner or its registered definition
            assertThat(aRegistry.resolve(currentKey)).isSameAs(currentRuntime);
            assertThat(aRegistry.registeredDefinition(currentKey)).isEqualTo(registeredDefinition);
            assertThat(recordsOf(a, id))
                    .extracting(WorkflowDefinitionRecord::key)
                    .containsExactly(currentKey);
            assertThat(a.app().definitionRegistryView().applyFailures()).isZero();
            assertThat(scenario.stored(currentTenant, id, 1)).isEqualTo(current);
        }
    }

    static Stream<Arguments> ownerCases() {
        // The listing is ordered by tenant, so the retired owner's row is met first or last by its tenant name
        return Stream.of(
                Arguments.of("retired row first, current owner activated", "tenant-a", "tenant-b", false),
                Arguments.of("current row first, current owner activated", "tenant-b", "tenant-a", false),
                Arguments.of("retired row first, current owner reinstated", "tenant-a", "tenant-b", true),
                Arguments.of("current row first, current owner reinstated", "tenant-b", "tenant-a", true));
    }

    /** What a peer meets when the code version was reinstated, named by the first status the peer observes. */
    enum PeerCase {
        AVAILABLE_ALONE,
        AVAILABLE_BESIDE_HIGHER,
        DEPRECATED_FIRST,
        MISSING_CODE,
        DRIFTED_CODE
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PeerCase.class)
    @DisplayName("a reinstated code version is restored on a peer from its own deployed code, or fails and is counted")
    void restoresReinstatedCodeOnPeer(PeerCase peerCase, Vertx vertx) {
        String id = "propagation-it-code";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        boolean higherLive = peerCase == PeerCase.AVAILABLE_BESIDE_HIGHER;
        WorkflowDefinition<?, ?> deployedOnA = code(id, 1, PeerCode.class, "done");
        WorkflowDefinition<?, ?> deployedOnB = peerCase == PeerCase.DRIFTED_CODE
                ? code(id, 1, PeerCode.class, "drifted-done")
                : code(id, 1, PeerCode.class, "done");
        WorkflowDefinition<?, ?> higherOnA = code(id, 2, PeerCode.class, "done");
        WorkflowDefinition<?, ?> higherOnB = code(id, 2, PeerCode.class, "done");
        String hash = planHashOf(deployedOnA);
        List<WorkflowDefinition<?, ?>> codeOnA = higherLive ? List.of(deployedOnA, higherOnA) : List.of(deployedOnA);
        List<WorkflowDefinition<?, ?>> codeOnB =
                switch (peerCase) {
                    case MISSING_CODE -> List.of();
                    case AVAILABLE_BESIDE_HIGHER -> List.of(deployedOnB, higherOnB);
                    default -> List.of(deployedOnB);
                };

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db);
                PollerLog log = new PollerLog()) {
            scenario.ownRows(id);

            // Given a retired code version, with a higher available one when the peer is to see it beside that version
            scenario.seedCode(id, 1, hash, "RETIRED");
            if (higherLive) scenario.seedCode(id, 2, planHashOf(higherOnA), "AVAILABLE");

            // And node A, which deploys the code and has retired the version from its registry
            Boot a = scenario.node(none(), unobserved(), codeOnA);
            WorkflowRegistry aRegistry = a.app().workflowRegistry();
            WorkflowDefinitionService aService = a.app().definitionService();

            // And node B, started while the row is retired and unpinned, with its own deployed code, or none, or
            // different code, and with the retired version removed from its registry
            Boot b = scenario.node(none(), unobserved(), codeOnB);
            WorkflowRegistry bRegistry = b.app().workflowRegistry();
            assertThat(registeredVersions(bRegistry, id)).isEqualTo(higherLive ? List.of(2L) : List.of());
            assertThat(bRegistry.registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                    .isEmpty();
            assertThatThrownBy(() -> bRegistry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                    .isInstanceOfAny(
                            WorkflowVersionPinUnavailableException.class, WorkflowDefinitionMissingException.class);

            if (peerCase == PeerCase.DRIFTED_CODE) {
                // And the two nodes' compiled hashes for the version really differ
                String driftedHash = planHashOf(deployedOnB);
                assertThat(driftedHash).isNotEqualTo(hash);

                // And B's startup reconciliation, which met no pinned instance, recorded its own hash on the row
                assertThat(scenario.stored(id, 1).status()).isEqualTo("RETIRED");
                assertThat(scenario.stored(id, 1).planHash()).isEqualTo(driftedHash);

                // And A's count-guarded reconciliation records A's matching hash again, leaving the row retired
                Instant now = Instant.now();
                ReconcileOutcome outcome = await(a.app()
                        .definitionRegistryStore()
                        .upsertCode(new WorkflowDefinitionRecord(
                                key,
                                DefinitionKind.CODE,
                                "example.Contract",
                                "example.State",
                                hash,
                                DefinitionStatus.AVAILABLE,
                                new DefinitionProvenance("code", null, null, null, null, now),
                                null,
                                now,
                                now)));
                assertThat(outcome).isEqualTo(ReconcileOutcome.REHASHED);
                assertThat(scenario.stored(id, 1).status()).isEqualTo("RETIRED");
                assertThat(scenario.stored(id, 1).planHash()).isEqualTo(hash);
            }

            // And, for the matching case, a valid runtime on B for version 2 of the id under another contract
            if (peerCase == PeerCase.AVAILABLE_ALONE) {
                bRegistry.register(code(id, 2, ForeignContractCode.class, "done"));
            }

            // When A reinstates the version through the common lifecycle
            await(aService.reinstate(key));
            if (peerCase == PeerCase.DEPRECATED_FIRST) await(aService.deprecate(key));
            Stored committed = scenario.stored(id, 1);
            String expectedStatus = peerCase == PeerCase.DEPRECATED_FIRST ? "DEPRECATED" : "AVAILABLE";
            assertThat(committed.status()).isEqualTo(expectedStatus);
            assertThat(committed.planHash()).isEqualTo(hash);

            if (peerCase == PeerCase.MISSING_CODE || peerCase == PeerCase.DRIFTED_CODE) {
                Reason expectedReason = peerCase == PeerCase.MISSING_CODE ? Reason.CODE_UNAVAILABLE : Reason.DRIFT;

                // And B then reconciles, without reinstating anything itself
                await(b.app().definitionPropagationPoller().pollNow());

                // Then the row is counted as failing, with the key and the typed reason logged
                assertThat(b.app().definitionRegistryView().applyFailures()).isEqualTo(1);
                assertThat(log.problems())
                        .anySatisfy(text -> assertThat(text).contains(id).contains(expectedReason.name()));

                // And nothing was published for it, and the committed row and its hash were not touched
                assertThat(registeredVersions(bRegistry, id)).isEmpty();
                assertThat(bRegistry.registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                        .isEmpty();
                assertThat(viewed(b, key)).isEmpty();
                assertThat(scenario.stored(id, 1)).isEqualTo(committed);
                assertThat(scenario.stored(id, 1).planHash()).isEqualTo(hash);

                // And the row stays counted, once, and untouched on the next reconciliation
                await(b.app().definitionPropagationPoller().pollNow());
                assertThat(b.app().definitionRegistryView().applyFailures()).isEqualTo(1);
                assertThat(registeredVersions(bRegistry, id)).isEmpty();
                assertThat(scenario.stored(id, 1)).isEqualTo(committed);
                return;
            }

            if (peerCase == PeerCase.AVAILABLE_ALONE) {
                // And B reconciles while its registry holds the id under another contract
                await(b.app().definitionPropagationPoller().pollNow());

                // Then the actual registration failure is counted, nothing is published, and the row is intact
                assertThat(b.app().definitionRegistryView().applyFailures()).isEqualTo(1);
                assertThat(registeredVersions(bRegistry, id)).containsExactly(2L);
                assertThat(bRegistry.registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                        .isEmpty();
                assertThat(viewed(b, key)).isEmpty();
                assertThat(scenario.stored(id, 1)).isEqualTo(committed);

                // When the other runtime is removed between polls
                bRegistry.unregister(new DefinitionKey(WorkflowScopes.DEFAULT, id, 2));
            }

            // And B reconciles, restoring from its unchanged deployed code
            await(b.app().definitionPropagationPoller().pollNow());

            // Then the version is restored from B's own deployed definition, with its hash and registered definition
            assertThat(b.app().definitionRegistryView().applyFailures()).isZero();
            RuntimeWorkflow restored = bRegistry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1));
            assertThat(restored.plan().planHash()).isEqualTo(hash);
            assertThat(bRegistry.registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                    .containsSame(deployedOnB);

            // And the view reports its key, status, hash and change time
            assertThat(viewed(b, key)).hasValueSatisfying(record -> {
                assertThat(record.kind()).isEqualTo(DefinitionKind.CODE);
                assertThat(record.status()).isEqualTo(DefinitionStatus.valueOf(expectedStatus));
                assertThat(record.planHash()).isEqualTo(hash);
                assertThat(record.statusChangedAt())
                        .isEqualTo(committed.changedAt().toInstant());
            });
            assertThat(scenario.stored(id, 1)).isEqualTo(committed);

            // And pinned resolution respects the higher version and the deprecation
            assertThat(bRegistry.isDeprecated(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                    .isEqualTo(peerCase == PeerCase.DEPRECATED_FIRST);
            if (peerCase == PeerCase.AVAILABLE_ALONE) {
                assertThat(bRegistry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                        .isSameAs(restored);
            } else if (peerCase == PeerCase.AVAILABLE_BESIDE_HIGHER) {
                assertThat(bRegistry
                                .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 2))
                                .plan()
                                .definitionVersion())
                        .isEqualTo(2);
            }

            if (peerCase == PeerCase.DEPRECATED_FIRST) {
                // When a new instance is started explicitly on the deprecated version
                Throwable refused = failureOf(b.app()
                        .workflowOperations()
                        .start(new StartCommand(
                                id,
                                PeerCode.class.getName(),
                                new CodeStart("deprecated"),
                                "deprecated-start",
                                null,
                                null,
                                1L,
                                null,
                                null)));

                // Then the start is refused as deprecated and no instance was written
                assertThat(refused).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(Reason.DEPRECATED);
                });
                assertThat(scenario.instanceCount(id)).isZero();
            } else {
                // When a new instance is started explicitly on the restored version
                WorkflowInstanceId started = await(b.app()
                        .workflowOperations()
                        .start(new StartCommand(
                                id,
                                PeerCode.class.getName(),
                                new CodeStart("restored"),
                                "restored-start",
                                null,
                                null,
                                1L,
                                null,
                                null)));
                WorkflowView view = await(b.app().workflowOperations().query(started));

                // Then it ran on the restored hash to a terminal status, leaving no pinned instance
                assertThat(view.instance().status()).isEqualTo(WorkflowStatus.COMPLETED);
                assertThat(view.instance().definitionVersion()).isEqualTo(1);
                assertThat(view.instance().planHash()).isEqualTo(hash);
                assertThat(scenario.livePinCount(id, 1, hash)).isZero();

                // And A deprecates the version
                await(aService.deprecate(key));
            }

            // When the deprecation has cooled by the database clock, A retires the version and B reconciles
            scenario.ageStatus(key, a.app().workflowDefinitionsConfig().maxStalenessSeconds() + 60);
            await(aService.retire(key));
            assertThat(scenario.stored(id, 1).status()).isEqualTo("RETIRED");
            await(b.app().definitionPropagationPoller().pollNow());

            // Then B removes the runtime it restored, with its registered definition, and drops it from the view
            assertThat(registeredVersions(bRegistry, id)).isEqualTo(higherLive ? List.of(2L) : List.of());
            assertThatThrownBy(() -> bRegistry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                    .isInstanceOfAny(
                            WorkflowVersionPinUnavailableException.class, WorkflowDefinitionMissingException.class);
            assertThat(bRegistry.registeredDefinition(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1)))
                    .isEmpty();
            assertThat(viewed(b, key)).isEmpty();
            assertThat(b.app().definitionRegistryView().applyFailures()).isZero();
            assertThat(registeredVersions(aRegistry, id)).isEqualTo(higherLive ? List.of(2L) : List.of());
        }
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }

    private static BootObservation unobserved() {
        return new BootObservation("propagation-it-unused", 1, "unused", false);
    }

    private static byte[] documentBytes(String definitionId, long version, Class<?> contract) {
        return DefinitionScenario.documentText(definitionId, version, contract).getBytes(StandardCharsets.UTF_8);
    }

    /** The node's view entry for the key, or empty when the node has not applied it. */
    private static Optional<WorkflowDefinitionRecord> viewed(Boot node, DefinitionKey key) {
        return node.app().definitionRegistryView().registered().stream()
                .filter(record -> record.key().equals(key))
                .findFirst();
    }

    /** The node's view entries of one definition id. */
    private static List<WorkflowDefinitionRecord> recordsOf(Boot node, String definitionId) {
        return node.app().definitionRegistryView().registered().stream()
                .filter(record -> record.key().definitionId().equals(definitionId))
                .toList();
    }

    private static List<Long> registeredVersions(WorkflowRegistry registry, String definitionId) {
        return registry.allRegistered().stream()
                .filter(runtime -> runtime.plan().definitionId().equals(definitionId))
                .map(runtime -> runtime.plan().definitionVersion())
                .sorted()
                .toList();
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

    /** Collects what the propagation poller logs while it is open. */
    private static final class PollerLog implements AutoCloseable {

        private final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(WorkflowDefinitionPropagationPoller.class.getName());
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final Level previousLevel = logger.getLevel();

        private PollerLog() {
            appender.start();
            logger.setLevel(Level.INFO);
            logger.addAppender(appender);
        }

        /** The text of every event at warning level or above, including the message of an attached failure. */
        List<String> problems() {
            return texts(Level.WARN);
        }

        /** The text of every event at information level. */
        List<String> informational() {
            return events().stream()
                    .filter(event -> event.getLevel() == Level.INFO)
                    .map(PollerLog::text)
                    .toList();
        }

        List<String> allText() {
            return texts(Level.ALL);
        }

        private List<String> texts(Level least) {
            return events().stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(least))
                    .map(PollerLog::text)
                    .toList();
        }

        private List<ILoggingEvent> events() {
            return new ArrayList<>(appender.list);
        }

        private static String text(ILoggingEvent event) {
            String failure = event.getThrowableProxy() == null
                    ? ""
                    : " " + event.getThrowableProxy().getMessage();
            return event.getFormattedMessage() + failure;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
}
