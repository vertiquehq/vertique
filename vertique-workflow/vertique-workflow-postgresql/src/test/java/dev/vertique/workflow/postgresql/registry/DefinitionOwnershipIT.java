// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.ActivationOutcome;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that candidates of different owners coexist while exactly one owner of a definition id
 * holds executable membership, across independent application graphs sharing one database.
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
    @DisplayName("two owners racing for one id leave exactly one holder and a typed conflict for the other")
    void oneOwnerWinsConcurrentTenantActivation(Vertx vertx) {
        String raceId = "ownership-race-doc";
        String heldId = "ownership-held-doc";
        DefinitionKey raceA = new DefinitionKey("tenant-a", raceId, 1);
        DefinitionKey raceB = new DefinitionKey("tenant-b", raceId, 1);
        DefinitionKey heldA = new DefinitionKey("tenant-a", heldId, 1);
        DefinitionKey heldB = new DefinitionKey("tenant-b", heldId, 1);

        try (Fixture fixture = new Fixture(vertx)) {
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
            String planHashA = planHash(nodeA, document(raceId, 1, RaceV1.class), "race-a");
            String planHashB = planHash(nodeB, document(raceId, 1, RaceV1.class), "race-b");

            // Then both candidates coexist without either claiming the id
            assertThat(fixture.stored(raceId))
                    .extracting(Stored::scopeKey, Stored::status, Stored::token)
                    .containsExactly(tuple("tenant-a", "CANDIDATE", null), tuple("tenant-b", "CANDIDATE", null));

            // When both owners activate at the same time and both claims are queued behind the id lock
            IdLockBarrier activationBarrier = fixture.holdIdLock(raceId);
            Future<Void> activationA = nodeA.service().activate(raceA);
            Future<Void> activationB = nodeB.service().activate(raceB);
            activationBarrier.awaitQueued(2);
            activationBarrier.release();
            Throwable failureA = failureOf(activationA);
            Throwable failureB = failureOf(activationB);

            // Then exactly one activation succeeds and the other fails with a conflict naming both owners
            assertThat(failureA == null ^ failureB == null)
                    .as("exactly one activation succeeds: tenant-a=%s tenant-b=%s", failureA, failureB)
                    .isTrue();
            boolean tenantAWon = failureA == null;
            DefinitionKey winnerKey = tenantAWon ? raceA : raceB;
            DefinitionKey loserKey = tenantAWon ? raceB : raceA;
            Node winnerNode = tenantAWon ? nodeA : nodeB;
            Node loserNode = tenantAWon ? nodeB : nodeA;
            Throwable loserFailure = tenantAWon ? failureB : failureA;
            String winnerPlanHash = tenantAWon ? planHashA : planHashB;
            assertThat(loserFailure).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                assertThat(failure.key()).isEqualTo(loserKey);
                assertThat(failure.getMessage()).contains(loserKey.scopeKey()).contains(winnerKey.scopeKey());
            });

            // And the database has exactly one holder, with no lost update
            assertThat(fixture.stored(raceId))
                    .extracting(Stored::scopeKey, Stored::status, Stored::token)
                    .containsExactlyInAnyOrder(
                            tuple(winnerKey.scopeKey(), "ACTIVE", null), tuple(loserKey.scopeKey(), "CANDIDATE", null));
            assertThat(fixture.stored(raceId))
                    .filteredOn(row -> row.scopeKey().equals(winnerKey.scopeKey()))
                    .singleElement()
                    .satisfies(row -> assertThat(row.planHash()).isEqualTo(winnerPlanHash));

            // And only the winner holds an executable registration
            assertThat(winnerNode.registry().resolve(winnerKey).plan().planHash())
                    .isEqualTo(winnerPlanHash);
            assertThat(loserNode.registry().allRegistered())
                    .noneMatch(runtime -> runtime.plan().definitionId().equals(raceId));
            assertThat(loserNode.registry().registeredDefinition(loserKey)).isEmpty();
            assertThat(loserNode.registry().registeredDefinition(winnerKey)).isEmpty();

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
            Future<Void> heldActivation = nodeA.service().activate(heldA);
            await(gate.observed());
            assertThat(fixture.stored(heldId))
                    .extracting(Stored::scopeKey, Stored::status)
                    .containsExactly(tuple("tenant-a", "ACTIVATING"), tuple("tenant-b", "CANDIDATE"));

            // When tenant-b activates the same id while tenant-a's fresh claim is outstanding
            Throwable lateFailure = failureOf(nodeB.service().activate(heldB));

            // Then it is refused with a conflict naming both owners and registers nothing
            assertThat(lateFailure).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                assertThat(failure.key()).isEqualTo(heldB);
                assertThat(failure.getMessage()).contains("tenant-a").contains("tenant-b");
            });
            assertThat(nodeB.registry().allRegistered())
                    .noneMatch(runtime -> runtime.plan().definitionId().equals(heldId));
            assertThat(fixture.stored(heldId))
                    .extracting(Stored::scopeKey, Stored::status)
                    .containsExactly(tuple("tenant-a", "ACTIVATING"), tuple("tenant-b", "CANDIDATE"));

            // And the held completion still finishes as the only owner
            gate.release();
            await(heldActivation);
            assertThat(fixture.stored(heldId))
                    .extracting(Stored::scopeKey, Stored::status, Stored::token)
                    .containsExactly(tuple("tenant-a", "ACTIVE", null), tuple("tenant-b", "CANDIDATE", null));
            assertThat(nodeA.registry().resolve(new DefinitionKey("tenant-a", heldId, 1)))
                    .isNotNull();
        }
    }

    private record OrderScenario(
            String definitionId,
            Class<?> version3Contract,
            Class<?> version4Contract,
            boolean version3CompletesFirst) {}

    @Test
    @DisplayName("concurrent activations of two versions of one owner end with the highest version active")
    void highestVersionWinsWithoutUniqueIndexFailure(Vertx vertx) {
        List<OrderScenario> scenarios = List.of(
                new OrderScenario(
                        "tenant-a:ownership-v3-first-doc", V3FirstVersion3.class, V3FirstVersion4.class, true),
                new OrderScenario(
                        "tenant-a:ownership-v4-first-doc", V4FirstVersion3.class, V4FirstVersion4.class, false));

        try (Fixture fixture = new Fixture(vertx)) {
            Node nodeA = fixture.node();
            Node nodeB = fixture.node();

            for (OrderScenario scenario : scenarios) {
                String id = scenario.definitionId();
                DefinitionKey version3 = new DefinitionKey("tenant-a", id, 3);
                DefinitionKey version4 = new DefinitionKey("tenant-a", id, 4);
                fixture.ownRows(id);

                // Given two candidates of one owner, each activating on its own node with completion held back
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
                Future<Void> activation3 = nodeA.service().activate(version3);
                Future<Void> activation4 = nodeB.service().activate(version4);
                await(gate3.observed());
                await(gate4.observed());
                assertThat(fixture.stored(id))
                        .extracting(Stored::version, Stored::status)
                        .containsExactly(tuple(3L, "ACTIVATING"), tuple(4L, "ACTIVATING"));

                // When the barriers release the completions in the scenario's order
                if (scenario.version3CompletesFirst()) {
                    gate3.release();
                    await(activation3);
                    assertThat(fixture.stored(id))
                            .extracting(Stored::version, Stored::status)
                            .containsExactly(tuple(3L, "ACTIVE"), tuple(4L, "ACTIVATING"));
                    gate4.release();
                    await(activation4);
                } else {
                    gate4.release();
                    await(activation4);
                    assertThat(fixture.stored(id))
                            .extracting(Stored::version, Stored::status)
                            .containsExactly(tuple(3L, "ACTIVATING"), tuple(4L, "ACTIVE"));
                    gate3.release();
                    await(activation3);
                }

                // Then version 4 is the only active row and version 3 is superseded, with no claim left behind
                assertThat(fixture.stored(id))
                        .extracting(Stored::version, Stored::status, Stored::token)
                        .containsExactly(tuple(3L, "SUPERSEDED", null), tuple(4L, "ACTIVE", null));
                assertThat(fixture.stored(id))
                        .filteredOn(row -> row.status().equals("ACTIVE"))
                        .hasSize(1);
                assertThat(fixture.stored(id))
                        .extracting(Stored::version, Stored::planHash)
                        .containsExactly(tuple(3L, loaded3.planHash()), tuple(4L, loaded4.planHash()));

                // And each version stays pinnable on the node that activated it
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
    @DisplayName("a completion from an abandoned claim cannot overwrite the owner that replaced it")
    void staleClaimCannotOverwriteReplacementOwner(Vertx vertx) {
        String id = "ownership-stale-doc";
        DefinitionKey tenantA = new DefinitionKey("tenant-a", id, 1);
        DefinitionKey tenantB = new DefinitionKey("tenant-b", id, 1);

        try (Fixture fixture = new Fixture(vertx)) {
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
            Future<Void> staleActivation = nodeA.service().activate(tenantA);
            await(gate.observed());
            Stored claimed = fixture.stored(id).stream()
                    .filter(row -> "tenant-a".equals(row.scopeKey()))
                    .findFirst()
                    .orElseThrow();
            assertThat(claimed.status()).isEqualTo("ACTIVATING");
            assertThat(claimed.token()).isNotBlank();
            assertThat(nodeA.registry().resolve(tenantA)).isNotNull();

            // And that claim is older than twice the staleness bound when tenant-b then activates the same id and
            // version
            fixture.ageClaim(nodeA.app().workflowDefinitionsConfig(), tenantA, claimed.token());
            await(nodeB.service().activate(tenantB));
            RuntimeWorkflow replacement = nodeB.registry().resolve(tenantB);
            var replacementDefinition = nodeB.registry().registeredDefinition(tenantB);
            Stored replacementRow = fixture.stored(id).stream()
                    .filter(row -> "tenant-b".equals(row.scopeKey()))
                    .findFirst()
                    .orElseThrow();
            assertThat(replacementRow.status()).isEqualTo("ACTIVE");
            assertThat(replacementRow.planHash()).isEqualTo(claimed.planHash());

            // When tenant-a's old claim token completes and its local cleanup finishes
            gate.release();
            Throwable failure = failureOf(staleActivation);

            // Then the completion is refused with a typed ownership conflict
            assertThat(failure).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, conflict -> {
                assertThat(conflict.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                assertThat(conflict.key()).isEqualTo(tenantA);
                assertThat(conflict.getMessage()).contains("tenant-b");
            });

            // And tenant-a's row returned to a candidate while tenant-b's row is untouched
            assertThat(fixture.stored(id))
                    .extracting(Stored::scopeKey, Stored::status, Stored::token)
                    .containsExactly(tuple("tenant-a", "CANDIDATE", null), tuple("tenant-b", "ACTIVE", null));
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

            // And nothing of tenant-a stays executable on its node
            assertThat(nodeA.registry().allRegistered())
                    .noneMatch(runtime -> runtime.plan().definitionId().equals(id));
            assertThat(nodeA.registry().registeredDefinition(tenantA)).isEmpty();
            assertThat(nodeA.registry().registeredDefinition(tenantB)).isEmpty();
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
                global2, DefinitionStatus.SUPERSEDED,
                tenantA1, DefinitionStatus.CANDIDATE,
                tenantA2, DefinitionStatus.ACTIVE,
                tenantA3, DefinitionStatus.RETIRED,
                tenantB1, DefinitionStatus.CANDIDATE,
                tenantB2, DefinitionStatus.DEPRECATED,
                tenantB3, DefinitionStatus.ACTIVATING);

        try (Fixture fixture = new Fixture(vertx)) {
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
                if (row.getValue() != DefinitionStatus.CANDIDATE && row.getValue() != DefinitionStatus.ACTIVATING) {
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
                                    Set.of(DefinitionStatus.SUPERSEDED, DefinitionStatus.CANDIDATE)),
                            Set.of(global1, global2)),
                    Map.entry(
                            new DefinitionFilter(
                                    "tenant-a", null, Set.of(DefinitionStatus.ACTIVE, DefinitionStatus.RETIRED)),
                            Set.of(tenantA2, tenantA3)),
                    Map.entry(
                            new DefinitionFilter(
                                    "tenant-b", null, Set.of(DefinitionStatus.DEPRECATED, DefinitionStatus.ACTIVATING)),
                            Set.of(tenantB2, tenantB3)),
                    Map.entry(new DefinitionFilter(null, null, Set.of(DefinitionStatus.ACTIVE)), Set.of(tenantA2)),
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
    @DisplayName("a global static document reconcile cannot take an id another owner holds or races into")
    void rejectsForeignOwnerStaticDocumentReconcile(Vertx vertx) {
        String heldId = "ownership-reconcile-held-doc";
        String freshId = "ownership-reconcile-fresh-doc";
        String raceId = "ownership-reconcile-race-doc";
        DefinitionKey heldTenant = new DefinitionKey("tenant-a", heldId, 1);
        DefinitionKey heldGlobal = new DefinitionKey(WorkflowScopes.DEFAULT, heldId, 1);
        DefinitionKey freshTenant = new DefinitionKey("tenant-a", freshId, 1);
        DefinitionKey freshGlobal = new DefinitionKey(WorkflowScopes.DEFAULT, freshId, 1);
        DefinitionKey raceTenant = new DefinitionKey("tenant-a", raceId, 1);
        DefinitionKey raceGlobal = new DefinitionKey(WorkflowScopes.DEFAULT, raceId, 1);

        try (Fixture fixture = new Fixture(vertx)) {
            Node nodeA = fixture.node();
            Node nodeB = fixture.node();
            fixture.ownRows(heldId, freshId, raceId);

            // Given tenant-a holds an id through a real activation
            byte[] heldDocument = document(heldId, 1, ReconcileHeldV1.class);
            TenantRowSeed.seed(
                    nodeB.app(), "tenant-a", heldDocument, source("reconcile-held"), DefinitionStatus.ACTIVE);
            String heldPlanHash = planHash(nodeB, heldDocument, "reconcile-held");
            Stored heldBefore = fixture.stored(heldId).getFirst();
            assertThat(heldBefore.status()).isEqualTo("ACTIVE");

            // When a global static document with the same id and version is reconciled
            ReconcileOutcome heldOutcome = await(nodeA.store()
                    .upsertDocument(staticRecord(heldGlobal, heldPlanHash), staticDocument(heldGlobal, heldDocument)));

            // Then it is refused, inserts no global row and leaves tenant-a's row untouched
            assertThat(heldOutcome).isEqualTo(ReconcileOutcome.OWNER_CONFLICT);
            assertThat(fixture.stored(heldId)).hasSize(1);
            assertThat(fixture.stored(heldId).getFirst().scopeKey()).isEqualTo("tenant-a");
            assertThat(fixture.stored(heldId).getFirst().status()).isEqualTo("ACTIVE");
            assertThat(fixture.stored(heldId).getFirst().changedAt()).isEqualTo(heldBefore.changedAt());
            assertThat(fixture.stored(heldId).getFirst().planHash()).isEqualTo(heldBefore.planHash());

            // Given an id without rows, and a global reconcile plus a tenant-a load queued behind the id lock
            byte[] freshDocument = document(freshId, 1, ReconcileFreshV1.class);
            String freshHash = nodeA.app()
                    .definitionPipeline()
                    .load(new DefinitionResource(freshDocument, DocumentFormat.YAML, source("reconcile-fresh")))
                    .planHash();
            IdLockBarrier freshBarrier = fixture.holdIdLock(freshId);
            Future<ReconcileOutcome> freshReconcile = nodeA.store()
                    .upsertDocument(staticRecord(freshGlobal, freshHash), staticDocument(freshGlobal, freshDocument));
            Future<Void> freshActivation = TenantRowSeed.insertCandidate(
                            nodeB.app(), "tenant-a", freshDocument, DocumentFormat.YAML, source("reconcile-fresh"))
                    .compose(loaded -> nodeB.service().activate(freshTenant));
            freshBarrier.awaitQueued(2);
            assertThat(fixture.stored(freshId)).isEmpty();

            // When the lock is released
            freshBarrier.release();
            ReconcileOutcome freshOutcome = await(freshReconcile);
            Throwable freshFailure = failureOf(freshActivation);

            // Then exactly one owner holds the id and the other is refused
            assertThat(freshOutcome == ReconcileOutcome.INSERTED ^ freshFailure == null)
                    .as("exactly one owner wins: reconcile=%s activation=%s", freshOutcome, freshFailure)
                    .isTrue();
            if (freshOutcome == ReconcileOutcome.INSERTED) {
                assertThat(freshFailure).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                    assertThat(failure.key()).isEqualTo(freshTenant);
                    assertThat(failure.getMessage()).contains("default");
                });
                assertThat(fixture.stored(freshId))
                        .extracting(Stored::scopeKey, Stored::status)
                        .containsExactly(tuple(WorkflowScopes.DEFAULT, "SUPERSEDED"), tuple("tenant-a", "CANDIDATE"));
            } else {
                assertThat(freshOutcome).isEqualTo(ReconcileOutcome.OWNER_CONFLICT);
                assertThat(fixture.stored(freshId))
                        .extracting(Stored::scopeKey, Stored::status)
                        .containsExactly(tuple("tenant-a", "ACTIVE"));
            }

            // Given tenant-a already has a candidate, and a global reconcile plus tenant-a's activation queue together
            byte[] raceDocument = document(raceId, 1, ReconcileRaceV1.class);
            String raceHash = nodeA.app()
                    .definitionPipeline()
                    .load(new DefinitionResource(raceDocument, DocumentFormat.YAML, source("reconcile-race")))
                    .planHash();
            await(TenantRowSeed.insertCandidate(
                    nodeB.app(), "tenant-a", raceDocument, DocumentFormat.YAML, source("reconcile-race")));
            IdLockBarrier raceBarrier = fixture.holdIdLock(raceId);
            Future<ReconcileOutcome> raceReconcile = nodeA.store()
                    .upsertDocument(staticRecord(raceGlobal, raceHash), staticDocument(raceGlobal, raceDocument));
            Future<Void> raceActivation = nodeB.service().activate(raceTenant);
            raceBarrier.awaitQueued(2);

            // When the lock is released
            raceBarrier.release();
            ReconcileOutcome raceOutcome = await(raceReconcile);
            Throwable raceFailure = failureOf(raceActivation);

            // Then exactly one owner holds the id and the other is refused, whichever arrived first
            assertThat(raceOutcome == ReconcileOutcome.INSERTED ^ raceFailure == null)
                    .as("exactly one owner wins: reconcile=%s activation=%s", raceOutcome, raceFailure)
                    .isTrue();
            if (raceOutcome == ReconcileOutcome.INSERTED) {
                assertThat(raceFailure).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(Reason.OWNER_CONFLICT);
                    assertThat(failure.key()).isEqualTo(raceTenant);
                    assertThat(failure.getMessage()).contains("default");
                });
                assertThat(fixture.stored(raceId))
                        .extracting(Stored::scopeKey, Stored::status)
                        .containsExactly(tuple(WorkflowScopes.DEFAULT, "SUPERSEDED"), tuple("tenant-a", "CANDIDATE"));
            } else {
                assertThat(raceOutcome).isEqualTo(ReconcileOutcome.OWNER_CONFLICT);
                assertThat(fixture.stored(raceId))
                        .extracting(Stored::scopeKey, Stored::status)
                        .containsExactly(tuple("tenant-a", "ACTIVE"));
            }
        }
    }

    @Test
    @DisplayName("recomputing membership rejects malformed keys before issuing any SQL")
    void rejectsMalformedKeysBeforeSql(Vertx vertx) {
        String missingId = "ownership-recompute-missing-doc";

        try (Fixture fixture = new Fixture(vertx)) {
            Node node = fixture.node();
            fixture.ownRows(missingId);
            Map<String, Map.Entry<String, String>> malformed = new java.util.LinkedHashMap<>();
            malformed.put("blank definition id", Map.entry("tenant-a", " "));
            malformed.put("oversized definition id", Map.entry("tenant-a", "x".repeat(256)));
            malformed.put("blank tenant key", Map.entry(" ", missingId));
            malformed.put("oversized tenant key", Map.entry("x".repeat(256), missingId));

            // Given a valid control that reaches the database as a no-op on a missing id
            int controlBefore = node.observer().statements().size();
            Throwable controlFailure = failureOf(node.store().recomputeActive("tenant-a", missingId));
            assertThat(controlFailure).isNull();
            assertThat(node.observer().statements().size()).isGreaterThan(controlBefore);
            assertThat(fixture.stored(missingId)).isEmpty();

            for (Map.Entry<String, Map.Entry<String, String>> entry : malformed.entrySet()) {
                String scopeKey = entry.getValue().getKey();
                String definitionId = entry.getValue().getValue();

                // When recomputing with a malformed key
                int before = node.observer().statements().size();
                Throwable failure = failureOf(node.store().recomputeActive(scopeKey, definitionId));
                int after = node.observer().statements().size();

                // Then the call fails with an argument error and no statement was issued
                assertThat(failure)
                        .as("recomputeActive with a %s must fail", entry.getKey())
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(after - before)
                        .as("statements issued for a %s", entry.getKey())
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("completing a lower version never becomes active while a higher version of the owner stays eligible")
    void demotesCompletingVersionBelowSupersededHigherVersion(Vertx vertx) {
        String supersededHigherId = "ownership-higher-superseded-doc";
        String deprecatedHigherId = "ownership-higher-deprecated-doc";
        String activeHigherId = "ownership-higher-active-doc";
        DefinitionKey supersededV1 = new DefinitionKey(WorkflowScopes.DEFAULT, supersededHigherId, 1);
        DefinitionKey supersededV2 = new DefinitionKey(WorkflowScopes.DEFAULT, supersededHigherId, 2);
        DefinitionKey supersededV3 = new DefinitionKey(WorkflowScopes.DEFAULT, supersededHigherId, 3);
        DefinitionKey deprecatedV1 = new DefinitionKey(WorkflowScopes.DEFAULT, deprecatedHigherId, 1);
        DefinitionKey deprecatedV3 = new DefinitionKey(WorkflowScopes.DEFAULT, deprecatedHigherId, 3);
        DefinitionKey activeV1 = new DefinitionKey(WorkflowScopes.DEFAULT, activeHigherId, 1);
        DefinitionKey activeV2 = new DefinitionKey(WorkflowScopes.DEFAULT, activeHigherId, 2);

        try (Fixture fixture = new Fixture(vertx)) {
            fixture.ownRows(supersededHigherId, deprecatedHigherId, activeHigherId);
            Node node = fixture.node();

            // Given version 2 SUPERSEDED, version 3 DEPRECATED and a version 1 candidate of one owner
            // (the higher statuses are seeded by SQL because the public operations cannot reach this state)
            await(node.store().insertCandidate(candidate(supersededV1), documentCarrier(supersededV1)));
            await(node.store().insertCandidate(candidate(supersededV2), documentCarrier(supersededV2)));
            await(node.store().insertCandidate(candidate(supersededV3), documentCarrier(supersededV3)));
            fixture.forceStatus(supersededV2, DefinitionStatus.SUPERSEDED);
            fixture.forceStatus(supersededV3, DefinitionStatus.DEPRECATED);

            // When version 1 is claimed and completed
            ActivationClaim supersededClaim = await(node.store().claim(supersededV1));
            ActivationOutcome supersededOutcome = await(node.store().complete(supersededClaim));

            // Then it is SUPERSEDED, the higher rows are untouched, and the owner has no ACTIVE row
            assertThat(supersededOutcome).isEqualTo(ActivationOutcome.SUPERSEDED);
            assertThat(fixture.stored(supersededHigherId))
                    .extracting(Stored::version, Stored::status, Stored::token)
                    .containsExactly(
                            tuple(1L, "SUPERSEDED", null),
                            tuple(2L, "SUPERSEDED", null),
                            tuple(3L, "DEPRECATED", null));

            // Given only a DEPRECATED version 3 above a version 1 candidate
            await(node.store().insertCandidate(candidate(deprecatedV1), documentCarrier(deprecatedV1)));
            await(node.store().insertCandidate(candidate(deprecatedV3), documentCarrier(deprecatedV3)));
            fixture.forceStatus(deprecatedV3, DefinitionStatus.DEPRECATED);

            // When version 1 is claimed and completed
            ActivationClaim deprecatedClaim = await(node.store().claim(deprecatedV1));
            ActivationOutcome deprecatedOutcome = await(node.store().complete(deprecatedClaim));

            // Then nothing eligible is higher, so version 1 becomes ACTIVE and version 3 stays DEPRECATED
            assertThat(deprecatedOutcome).isEqualTo(ActivationOutcome.ACTIVE);
            assertThat(fixture.stored(deprecatedHigherId))
                    .extracting(Stored::version, Stored::status, Stored::token)
                    .containsExactly(tuple(1L, "ACTIVE", null), tuple(3L, "DEPRECATED", null));

            // Given an ACTIVE version 2 above a version 1 candidate
            await(node.store().insertCandidate(candidate(activeV1), documentCarrier(activeV1)));
            await(node.store().insertCandidate(candidate(activeV2), documentCarrier(activeV2)));
            fixture.forceStatus(activeV2, DefinitionStatus.ACTIVE);

            // When version 1 is claimed and completed
            ActivationClaim activeClaim = await(node.store().claim(activeV1));
            ActivationOutcome activeOutcome = await(node.store().complete(activeClaim));

            // Then version 1 is SUPERSEDED and version 2 stays the only ACTIVE row
            assertThat(activeOutcome).isEqualTo(ActivationOutcome.SUPERSEDED);
            assertThat(fixture.stored(activeHigherId))
                    .extracting(Stored::version, Stored::status, Stored::token)
                    .containsExactly(tuple(1L, "SUPERSEDED", null), tuple(2L, "ACTIVE", null));
        }
    }

    private static WorkflowDefinitionRecord staticRecord(DefinitionKey key, String planHash) {
        Instant now = Instant.now();
        return new WorkflowDefinitionRecord(
                key,
                DefinitionKind.DOCUMENT,
                "example.Contract",
                "example.State",
                planHash,
                DefinitionStatus.SUPERSEDED,
                new DefinitionProvenance("static-source", "memory:static", "revision-static", null, null, now),
                "YAML",
                now,
                now);
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
                now);
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

    private static SourceMetadata source(String label) {
        return new SourceMetadata("ownership-test", "memory:" + label, "revision-" + label, null, null, Instant.now());
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

    private static long lockKey(String definitionId) {
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                            .digest(("vertique.workflow.definition:" + definitionId).getBytes(StandardCharsets.UTF_8)))
                    .getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Stored(
            String scopeKey, long version, String status, String token, String planHash, OffsetDateTime changedAt) {}

    private record Node(Pool pool, WorkflowDefinitionSqlObserver observer, AppComponent app) {

        WorkflowDefinitionService service() {
            return app.definitionService();
        }

        WorkflowRegistry registry() {
            return app.workflowRegistry();
        }

        WorkflowDefinitionRegistryStore store() {
            return app.definitionRegistryStore();
        }
    }

    @FunctionalInterface
    private interface Cleanup {
        void run() throws Exception;
    }

    /** Holds a definition id's advisory lock in a test-owned transaction so competing operations queue behind it. */
    private static final class IdLockBarrier {
        private final Pool pool;
        private final long key;
        private final SqlConnection connection;
        private final Transaction transaction;
        private boolean released;

        private IdLockBarrier(Pool pool, long key, SqlConnection connection, Transaction transaction) {
            this.pool = pool;
            this.key = key;
            this.connection = connection;
            this.transaction = transaction;
        }

        static IdLockBarrier hold(Pool pool, String definitionId) {
            SqlConnection connection = await(pool.getConnection());
            try {
                Transaction transaction = await(connection.begin());
                long key = lockKey(definitionId);
                await(connection
                        .preparedQuery("SELECT pg_advisory_xact_lock($1)")
                        .execute(Tuple.of(key)));
                return new IdLockBarrier(pool, key, connection, transaction);
            } catch (RuntimeException | Error failure) {
                await(connection.close());
                throw failure;
            }
        }

        /** Proves the precondition that the expected number of operations is blocked on this lock. */
        void awaitQueued(int expected) {
            long high = (key >>> 32) & 0xFFFFFFFFL;
            long low = key & 0xFFFFFFFFL;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            long waiting = -1;
            while (System.nanoTime() < deadline) {
                waiting = await(pool.preparedQuery("""
                                SELECT count(*) AS waiting FROM pg_locks
                                WHERE locktype = 'advisory' AND NOT granted AND objsubid = 1
                                  AND classid::bigint = $1 AND objid::bigint = $2
                                """).execute(Tuple.of(high, low)))
                        .iterator()
                        .next()
                        .getLong("waiting");
                if (waiting == expected) return;
                Thread.onSpinWait();
            }
            throw new AssertionError("Expected " + expected + " operations queued on the id lock but saw " + waiting);
        }

        void release() {
            if (released) return;
            released = true;
            try {
                await(transaction.rollback());
            } finally {
                await(connection.close());
            }
        }
    }

    /** Owns the application graphs, pools and barriers of one scenario and closes them in reverse order. */
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

        /** Starts an independent application graph with its own observed pool on the shared database. */
        Node node() {
            WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
            Pool pool = observer.observe(ManagedDefinitionDatabase.openPool(vertx, db));
            onClose(() -> await(pool.close()));
            AppComponent app = ManagedDefinitionDatabase.app(
                    vertx, pool, new BootObservation("ownership-unused", 1, "unused", false));
            VertiqueApplicationHandle<?> handle = await(VertiqueApplicationBootstrap.start(
                    VertiqueRuntime.of(vertx, new JsonObject()), ignoredRuntime -> app));
            onClose(() -> await(handle.shutdown()));
            return new Node(pool, observer, app);
        }

        /** Returns the unobserved pool used for test-owned SQL, so application gates never see it. */
        Pool admin() {
            if (admin == null) {
                admin = ManagedDefinitionDatabase.openPool(vertx, db);
                Pool opened = admin;
                onClose(() -> await(opened.close()));
            }
            return admin;
        }

        IdLockBarrier holdIdLock(String definitionId) {
            IdLockBarrier barrier = IdLockBarrier.hold(admin(), definitionId);
            onClose(barrier::release);
            return barrier;
        }

        /** Clears the ids now and again on close, so rows with placeholder sources never reach a later start. */
        void ownRows(String... definitionIds) {
            deleteRows(definitionIds);
            onClose(() -> deleteRows(definitionIds));
        }

        void deleteRows(String... definitionIds) {
            for (String id : definitionIds) {
                await(admin().preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                        .execute(Tuple.of(id)));
            }
        }

        List<Stored> stored(String definitionId) {
            List<Stored> rows = new ArrayList<>();
            for (Row row : await(admin().preparedQuery("""
                            SELECT scope_key, definition_version, status, activation_token::text AS activation_token,
                                   plan_hash, status_changed_at
                            FROM workflow_definitions WHERE definition_id = $1
                            ORDER BY scope_key NULLS FIRST, definition_version
                            """).execute(Tuple.of(definitionId)))) {
                rows.add(new Stored(
                        row.getString("scope_key"),
                        row.getLong("definition_version"),
                        row.getString("status"),
                        row.getString("activation_token"),
                        row.getString("plan_hash"),
                        row.getOffsetDateTime("status_changed_at")));
            }
            rows.sort(Comparator.comparing(Stored::scopeKey).thenComparingLong(Stored::version));
            return rows;
        }

        /** Ages one outstanding claim past twice the staleness bound without touching any other row. */
        void ageClaim(WorkflowDefinitionsConfig config, DefinitionKey key, String token) {
            long ageSeconds = 2 * config.maxStalenessSeconds() + 60;
            await(admin().preparedQuery("""
                            UPDATE workflow_definitions
                            SET status_changed_at = clock_timestamp() - ($5::bigint * interval '1 second')
                            WHERE scope_key IS NOT DISTINCT FROM $1 AND definition_id = $2
                              AND definition_version = $3 AND status = 'ACTIVATING' AND activation_token = $4::uuid
                            """)
                    .execute(Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion(), token, ageSeconds)));
        }

        /** Moves a row to a status that a candidate cannot reach through the public operations. */
        void forceStatus(DefinitionKey key, DefinitionStatus status) {
            await(admin().preparedQuery("""
                            UPDATE workflow_definitions SET status = $4
                            WHERE scope_key IS NOT DISTINCT FROM $1 AND definition_id = $2 AND definition_version = $3
                            """)
                    .execute(Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion(), status.name())));
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
