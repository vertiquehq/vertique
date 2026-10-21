// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionRaceFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.postgresql.registry.DefinitionRaceFixtures.Fixture;
import dev.vertique.workflow.postgresql.registry.DefinitionRaceFixtures.IdLockBarrier;
import dev.vertique.workflow.postgresql.registry.DefinitionRaceFixtures.Node;
import dev.vertique.workflow.postgresql.registry.DefinitionRaceFixtures.Stored;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that rows of different scopes and versions of one definition id coexist and are available together,
 * and that one row's claim, completion or reconciliation never changes another, across independent application
 * graphs sharing one database.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionOwnershipIT {

    @WorkflowContract
    public interface RaceV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface HeldV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface V3FirstVersion3 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface V3FirstVersion4 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface V4FirstVersion3 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface V4FirstVersion4 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface StaleV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface CandidatesV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface ReconcileHeldV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface ReconcileFreshV1 extends OrderDocumentSignals {}

    @WorkflowContract
    public interface ReconcileRaceV1 extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_ownership_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("two scopes publishing one id and version at once are both available, each with its own row")
    void concurrentScopePublicationsOfOneIdAndVersionAreBothAvailable(Vertx vertx) {
        String raceId = "ownership-race-doc";
        String heldId = "ownership-held-doc";
        DefinitionKey raceA = new DefinitionKey("tenant-a", raceId, 1);
        DefinitionKey raceB = new DefinitionKey("tenant-b", raceId, 1);
        DefinitionKey heldA = new DefinitionKey("tenant-a", heldId, 1);
        DefinitionKey heldB = new DefinitionKey("tenant-b", heldId, 1);

        try (Fixture fixture = new Fixture(vertx, db)) {
            Node nodeA = fixture.node();
            Node nodeB = fixture.node();
            fixture.ownRows(raceId, heldId);

            // Given an id without any row, and two candidates loaded while a barrier holds the id lock
            IdLockBarrier loadBarrier = fixture.holdIdLock(raceId);
            Future<DefinitionKey> loadA = TenantRowSeed.insertCandidate(
                    nodeA.app(), "tenant-a", document(raceId, 1, RaceV1.class), DocumentFormat.YAML, source("race-a"));
            Future<DefinitionKey> loadB = TenantRowSeed.insertCandidate(
                    nodeB.app(), "tenant-b", document(raceId, 1, RaceV1.class), DocumentFormat.YAML, source("race-b"));
            loadBarrier.awaitQueued(2);
            assertThat(fixture.stored(raceId)).isEmpty();
            loadBarrier.release();
            await(loadA);
            await(loadB);
            String planHash = planHash(nodeA, document(raceId, 1, RaceV1.class), "race-a");

            // Then both candidates coexist
            assertThat(fixture.stored(raceId))
                    .extracting(Stored::scopeKey, Stored::status, Stored::token)
                    .containsExactly(tuple("tenant-a", "CANDIDATE", null), tuple("tenant-b", "CANDIDATE", null));

            // When both scopes publish at the same time and both claims are queued behind the id lock
            IdLockBarrier publicationBarrier = fixture.holdIdLock(raceId);
            Future<Void> publicationA = nodeA.service().publish(raceA);
            Future<Void> publicationB = nodeB.service().publish(raceB);
            publicationBarrier.awaitQueued(2);
            publicationBarrier.release();
            await(publicationA);
            await(publicationB);

            // Then both publications succeed, both rows are available and no claim is left behind
            assertThat(fixture.stored(raceId))
                    .extracting(Stored::scopeKey, Stored::status, Stored::token)
                    .containsExactly(tuple("tenant-a", "AVAILABLE", null), tuple("tenant-b", "AVAILABLE", null));
            assertThat(fixture.stored(raceId)).extracting(Stored::planHash).containsOnly(planHash);

            // And each node holds the executable registration of the scope it published
            assertThat(nodeA.registry().resolve(raceA).plan().planHash()).isEqualTo(planHash);
            assertThat(nodeB.registry().resolve(raceB).plan().planHash()).isEqualTo(planHash);
            assertThat(nodeA.registry().registeredDefinition(raceB)).isEmpty();
            assertThat(nodeB.registry().registeredDefinition(raceA)).isEmpty();

            // Given a second id where tenant-a's claim is committed and its completion is held back
            TenantRowSeed.seed(
                    nodeA.app(),
                    "tenant-a",
                    document(heldId, 1, HeldV1.class),
                    source("held-a"),
                    DefinitionStatus.CANDIDATE);
            TenantRowSeed.seed(
                    nodeB.app(),
                    "tenant-b",
                    document(heldId, 1, HeldV1.class),
                    source("held-b"),
                    DefinitionStatus.CANDIDATE);
            WorkflowDefinitionSqlObserver.CompletionLockGate gate =
                    nodeA.observer().holdNextCompletionLockAfterClaim();
            fixture.onClose(gate::release);
            Future<Void> heldPublication = nodeA.service().publish(heldA);
            await(gate.observed());
            assertThat(fixture.stored(heldId))
                    .extracting(Stored::scopeKey, Stored::status)
                    .containsExactly(tuple("tenant-a", "PUBLISHING"), tuple("tenant-b", "CANDIDATE"));

            // When tenant-b publishes the same id while tenant-a's fresh claim is outstanding
            await(nodeB.service().publish(heldB));

            // Then tenant-b is available at once and tenant-a's claim is untouched
            assertThat(nodeB.registry().resolve(heldB)).isNotNull();
            assertThat(fixture.stored(heldId))
                    .extracting(Stored::scopeKey, Stored::status)
                    .containsExactly(tuple("tenant-a", "PUBLISHING"), tuple("tenant-b", "AVAILABLE"));

            // And the held completion still finishes
            gate.release();
            await(heldPublication);
            assertThat(fixture.stored(heldId))
                    .extracting(Stored::scopeKey, Stored::status, Stored::token)
                    .containsExactly(tuple("tenant-a", "AVAILABLE", null), tuple("tenant-b", "AVAILABLE", null));
            assertThat(nodeA.registry().resolve(heldA)).isNotNull();
        }
    }

    private record OrderScenario(
            String definitionId,
            Class<?> version3Contract,
            Class<?> version4Contract,
            boolean version3CompletesFirst) {}

    @Test
    @DisplayName("concurrent publications of two versions of one scope leave both versions available")
    void concurrentPublicationsOfTwoVersionsLeaveBothAvailable(Vertx vertx) {
        List<OrderScenario> scenarios = List.of(
                new OrderScenario(
                        "tenant-a:ownership-v3-first-doc", V3FirstVersion3.class, V3FirstVersion4.class, true),
                new OrderScenario(
                        "tenant-a:ownership-v4-first-doc", V4FirstVersion3.class, V4FirstVersion4.class, false));

        try (Fixture fixture = new Fixture(vertx, db)) {
            Node nodeA = fixture.node();
            Node nodeB = fixture.node();

            for (OrderScenario scenario : scenarios) {
                String id = scenario.definitionId();
                DefinitionKey version3 = new DefinitionKey("tenant-a", id, 3);
                DefinitionKey version4 = new DefinitionKey("tenant-a", id, 4);
                fixture.ownRows(id);

                // Given two candidates of one scope, each publishing on its own node with completion held back
                CompiledDefinitionRef loaded3 = await(nodeA.service()
                        .load(
                                "tenant-a",
                                document(id, 3, scenario.version3Contract()),
                                DocumentFormat.YAML,
                                source(id + "-3")));
                CompiledDefinitionRef loaded4 = await(nodeB.service()
                        .load(
                                "tenant-a",
                                document(id, 4, scenario.version4Contract()),
                                DocumentFormat.YAML,
                                source(id + "-4")));
                WorkflowDefinitionSqlObserver.CompletionLockGate gate3 =
                        nodeA.observer().holdNextCompletionLockAfterClaim();
                WorkflowDefinitionSqlObserver.CompletionLockGate gate4 =
                        nodeB.observer().holdNextCompletionLockAfterClaim();
                fixture.onClose(gate3::release);
                fixture.onClose(gate4::release);
                Future<Void> publication3 = nodeA.service().publish(version3);
                Future<Void> publication4 = nodeB.service().publish(version4);
                await(gate3.observed());
                await(gate4.observed());
                assertThat(fixture.stored(id))
                        .extracting(Stored::version, Stored::status)
                        .containsExactly(tuple(3L, "PUBLISHING"), tuple(4L, "PUBLISHING"));

                // When the barriers release the completions in the scenario's order
                if (scenario.version3CompletesFirst()) {
                    gate3.release();
                    await(publication3);
                    assertThat(fixture.stored(id))
                            .extracting(Stored::version, Stored::status)
                            .containsExactly(tuple(3L, "AVAILABLE"), tuple(4L, "PUBLISHING"));
                    gate4.release();
                    await(publication4);
                } else {
                    gate4.release();
                    await(publication4);
                    assertThat(fixture.stored(id))
                            .extracting(Stored::version, Stored::status)
                            .containsExactly(tuple(3L, "PUBLISHING"), tuple(4L, "AVAILABLE"));
                    gate3.release();
                    await(publication3);
                }

                // Then both versions are available, with no claim left behind
                assertThat(fixture.stored(id))
                        .extracting(Stored::version, Stored::status, Stored::token)
                        .containsExactly(tuple(3L, "AVAILABLE", null), tuple(4L, "AVAILABLE", null));
                assertThat(fixture.stored(id))
                        .extracting(Stored::version, Stored::planHash)
                        .containsExactly(tuple(3L, loaded3.planHash()), tuple(4L, loaded4.planHash()));

                // And each version stays pinnable on the node that published it
                RuntimeWorkflow pinned3 = nodeA.registry().resolve(new DefinitionKey("tenant-a", id, 3));
                RuntimeWorkflow pinned4 = nodeB.registry().resolve(new DefinitionKey("tenant-a", id, 4));
                assertThat(pinned3.plan().definitionVersion()).isEqualTo(3);
                assertThat(pinned3.plan().planHash()).isEqualTo(loaded3.planHash());
                assertThat(pinned4.plan().definitionVersion()).isEqualTo(4);
                assertThat(pinned4.plan().planHash()).isEqualTo(loaded4.planHash());
            }
        }
    }

    @Test
    @DisplayName("a completion from an abandoned claim of one scope never touches the row of another scope")
    void staleClaimOfOneScopeNeverTouchesAnotherScopesRow(Vertx vertx) {
        String id = "ownership-stale-doc";
        DefinitionKey tenantA = new DefinitionKey("tenant-a", id, 1);
        DefinitionKey tenantB = new DefinitionKey("tenant-b", id, 1);

        try (Fixture fixture = new Fixture(vertx, db)) {
            Node nodeA = fixture.node();
            Node nodeB = fixture.node();
            fixture.ownRows(id);
            TenantRowSeed.seed(
                    nodeA.app(),
                    "tenant-a",
                    document(id, 1, StaleV1.class),
                    source("stale-a"),
                    DefinitionStatus.CANDIDATE);
            TenantRowSeed.seed(
                    nodeB.app(),
                    "tenant-b",
                    document(id, 1, StaleV1.class),
                    source("stale-b"),
                    DefinitionStatus.CANDIDATE);

            // Given tenant-a's claim is committed, published locally and held before completion
            WorkflowDefinitionSqlObserver.CompletionLockGate gate =
                    nodeA.observer().holdNextCompletionLockAfterClaim();
            fixture.onClose(gate::release);
            Future<Void> staleActivation = nodeA.service().publish(tenantA);
            await(gate.observed());
            Stored claimed = fixture.stored(id).stream()
                    .filter(row -> "tenant-a".equals(row.scopeKey()))
                    .findFirst()
                    .orElseThrow();
            assertThat(claimed.status()).isEqualTo("PUBLISHING");
            assertThat(claimed.token()).isNotBlank();
            assertThat(nodeA.registry().resolve(tenantA)).isNotNull();

            // And that claim is older than twice the staleness bound when tenant-b then publishes the same id and
            // version
            fixture.ageClaim(nodeA.app().workflowDefinitionsConfig(), tenantA, claimed.token());
            await(nodeB.service().publish(tenantB));
            RuntimeWorkflow replacement = nodeB.registry().resolve(tenantB);
            var replacementDefinition = nodeB.registry().registeredDefinition(tenantB);
            Stored replacementRow = fixture.stored(id).stream()
                    .filter(row -> "tenant-b".equals(row.scopeKey()))
                    .findFirst()
                    .orElseThrow();
            assertThat(replacementRow.status()).isEqualTo("AVAILABLE");
            assertThat(replacementRow.planHash()).isEqualTo(claimed.planHash());

            // When tenant-a's claim token completes
            gate.release();
            await(staleActivation);

            // Then tenant-a's row is available and tenant-b's row is untouched
            assertThat(fixture.stored(id))
                    .extracting(Stored::scopeKey, Stored::status, Stored::token)
                    .containsExactly(tuple("tenant-a", "AVAILABLE", null), tuple("tenant-b", "AVAILABLE", null));
            assertThat(fixture.stored(id))
                    .filteredOn(row -> "tenant-b".equals(row.scopeKey()))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.changedAt()).isEqualTo(replacementRow.changedAt());
                        assertThat(row.planHash()).isEqualTo(replacementRow.planHash());
                    });

            // And tenant-b's runtime identity, pinned resolution and registered definition are unchanged
            assertThat(nodeB.registry().resolve(tenantB)).isSameAs(replacement);
            assertThat(nodeB.registry().registeredDefinition(tenantB)).isEqualTo(replacementDefinition);
            assertThat(nodeB.registry().allRegistered())
                    .filteredOn(runtime -> runtime.plan().definitionId().equals(id))
                    .singleElement()
                    .isSameAs(replacement);

            // And each node holds only the registration of the scope it published
            assertThat(nodeA.registry().resolve(tenantA)).isNotNull();
            assertThat(nodeA.registry().registeredDefinition(tenantB)).isEmpty();
            assertThat(nodeB.registry().registeredDefinition(tenantA)).isEmpty();
        }
    }

    @Test
    @DisplayName("listing filters candidates by exact scope and status and never reads or claims document bytes")
    void filtersMetadataWithoutDocumentBytes(Vertx vertx) {
        String candidatesId = "ownership-candidates-doc";
        String mixId = "ownership-listing-mix";
        DefinitionKey global1 = new DefinitionKey(WorkflowScopes.DEFAULT, mixId, 1);
        DefinitionKey global2 = new DefinitionKey(WorkflowScopes.DEFAULT, mixId, 2);
        DefinitionKey tenantA1 = new DefinitionKey("tenant-a", mixId, 1);
        DefinitionKey tenantA2 = new DefinitionKey("tenant-a", mixId, 2);
        DefinitionKey tenantA3 = new DefinitionKey("tenant-a", mixId, 3);
        DefinitionKey tenantB1 = new DefinitionKey("tenant-b", mixId, 1);
        DefinitionKey tenantB2 = new DefinitionKey("tenant-b", mixId, 2);
        DefinitionKey tenantB3 = new DefinitionKey("tenant-b", mixId, 3);
        Map<DefinitionKey, DefinitionStatus> seeded = Map.of(
                global1, DefinitionStatus.CANDIDATE,
                global2, DefinitionStatus.AVAILABLE,
                tenantA1, DefinitionStatus.CANDIDATE,
                tenantA2, DefinitionStatus.AVAILABLE,
                tenantA3, DefinitionStatus.RETIRED,
                tenantB1, DefinitionStatus.CANDIDATE,
                tenantB2, DefinitionStatus.DEPRECATED,
                tenantB3, DefinitionStatus.PUBLISHING);

        try (Fixture fixture = new Fixture(vertx, db)) {
            Node node = fixture.node();
            fixture.ownRows(candidatesId, mixId);

            // Given the same id and version loaded as candidates by the global scope and two tenants
            await(node.service()
                    .load(
                            WorkflowScopes.DEFAULT,
                            document(candidatesId, 1, CandidatesV1.class),
                            DocumentFormat.YAML,
                            source("candidates-global")));
            for (String tenant : new String[] {"tenant-a", "tenant-b"}) {
                TenantRowSeed.seed(
                        node.app(),
                        tenant,
                        document(candidatesId, 1, CandidatesV1.class),
                        source("candidates-" + tenant),
                        DefinitionStatus.CANDIDATE);
            }

            // Then the candidates coexist, none of them claims the id, and each tenant lists only its own
            assertThat(fixture.stored(candidatesId))
                    .extracting(Stored::scopeKey, Stored::status, Stored::token)
                    .containsExactly(
                            tuple(WorkflowScopes.DEFAULT, "CANDIDATE", null),
                            tuple("tenant-a", "CANDIDATE", null),
                            tuple("tenant-b", "CANDIDATE", null));
            for (String tenant : new String[] {"tenant-a", "tenant-b"}) {
                assertThat(await(node.service().list(new DefinitionFilter(tenant, null, null))))
                        .filteredOn(row -> row.key().definitionId().equals(candidatesId))
                        .singleElement()
                        .satisfies(row -> {
                            assertThat(row.key()).isEqualTo(new DefinitionKey(tenant, candidatesId, 1));
                            assertThat(row.status()).isEqualTo(DefinitionStatus.CANDIDATE);
                            assertThat(row.provenance().sourceType()).isEqualTo("ownership-test");
                            assertThat(row.provenance().uri()).isEqualTo("memory:candidates-" + tenant);
                        });
            }

            // Given rows in several statuses for the global scope and two tenants
            for (DefinitionKey key : seeded.keySet()) {
                await(node.store().insertCandidate(candidate(key), documentCarrier(key)));
            }
            await(node.store().claim(tenantB3));
            for (Map.Entry<DefinitionKey, DefinitionStatus> row : seeded.entrySet()) {
                if (row.getValue() != DefinitionStatus.CANDIDATE && row.getValue() != DefinitionStatus.PUBLISHING) {
                    fixture.forceStatus(row.getKey(), row.getValue());
                }
            }
            assertThat(fixture.stored(mixId))
                    .extracting(Stored::scopeKey, Stored::version, Stored::status)
                    .containsExactlyInAnyOrder(seeded.entrySet().stream()
                            .map(row -> tuple(
                                    row.getKey().scopeKey(),
                                    row.getKey().definitionVersion(),
                                    row.getValue().name()))
                            .toArray(org.assertj.core.groups.Tuple[]::new));

            // When every scope is listed with absent, empty and selected status sets
            Map<DefinitionFilter, Set<DefinitionKey>> expectations = Map.ofEntries(
                    Map.entry(
                            new DefinitionFilter(null, null, null),
                            Set.of(global1, global2, tenantA1, tenantA2, tenantA3, tenantB1, tenantB2, tenantB3)),
                    Map.entry(new DefinitionFilter(WorkflowScopes.DEFAULT, null, null), Set.of(global1, global2)),
                    Map.entry(new DefinitionFilter("tenant-a", null, null), Set.of(tenantA1, tenantA2, tenantA3)),
                    Map.entry(new DefinitionFilter("tenant-b", null, null), Set.of(tenantB1, tenantB2, tenantB3)),
                    Map.entry(new DefinitionFilter("tenant-c", null, null), Set.of()),
                    Map.entry(new DefinitionFilter(null, null, Set.of()), Set.of()),
                    Map.entry(new DefinitionFilter(WorkflowScopes.DEFAULT, null, Set.of()), Set.of()),
                    Map.entry(new DefinitionFilter("tenant-a", null, Set.of()), Set.of()),
                    Map.entry(
                            new DefinitionFilter(null, null, Set.of(DefinitionStatus.CANDIDATE)),
                            Set.of(global1, tenantA1, tenantB1)),
                    Map.entry(
                            new DefinitionFilter(
                                    WorkflowScopes.DEFAULT,
                                    null,
                                    Set.of(DefinitionStatus.AVAILABLE, DefinitionStatus.CANDIDATE)),
                            Set.of(global1, global2)),
                    Map.entry(
                            new DefinitionFilter(
                                    "tenant-a", null, Set.of(DefinitionStatus.AVAILABLE, DefinitionStatus.RETIRED)),
                            Set.of(tenantA2, tenantA3)),
                    Map.entry(
                            new DefinitionFilter(
                                    "tenant-b", null, Set.of(DefinitionStatus.DEPRECATED, DefinitionStatus.PUBLISHING)),
                            Set.of(tenantB2, tenantB3)),
                    Map.entry(
                            new DefinitionFilter(null, null, Set.of(DefinitionStatus.AVAILABLE)),
                            Set.of(global2, tenantA2)),
                    Map.entry(new DefinitionFilter("tenant-a", null, Set.of(DefinitionStatus.DEPRECATED)), Set.of()));
            int statementsBefore = node.observer().statements().size();
            int queriesBefore = node.observer().queries().size();
            Map<DefinitionFilter, List<WorkflowDefinitionRecord>> listed = new java.util.LinkedHashMap<>();
            Map<DefinitionFilter, List<WorkflowDefinitionRecord>> listedByService = new java.util.LinkedHashMap<>();
            for (DefinitionFilter filter : expectations.keySet()) {
                listed.put(filter, await(node.store().list(filter)));
                listedByService.put(filter, await(node.service().list(filter)));
            }
            int queriesBeforeEnd = node.observer().queries().size();
            List<String> listStatements = node.observer()
                    .statements()
                    .subList(statementsBefore, node.observer().statements().size());

            // Then each result is exactly the expected scope and status selection, with keys and statuses intact
            for (Map.Entry<DefinitionFilter, Set<DefinitionKey>> expectation : expectations.entrySet()) {
                DefinitionFilter filter = expectation.getKey();
                List<WorkflowDefinitionRecord> mixRows = listed.get(filter).stream()
                        .filter(row -> row.key().definitionId().equals(mixId))
                        .toList();
                assertThat(mixRows)
                        .as("rows listed for %s", filter)
                        .extracting(WorkflowDefinitionRecord::key)
                        .containsExactlyInAnyOrderElementsOf(expectation.getValue());
                assertThat(mixRows).allSatisfy(row -> {
                    assertThat(row.status()).isEqualTo(seeded.get(row.key()));
                    assertThat(row.kind()).isEqualTo(DefinitionKind.DOCUMENT);
                    assertThat(row.documentFormat()).isEqualTo("YAML");
                    assertThat(row.provenance().sourceType()).isEqualTo(sourceLabel(row.key()));
                });
                assertThat(listedByService.get(filter))
                        .as("service rows listed for %s", filter)
                        .isEqualTo(listed.get(filter));
            }
            assertThat(listed.get(new DefinitionFilter("tenant-c", null, null))).isEmpty();
            assertThat(listed.get(new DefinitionFilter(WorkflowScopes.DEFAULT, null, null)))
                    .allSatisfy(row -> assertThat(row.key().scopeKey()).isEqualTo(WorkflowScopes.DEFAULT));
            assertThat(listed.get(new DefinitionFilter("tenant-a", null, null)))
                    .allSatisfy(row -> assertThat(row.key().scopeKey()).isEqualTo("tenant-a"));
            List<WorkflowDefinitionRecord> everything = listed.get(new DefinitionFilter(null, null, null));
            assertThatThrownBy(() -> everything.add(everything.get(0)))
                    .isInstanceOf(UnsupportedOperationException.class);

            // And listing selected metadata only: no document column was read and no value was embedded in SQL
            assertThat(listStatements).hasSizeGreaterThanOrEqualTo(2 * expectations.size());
            assertThat(listStatements).allSatisfy(sql -> {
                assertThat(sql).containsIgnoringCase("FROM workflow_definitions");
                assertThat(sql).doesNotContainPattern("(?i)\\bdocument\\b");
                assertThat(sql).doesNotContainPattern("(?i)select\\s+\\*");
                assertThat(sql).doesNotContain("tenant-a").doesNotContain("tenant-b");
            });
            assertThat(node.observer().queries().subList(queriesBefore, queriesBeforeEnd))
                    .isEmpty();

            // And listing claimed nothing: the candidates are still candidates and the seeded rows are unchanged
            assertThat(fixture.stored(candidatesId))
                    .extracting(Stored::status, Stored::token)
                    .containsOnly(tuple("CANDIDATE", null));
            assertThat(fixture.stored(mixId))
                    .extracting(Stored::scopeKey, Stored::version, Stored::status)
                    .containsExactlyInAnyOrder(seeded.entrySet().stream()
                            .map(row -> tuple(
                                    row.getKey().scopeKey(),
                                    row.getKey().definitionVersion(),
                                    row.getValue().name()))
                            .toArray(org.assertj.core.groups.Tuple[]::new));

            // And invalid scope and key combinations are rejected when the filter or key is constructed
            assertThatThrownBy(() -> new DefinitionFilter(" ", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DefinitionFilter("x".repeat(256), null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DefinitionKey(WorkflowScopes.DEFAULT, " ", 1))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new DefinitionKey("tenant-a", mixId, 0)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    @DisplayName("a global static document reconcile does not depend on the rows other scopes hold for the id")
    void staticDocumentReconcileIgnoresTheRowsOfOtherScopes(Vertx vertx) {
        String heldId = "ownership-reconcile-held-doc";
        String freshId = "ownership-reconcile-fresh-doc";
        String raceId = "ownership-reconcile-race-doc";
        DefinitionKey heldGlobal = new DefinitionKey(WorkflowScopes.DEFAULT, heldId, 1);
        DefinitionKey freshTenant = new DefinitionKey("tenant-a", freshId, 1);
        DefinitionKey freshGlobal = new DefinitionKey(WorkflowScopes.DEFAULT, freshId, 1);
        DefinitionKey raceTenant = new DefinitionKey("tenant-a", raceId, 1);
        DefinitionKey raceGlobal = new DefinitionKey(WorkflowScopes.DEFAULT, raceId, 1);

        try (Fixture fixture = new Fixture(vertx, db)) {
            Node nodeA = fixture.node();
            Node nodeB = fixture.node();
            fixture.ownRows(heldId, freshId, raceId);

            // Given tenant-a has the id available through a real publication
            byte[] heldDocument = document(heldId, 1, ReconcileHeldV1.class);
            TenantRowSeed.seed(
                    nodeB.app(), "tenant-a", heldDocument, source("reconcile-held"), DefinitionStatus.AVAILABLE);
            String heldPlanHash = planHash(nodeB, heldDocument, "reconcile-held");
            Stored heldBefore = fixture.stored(heldId).getFirst();
            assertThat(heldBefore.status()).isEqualTo("AVAILABLE");

            // When a global static document with the same id and version is reconciled
            ReconcileOutcome heldOutcome = await(nodeA.store()
                    .upsertDocument(
                            staticRecord(heldGlobal, heldPlanHash, contractName(nodeB, heldDocument, "reconcile-held")),
                            staticDocument(heldGlobal, heldDocument)));

            // Then it is inserted as available beside tenant-a's untouched row
            assertThat(heldOutcome).isEqualTo(ReconcileOutcome.INSERTED);
            assertThat(fixture.stored(heldId))
                    .extracting(Stored::scopeKey, Stored::status)
                    .containsExactly(tuple(WorkflowScopes.DEFAULT, "AVAILABLE"), tuple("tenant-a", "AVAILABLE"));
            Stored heldAfter = fixture.stored(heldId).get(1);
            assertThat(heldAfter.changedAt()).isEqualTo(heldBefore.changedAt());
            assertThat(heldAfter.planHash()).isEqualTo(heldBefore.planHash());

            // Given an id without rows, and a global reconcile plus a tenant-a load and publication queued behind
            // the id lock
            byte[] freshDocument = document(freshId, 1, ReconcileFreshV1.class);
            String freshHash = nodeA.app()
                    .definitionPipeline()
                    .load(new DefinitionResource(freshDocument, DocumentFormat.YAML, source("reconcile-fresh")))
                    .planHash();
            IdLockBarrier freshBarrier = fixture.holdIdLock(freshId);
            Future<ReconcileOutcome> freshReconcile = nodeA.store()
                    .upsertDocument(
                            staticRecord(freshGlobal, freshHash, contractName(nodeA, freshDocument, "reconcile-fresh")),
                            staticDocument(freshGlobal, freshDocument));
            Future<Void> freshPublication = TenantRowSeed.insertCandidate(
                            nodeB.app(), "tenant-a", freshDocument, DocumentFormat.YAML, source("reconcile-fresh"))
                    .compose(loaded -> nodeB.service().publish(freshTenant));
            freshBarrier.awaitQueued(2);
            assertThat(fixture.stored(freshId)).isEmpty();

            // When the lock is released
            freshBarrier.release();
            ReconcileOutcome freshOutcome = await(freshReconcile);
            await(freshPublication);

            // Then both scopes hold the id as available
            assertThat(freshOutcome).isEqualTo(ReconcileOutcome.INSERTED);
            assertThat(fixture.stored(freshId))
                    .extracting(Stored::scopeKey, Stored::status)
                    .containsExactly(tuple(WorkflowScopes.DEFAULT, "AVAILABLE"), tuple("tenant-a", "AVAILABLE"));

            // Given tenant-a already has a candidate, and a global reconcile plus tenant-a's publication queue together
            byte[] raceDocument = document(raceId, 1, ReconcileRaceV1.class);
            String raceHash = nodeA.app()
                    .definitionPipeline()
                    .load(new DefinitionResource(raceDocument, DocumentFormat.YAML, source("reconcile-race")))
                    .planHash();
            await(TenantRowSeed.insertCandidate(
                    nodeB.app(), "tenant-a", raceDocument, DocumentFormat.YAML, source("reconcile-race")));
            IdLockBarrier raceBarrier = fixture.holdIdLock(raceId);
            Future<ReconcileOutcome> raceReconcile = nodeA.store()
                    .upsertDocument(
                            staticRecord(raceGlobal, raceHash, contractName(nodeA, raceDocument, "reconcile-race")),
                            staticDocument(raceGlobal, raceDocument));
            Future<Void> racePublication = nodeB.service().publish(raceTenant);
            raceBarrier.awaitQueued(2);

            // When the lock is released
            raceBarrier.release();
            ReconcileOutcome raceOutcome = await(raceReconcile);
            await(racePublication);

            // Then both scopes hold the id as available, whichever arrived first
            assertThat(raceOutcome).isEqualTo(ReconcileOutcome.INSERTED);
            assertThat(fixture.stored(raceId))
                    .extracting(Stored::scopeKey, Stored::status)
                    .containsExactly(tuple(WorkflowScopes.DEFAULT, "AVAILABLE"), tuple("tenant-a", "AVAILABLE"));
        }
    }

    @Test
    @DisplayName("completing a version changes no other row, whatever the status of the other versions")
    void completingAVersionChangesNoOtherRow(Vertx vertx) {
        String id = "ownership-complete-doc";
        DefinitionKey candidateKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        DefinitionKey availableKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 2);
        DefinitionKey deprecatedKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 3);
        DefinitionKey otherScopeKey = new DefinitionKey("tenant-a", id, 1);

        try (Fixture fixture = new Fixture(vertx, db)) {
            fixture.ownRows(id);
            Node node = fixture.node();

            // Given a version 1 candidate beside an available version 2, a deprecated version 3 and the same
            // version 1 in another scope
            // (the other statuses are seeded by SQL so that their change times are fixed)
            for (DefinitionKey key : List.of(candidateKey, availableKey, deprecatedKey, otherScopeKey)) {
                await(node.store().insertCandidate(candidate(key), documentCarrier(key)));
            }
            fixture.forceStatus(availableKey, DefinitionStatus.AVAILABLE);
            fixture.forceStatus(deprecatedKey, DefinitionStatus.DEPRECATED);
            List<Stored> before = fixture.stored(id);

            // When version 1 is claimed and completed
            ActivationClaim claim = await(node.store().claim(candidateKey));
            await(node.store().complete(claim));

            // Then version 1 is available without a claim, and every other row is exactly as it was
            assertThat(fixture.stored(id))
                    .extracting(Stored::scopeKey, Stored::version, Stored::status, Stored::token)
                    .containsExactly(
                            tuple(WorkflowScopes.DEFAULT, 1L, "AVAILABLE", null),
                            tuple(WorkflowScopes.DEFAULT, 2L, "AVAILABLE", null),
                            tuple(WorkflowScopes.DEFAULT, 3L, "DEPRECATED", null),
                            tuple("tenant-a", 1L, "CANDIDATE", null));
            List<Stored> after = fixture.stored(id);
            for (int index = 1; index < before.size(); index++) {
                assertThat(after.get(index)).as("row %d", index).isEqualTo(before.get(index));
            }
        }
    }

    private static WorkflowDefinitionRecord staticRecord(DefinitionKey key, String planHash, String contractName) {
        Instant now = Instant.now();
        return new WorkflowDefinitionRecord(
                key,
                DefinitionKind.DOCUMENT,
                contractName,
                "example.State",
                planHash,
                DefinitionStatus.AVAILABLE,
                new DefinitionProvenance("static-source", "memory:static", "revision-static", null, null, now),
                "YAML",
                now,
                now,
                Map.of());
    }

    private static WorkflowDefinitionDocument staticDocument(DefinitionKey key, byte[] document) {
        return new WorkflowDefinitionDocument(key, new String(document, StandardCharsets.UTF_8), "YAML");
    }

    private static String sourceLabel(DefinitionKey key) {
        return "mix-" + key.scopeKey();
    }

    private static WorkflowDefinitionRecord candidate(DefinitionKey key) {
        Instant now = Instant.now();
        return new WorkflowDefinitionRecord(
                key,
                DefinitionKind.DOCUMENT,
                "example.Contract",
                "example.State",
                "f".repeat(64),
                DefinitionStatus.CANDIDATE,
                new DefinitionProvenance(sourceLabel(key), null, null, null, null, now),
                "YAML",
                now,
                now,
                Map.of());
    }

    private static WorkflowDefinitionDocument documentCarrier(DefinitionKey key) {
        return new WorkflowDefinitionDocument(key, "definitionId: " + key.definitionId() + "\n", "YAML");
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

    private static String planHash(Node node, byte[] document, String label) {
        return node.app()
                .definitionPipeline()
                .load(new DefinitionResource(document, DocumentFormat.YAML, source(label)))
                .planHash();
    }

    private static String contractName(Node node, byte[] document, String label) {
        return node.app()
                .definitionPipeline()
                .load(new DefinitionResource(document, DocumentFormat.YAML, source(label)))
                .contractName();
    }

    private static SourceMetadata source(String label) {
        return new SourceMetadata("ownership-test", "memory:" + label, "revision-" + label, null, null, Instant.now());
    }
}
