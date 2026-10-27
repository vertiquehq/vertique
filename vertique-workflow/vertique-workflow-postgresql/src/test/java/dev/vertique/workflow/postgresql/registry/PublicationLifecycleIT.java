// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.documentText;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the publication lifecycle of durable definitions: a candidate becomes available through a fenced
 * publication, any number of versions and scopes of one id are available together, and only the documented status
 * transitions are accepted.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PublicationLifecycleIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    /** The one contract that every document and code definition of these tests names. */
    @WorkflowContract
    public interface PublicationContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_publication_lifecycle_it")
            .withMigration("classpath:db/migration/workflow");

    private static final Instant LOADED_AT = Instant.parse("2026-10-21T12:00:00Z");

    /** One committed definition row, read straight from the table. */
    private record Row(
            String scopeKey,
            long version,
            String status,
            String publishToken,
            String planHash,
            OffsetDateTime changedAt) {}

    @Test
    @DisplayName("publishing a candidate makes it available and a repeated publication changes nothing")
    void publishMakesCandidateAvailable(Vertx vertx) {
        String id = "pub-doc";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a component and a document loaded into the default scope
            Boot node = scenario.node(none(), unobserved());
            WorkflowDefinitionService service = node.app().definitionService();
            CompiledDefinitionRef loaded = await(service.load(
                    WorkflowScopes.DEFAULT, document(id, 1), DocumentFormat.YAML, source("pub", LOADED_AT)));
            assertThat(rows(scenario, id)).extracting(Row::status).containsExactly("CANDIDATE");

            // When the candidate is published
            await(service.publish(key));

            // Then the row is available without a claim token, and the registry resolves the key
            List<Row> published = rows(scenario, id);
            assertThat(published).singleElement().satisfies(row -> {
                assertThat(row.status()).isEqualTo("AVAILABLE");
                assertThat(row.publishToken()).isNull();
                assertThat(row.planHash()).isEqualTo(loaded.planHash());
            });
            assertThat(DefinitionScenario.planOf(node.app().workflowRegistry(), key)
                            .plan()
                            .planHash())
                    .isEqualTo(loaded.planHash());

            // When the same key is published again
            await(service.publish(key));

            // Then the call succeeds and the row is exactly as it was
            assertThat(rows(scenario, id)).isEqualTo(published);
        }
    }

    @Test
    @DisplayName("two nodes publishing one version leave one available row and no claim behind")
    void concurrentPublishOfOneVersionLeavesOneAvailableRow(Vertx vertx) {
        String id = "race-doc";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given two nodes on one database and a document loaded once as a candidate
            Boot nodeA = scenario.node(none(), unobserved());
            Boot nodeB = scenario.node(none(), unobserved());
            CompiledDefinitionRef loaded = await(nodeA.app()
                    .definitionService()
                    .load(WorkflowScopes.DEFAULT, document(id, 1), DocumentFormat.YAML, source("race", LOADED_AT)));

            // And both publications are queued behind the id lock
            IdLock barrier = IdLock.hold(scenario, id);
            Future<Void> publicationA = nodeA.app().definitionService().publish(key);
            Future<Void> publicationB = nodeB.app().definitionService().publish(key);
            barrier.awaitQueued(2);

            // When the lock is released
            barrier.release();
            Throwable failureA = failureOf(publicationA);
            Throwable failureB = failureOf(publicationB);

            // Then one row remains, available, without a claim token
            assertThat(rows(scenario, id))
                    .extracting(Row::scopeKey, Row::version, Row::status, Row::publishToken)
                    .containsExactly(tuple(WorkflowScopes.DEFAULT, 1L, "AVAILABLE", null));

            // And at least one publication succeeded, and a failing one is a typed refusal naming the key
            assertThat(failureA == null || failureB == null)
                    .as("at least one publication succeeds: a=%s b=%s", failureA, failureB)
                    .isTrue();
            for (Throwable failure : new Throwable[] {failureA, failureB}) {
                if (failure == null) continue;
                assertThat(failure).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                    assertThat(refusal.key()).isEqualTo(key);
                    assertThat(refusal.getMessage()).contains(id);
                });
            }

            // And every node whose publication succeeded resolves the key
            if (failureA == null) {
                assertThat(DefinitionScenario.planOf(nodeA.app().workflowRegistry(), key)
                                .plan()
                                .planHash())
                        .isEqualTo(loaded.planHash());
            }
            if (failureB == null) {
                assertThat(DefinitionScenario.planOf(nodeB.app().workflowRegistry(), key)
                                .plan()
                                .planHash())
                        .isEqualTo(loaded.planHash());
            }
        }
    }

    @Test
    @DisplayName("code and packaged versions are recorded available when the component starts")
    void codeAndPackagedVersionsAreRecordedAvailable(Vertx vertx) {
        String codeId = "pub-code";
        String packagedId = "pub-packaged";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(codeId, packagedId);

            // Given a component with code versions 1 and 2 and a packaged document
            WorkflowDefinitionSource packaged = () -> List.of(new DefinitionResource(
                    document(packagedId, 1), DocumentFormat.YAML, source("packaged", LOADED_AT)));

            // When the component starts
            Boot node = scenario.node(
                    packaged, unobserved(), List.of(new CodeWorkflow(codeId, 1), new CodeWorkflow(codeId, 2)));

            // Then all three rows are available and no row of the table has another status
            assertThat(rows(scenario, codeId))
                    .extracting(Row::version, Row::status, Row::publishToken)
                    .containsExactly(tuple(1L, "AVAILABLE", null), tuple(2L, "AVAILABLE", null));
            assertThat(rows(scenario, packagedId))
                    .extracting(Row::version, Row::status, Row::publishToken)
                    .containsExactly(tuple(1L, "AVAILABLE", null));
            assertThat(statuses(scenario)).containsExactly("AVAILABLE");

            // And both code versions resolve
            WorkflowRegistry registry = node.app().workflowRegistry();
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, codeId, 1))
                            .plan()
                            .definitionVersion())
                    .isEqualTo(1);
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, codeId, 2))
                            .plan()
                            .definitionVersion())
                    .isEqualTo(2);
            assertThat(DefinitionScenario.planOf(registry, new DefinitionKey(WorkflowScopes.DEFAULT, packagedId, 1)))
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("one id and version is available in two scopes at once, each with its own plan")
    void sameIdPublishesInTwoScopesWithoutOwnerConflict(Vertx vertx) {
        String id = "shared-doc";
        DefinitionKey defaultKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        DefinitionKey acmeKey = new DefinitionKey("acme", id, 1);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given the id published in the default scope
            Boot node = scenario.node(none(), unobserved());
            WorkflowDefinitionService service = node.app().definitionService();
            CompiledDefinitionRef defaultLoaded = await(service.load(
                    WorkflowScopes.DEFAULT, document(id, 1), DocumentFormat.YAML, source("default", LOADED_AT)));
            await(service.publish(defaultKey));

            // When the same id and version, with another step graph and the same contract, is loaded into acme and
            // published
            CompiledDefinitionRef acmeLoaded = await(
                    service.load("acme", otherGraphDocument(id, 1), DocumentFormat.YAML, source("acme", LOADED_AT)));
            await(service.publish(acmeKey));

            // Then both rows are available
            assertThat(rows(scenario, id))
                    .extracting(Row::scopeKey, Row::version, Row::status, Row::publishToken)
                    .containsExactly(
                            tuple("acme", 1L, "AVAILABLE", null), tuple(WorkflowScopes.DEFAULT, 1L, "AVAILABLE", null));

            // And the registry resolves each scope's own plan
            WorkflowRegistry registry = node.app().workflowRegistry();
            assertThat(DefinitionScenario.planOf(registry, defaultKey).plan().planHash())
                    .isEqualTo(defaultLoaded.planHash());
            assertThat(DefinitionScenario.planOf(registry, acmeKey).plan().planHash())
                    .isEqualTo(acmeLoaded.planHash());
            assertThat(defaultLoaded.planHash()).isNotEqualTo(acmeLoaded.planHash());
        }
    }

    @Test
    @DisplayName("a candidate cannot be started, even by an explicit version")
    void candidateCannotBeStartedEvenExplicitly(Vertx vertx) {
        String id = "cand-doc";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given a document loaded and not published
            Boot node = scenario.node(none(), unobserved());
            await(node.app()
                    .definitionService()
                    .load(WorkflowScopes.DEFAULT, document(id, 1), DocumentFormat.YAML, source("cand", LOADED_AT)));

            // When a start names version 1, and a start names version 1 of the default scope
            Throwable byVersion = failureOf(node.app().workflowOperations().start(start(id, "cand-version", 1, null)));
            Throwable byScopedVersion =
                    failureOf(node.app().workflowOperations().start(start(id, "cand-scoped", 1, "default")));

            // Then both fail as an unavailable version of the id, and no instance was written
            for (Throwable failure : List.of(byVersion, byScopedVersion)) {
                assertThat(failure).isInstanceOfSatisfying(WorkflowVersionPinUnavailableException.class, refusal -> {
                    assertThat(refusal.scopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
                    assertThat(refusal.definitionId()).isEqualTo(id);
                    assertThat(refusal.version()).isEqualTo(1);
                });
            }
            assertThat(scenario.instanceCount(id)).isZero();
        }
    }

    @Test
    @DisplayName("a deprecated version refuses explicit starts unless they replay an earlier start")
    void deprecatedVersionRefusesExplicitStartsUnlessReplay(Vertx vertx) {
        String id = "dep-doc";
        DefinitionKey versionOne = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given versions 1 and 2 published and an instance started on version 1 with key K
            Boot node = scenario.node(none(), unobserved());
            WorkflowDefinitionService service = node.app().definitionService();
            for (long version = 1; version <= 2; version++) {
                await(service.load(
                        WorkflowScopes.DEFAULT,
                        document(id, version),
                        DocumentFormat.YAML,
                        source("dep-" + version, LOADED_AT)));
                await(service.publish(new DefinitionKey(WorkflowScopes.DEFAULT, id, version)));
            }
            WorkflowInstanceId original = await(node.app().workflowOperations().start(start(id, "K", 1, null)));
            await(node.app().executionProbe().awaitIdle(original, IDLE_TIMEOUT));
            assertThat(scenario.instanceCount(id)).isEqualTo(1);

            // And version 1 is deprecated through the lifecycle
            await(service.deprecate(versionOne));

            // When version 1 is started with a new key, and with K
            Throwable newKey = failureOf(node.app().workflowOperations().start(start(id, "new-key", 1, null)));
            WorkflowInstanceId replayed = await(node.app().workflowOperations().start(start(id, "K", 1, null)));

            // Then the new key is refused as deprecated, and K returns the original instance
            assertThat(newKey).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                assertThat(refusal.reason()).isEqualTo(Reason.DEPRECATED);
                assertThat(refusal.key()).isEqualTo(versionOne);
            });
            assertThat(replayed).isEqualTo(original);
            assertThat(scenario.instanceCount(id)).isEqualTo(1);
            assertThat(rows(scenario, id))
                    .extracting(Row::version, Row::status)
                    .containsExactly(tuple(1L, "DEPRECATED"), tuple(2L, "AVAILABLE"));
        }
    }

    @Test
    @DisplayName("a publishing claim older than the claim timeout is taken over and a younger one is not")
    void expiredPublishingClaimIsTakenOverByAnotherNode(Vertx vertx) {
        String id = "claim-timeout-doc";
        DefinitionKey expiredKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        DefinitionKey youngKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 2);
        JsonObject config = new JsonObject()
                .put(
                        "workflow",
                        new JsonObject().put("definitions", new JsonObject().put("publicationClaimTimeoutSeconds", 5)));

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given two nodes with a claim timeout of 5 seconds and two candidates
            Boot nodeA = scenario.node(none(), unobserved(), List.of(), config);
            Boot nodeB = scenario.node(none(), unobserved(), List.of(), config);
            await(nodeA.app()
                    .definitionService()
                    .load(WorkflowScopes.DEFAULT, document(id, 1), DocumentFormat.YAML, source("claim-1", LOADED_AT)));
            await(nodeA.app()
                    .definitionService()
                    .load(WorkflowScopes.DEFAULT, document(id, 2), DocumentFormat.YAML, source("claim-2", LOADED_AT)));

            // And node A claims both and leaves them PUBLISHING, one claimed 6 seconds ago and one 2 seconds ago
            ActivationClaim expiredClaim =
                    await(nodeA.app().definitionRegistryStore().claim(expiredKey));
            ActivationClaim youngClaim =
                    await(nodeA.app().definitionRegistryStore().claim(youngKey));
            ageClaim(scenario, expiredKey, Duration.ofSeconds(6));
            ageClaim(scenario, youngKey, Duration.ofSeconds(2));
            Row youngBefore = row(scenario, id, 2);
            assertThat(row(scenario, id, 1).status()).isEqualTo("PUBLISHING");
            assertThat(youngBefore.status()).isEqualTo("PUBLISHING");

            // When node B publishes each
            Throwable takeover = failureOf(nodeB.app().definitionService().publish(expiredKey));
            Throwable refusal = failureOf(nodeB.app().definitionService().publish(youngKey));

            // Then the expired claim is taken over: the row is available, the claim is released and node A's claim is
            // obsolete
            assertThat(takeover).isNull();
            assertThat(row(scenario, id, 1)).satisfies(row -> {
                assertThat(row.status()).isEqualTo("AVAILABLE");
                assertThat(row.publishToken()).isNull();
            });
            Throwable obsolete = failureOf(nodeA.app().definitionRegistryStore().complete(expiredClaim));
            assertThat(obsolete).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.STALE_CLAIM);
                assertThat(failure.key()).isEqualTo(expiredKey);
            });

            // And the younger claim is refused as still held, and its row and claim are untouched
            assertThat(refusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(failure.key()).isEqualTo(youngKey);
                assertThat(failure.getMessage()).contains("cannot claim status PUBLISHING");
            });
            assertThat(row(scenario, id, 2)).isEqualTo(youngBefore);
            assertThat(row(scenario, id, 2).publishToken())
                    .isEqualTo(youngClaim.token().toString());
        }
    }

    @Test
    @DisplayName("a retired version reinstates to available and leaves its sibling alone")
    void retiredVersionReinstatesToAvailable(Vertx vertx) {
        String id = "ret-doc";
        DefinitionKey versionOne = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        DefinitionKey versionTwo = new DefinitionKey(WorkflowScopes.DEFAULT, id, 2);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given versions 1 and 2 available, and version 1 deprecated and retired
            Boot node = scenario.node(none(), unobserved());
            WorkflowDefinitionService service = node.app().definitionService();
            CompiledDefinitionRef loadedOne = await(service.load(
                    WorkflowScopes.DEFAULT, document(id, 1), DocumentFormat.YAML, source("ret-1", LOADED_AT)));
            await(service.load(
                    WorkflowScopes.DEFAULT, document(id, 2), DocumentFormat.YAML, source("ret-2", LOADED_AT)));
            await(service.publish(versionOne));
            await(service.publish(versionTwo));
            await(service.deprecate(versionOne));
            await(service.retire(versionOne));
            Row siblingBefore = row(scenario, id, 2);
            assertThat(row(scenario, id, 1).status()).isEqualTo("RETIRED");

            // When version 1 is reinstated
            await(service.reinstate(versionOne));

            // Then it is available again and the registry resolves it
            assertThat(row(scenario, id, 1)).satisfies(row -> {
                assertThat(row.status()).isEqualTo("AVAILABLE");
                assertThat(row.publishToken()).isNull();
            });
            assertThat(DefinitionScenario.planOf(node.app().workflowRegistry(), versionOne)
                            .plan()
                            .planHash())
                    .isEqualTo(loadedOne.planHash());

            // And version 2 is unchanged
            assertThat(row(scenario, id, 2)).isEqualTo(siblingBefore);
        }
    }

    @Test
    @DisplayName("pin admission accepts new pins on available versions only and resurrection on deprecated ones too")
    void pinAdmissionUsesPublicationStatuses(Vertx vertx) {
        String id = "adm-doc";
        DefinitionKey availableKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        DefinitionKey deprecatedKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 2);
        DefinitionKey candidateKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 3);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given version 1 available, version 2 deprecated and version 3 a candidate
            Boot node = scenario.node(none(), unobserved());
            WorkflowDefinitionService service = node.app().definitionService();
            String[] hashes = new String[4];
            for (long version = 1; version <= 3; version++) {
                hashes[(int) version] = await(service.load(
                                WorkflowScopes.DEFAULT,
                                document(id, version),
                                DocumentFormat.YAML,
                                source("adm-" + version, LOADED_AT)))
                        .planHash();
            }
            await(service.publish(availableKey));
            await(service.publish(deprecatedKey));
            await(service.deprecate(deprecatedKey));
            assertThat(rows(scenario, id))
                    .extracting(Row::version, Row::status)
                    .containsExactly(tuple(1L, "AVAILABLE"), tuple(2L, "DEPRECATED"), tuple(3L, "CANDIDATE"));

            // When pins are admitted, each in its own transaction
            Throwable pinAvailable = admit(scenario, false, availableKey, hashes[1]);
            Throwable pinDeprecated = admit(scenario, false, deprecatedKey, hashes[2]);
            Throwable pinCandidate = admit(scenario, false, candidateKey, hashes[3]);
            Throwable resurrectDeprecated = admit(scenario, true, deprecatedKey, hashes[2]);
            Throwable resurrectAvailable = admit(scenario, true, availableKey, hashes[1]);
            Throwable resurrectCandidate = admit(scenario, true, candidateKey, hashes[3]);

            // Then new pins are admitted on version 1 only
            assertThat(pinAvailable).isNull();
            assertRefusedAs(pinDeprecated, Reason.INVALID_TRANSITION, deprecatedKey);
            assertRefusedAs(pinCandidate, Reason.INVALID_TRANSITION, candidateKey);

            // And resurrection is admitted on available and deprecated versions
            assertThat(resurrectDeprecated).isNull();
            assertThat(resurrectAvailable).isNull();
            assertRefusedAs(resurrectCandidate, Reason.INVALID_TRANSITION, candidateKey);
        }
    }

    @Test
    @DisplayName("pin admission judges the pin's own scope when two scopes hold one id and version")
    void pinAdmissionJudgesEachScopeOfOneIdAndVersion(Vertx vertx) {
        String id = "adm-scopes-doc";
        DefinitionKey defaultKey = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);
        DefinitionKey acmeKey = new DefinitionKey("acme", id, 1);
        DefinitionKey acmeCandidateKey = new DefinitionKey("acme", id, 2);
        DefinitionKey globexKey = new DefinitionKey("globex", id, 1);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(id);

            // Given the id and version available in the default scope and in acme, with different plans, and a
            // candidate version 2 in acme
            Boot node = scenario.node(none(), unobserved());
            WorkflowDefinitionService service = node.app().definitionService();
            String defaultHash = await(service.load(
                            WorkflowScopes.DEFAULT, document(id, 1), DocumentFormat.YAML, source("d", LOADED_AT)))
                    .planHash();
            String acmeHash = await(service.load(
                            "acme", otherGraphDocument(id, 1), DocumentFormat.YAML, source("a1", LOADED_AT)))
                    .planHash();
            await(service.load("acme", otherGraphDocument(id, 2), DocumentFormat.YAML, source("a2", LOADED_AT)));
            await(service.publish(defaultKey));
            await(service.publish(acmeKey));
            assertThat(defaultHash).isNotEqualTo(acmeHash);

            // When pins are admitted for each scope with its own plan hash, with the other scope's plan hash, for a
            // scope without a row, and for acme's candidate
            Throwable defaultOwn = admit(scenario, false, defaultKey, defaultHash);
            Throwable acmeOwn = admit(scenario, false, acmeKey, acmeHash);
            Throwable defaultForeignHash = admit(scenario, false, defaultKey, acmeHash);
            Throwable acmeForeignHash = admit(scenario, false, acmeKey, defaultHash);
            Throwable globex = admit(scenario, false, globexKey, defaultHash);
            Throwable acmeCandidate = admit(scenario, false, acmeCandidateKey, acmeHash);

            // Then each scope admits its own pin although the other scope holds the same id and version
            assertThat(defaultOwn).isNull();
            assertThat(acmeOwn).isNull();

            // And a plan hash of the other scope is a drift against this scope's row
            assertRefusedAs(defaultForeignHash, Reason.DRIFT, defaultKey);
            assertRefusedAs(acmeForeignHash, Reason.DRIFT, acmeKey);

            // And a scope without a row and a candidate are refused
            assertRefusedAs(globex, Reason.INVALID_TRANSITION, globexKey);
            assertRefusedAs(acmeCandidate, Reason.INVALID_TRANSITION, acmeCandidateKey);
        }
    }

    @Test
    @DisplayName("only the table's status transitions are accepted and no refused call changes a row")
    void onlyTheTableTransitionsAreAccepted(Vertx vertx) {
        String codeId = "table-code";
        List<String> ids = new ArrayList<>();
        for (Start start : Start.values()) {
            for (Operation operation : Operation.values()) ids.add(tableId(start, operation));
        }
        ids.add(codeId);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(ids.toArray(String[]::new));

            // Given a component whose code provides a version that is later retired
            Boot node = scenario.node(none(), unobserved(), List.of(new CodeWorkflow(codeId, 1)));
            WorkflowDefinitionService service = node.app().definitionService();

            for (Start start : Start.values()) {
                for (Operation operation : Operation.values()) {
                    String id = tableId(start, operation);
                    DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, id, 1);

                    // Given a document row in the starting state
                    CompiledDefinitionRef loaded = await(service.load(
                            WorkflowScopes.DEFAULT, document(id, 1), DocumentFormat.YAML, source(id, LOADED_AT)));
                    reach(scenario, node, start, key, loaded.planHash());
                    Row before = row(scenario, id, 1);
                    assertThat(before.status())
                            .as("%s row before %s", start, operation)
                            .isEqualTo(start.status());
                    Expectation expected = expected(start, operation);

                    // When the operation is attempted
                    Throwable failure = failureOf(operation.apply(service, key));
                    Row after = row(scenario, id, 1);

                    // Then it succeeds and moves the row, or repeats without a change, or is refused without a change
                    String label = start + " " + operation;
                    if (expected.refusal() != null) {
                        assertThat(failure)
                                .as(label)
                                .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                                    assertThat(refusal.reason()).isEqualTo(expected.refusal());
                                    assertThat(refusal.key()).isEqualTo(key);
                                });
                        assertThat(after).as("%s leaves the row alone", label).isEqualTo(before);
                    } else if (expected.status() == null) {
                        assertThat(failure).as(label).isNull();
                        assertThat(after)
                                .as("%s repeats without a change", label)
                                .isEqualTo(before);
                    } else {
                        assertThat(failure).as(label).isNull();
                        assertThat(after.status()).as(label).isEqualTo(expected.status());
                        assertThat(after.publishToken())
                                .as("%s leaves no claim", label)
                                .isNull();
                    }
                }
            }

            // Given a retired code version that a second component does not have
            DefinitionKey codeKey = new DefinitionKey(WorkflowScopes.DEFAULT, codeId, 1);
            await(service.deactivateDefault(codeId));
            await(service.deprecate(codeKey));
            await(service.retire(codeKey));
            Boot withoutClass = scenario.node(none(), unobserved());
            Row retiredCode = row(scenario, codeId, 1);
            assertThat(retiredCode.status()).isEqualTo("RETIRED");

            // When it is reinstated on the component without the class
            Throwable noClass = failureOf(withoutClass.app().definitionService().reinstate(codeKey));

            // Then it fails as unavailable code and the row is unchanged
            assertThat(noClass).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                assertThat(refusal.reason()).isEqualTo(Reason.CODE_UNAVAILABLE);
                assertThat(refusal.key()).isEqualTo(codeKey);
            });
            assertThat(row(scenario, codeId, 1)).isEqualTo(retiredCode);

            // When it is reinstated on the component that has the class
            await(service.reinstate(codeKey));

            // Then it is available
            assertThat(row(scenario, codeId, 1).status()).isEqualTo("AVAILABLE");
        }
    }

    private enum Operation {
        PUBLISH {
            @Override
            Future<Void> apply(WorkflowDefinitionService service, DefinitionKey key) {
                return service.publish(key);
            }
        },
        DEPRECATE {
            @Override
            Future<Void> apply(WorkflowDefinitionService service, DefinitionKey key) {
                return service.deprecate(key);
            }
        },
        RETIRE {
            @Override
            Future<Void> apply(WorkflowDefinitionService service, DefinitionKey key) {
                return service.retire(key);
            }
        },
        REINSTATE {
            @Override
            Future<Void> apply(WorkflowDefinitionService service, DefinitionKey key) {
                return service.reinstate(key);
            }
        };

        abstract Future<Void> apply(WorkflowDefinitionService service, DefinitionKey key);
    }

    /** The states a row is brought to before an operation is attempted on it. */
    private enum Start {
        CANDIDATE("CANDIDATE"),
        PUBLISHING("PUBLISHING"),
        AVAILABLE("AVAILABLE"),
        DEPRECATED_UNPINNED("DEPRECATED"),
        DEPRECATED_PINNED("DEPRECATED"),
        RETIRED("RETIRED");

        private final String status;

        Start(String status) {
            this.status = status;
        }

        String status() {
            return status;
        }
    }

    /**
     * The expected outcome of one operation: a refusal with its reason, a move to a status, or, when both are null, a
     * repeat of a completed transition that is accepted without a change.
     */
    private record Expectation(String status, Reason refusal) {}

    private static Expectation becomes(String status) {
        return new Expectation(status, null);
    }

    private static Expectation repeats() {
        return new Expectation(null, null);
    }

    private static Expectation refused(Reason reason) {
        return new Expectation(null, reason);
    }

    /** The status transition table: every pair that is not listed here is an invalid transition. */
    private static Expectation expected(Start start, Operation operation) {
        Expectation invalid = refused(Reason.INVALID_TRANSITION);
        return switch (start) {
            case CANDIDATE ->
                switch (operation) {
                    case PUBLISH -> becomes("AVAILABLE");
                    case RETIRE -> becomes("RETIRED");
                    default -> invalid;
                };
            case PUBLISHING -> invalid;
            case AVAILABLE ->
                switch (operation) {
                    case PUBLISH -> repeats();
                    case DEPRECATE -> becomes("DEPRECATED");
                    default -> invalid;
                };
            case DEPRECATED_UNPINNED ->
                switch (operation) {
                    case DEPRECATE -> repeats();
                    case RETIRE -> becomes("RETIRED");
                    case REINSTATE -> becomes("AVAILABLE");
                    default -> invalid;
                };
            case DEPRECATED_PINNED ->
                switch (operation) {
                    case DEPRECATE -> repeats();
                    case RETIRE -> refused(Reason.PINNED_INSTANCES);
                    case REINSTATE -> becomes("AVAILABLE");
                    default -> invalid;
                };
            case RETIRED ->
                switch (operation) {
                    case RETIRE -> repeats();
                    case REINSTATE -> becomes("AVAILABLE");
                    default -> invalid;
                };
        };
    }

    private static String tableId(Start start, Operation operation) {
        return "table-" + start.name().toLowerCase().replace('_', '-') + "-"
                + operation.name().toLowerCase();
    }

    /** Brings the loaded candidate to the starting state through the public operations and the store's claim. */
    private static void reach(DefinitionScenario scenario, Boot node, Start start, DefinitionKey key, String planHash) {
        WorkflowDefinitionService service = node.app().definitionService();
        switch (start) {
            case CANDIDATE -> {}
            case PUBLISHING -> await(node.app().definitionRegistryStore().claim(key));
            case AVAILABLE -> await(service.publish(key));
            case DEPRECATED_UNPINNED -> {
                await(service.publish(key));
                await(service.deprecate(key));
            }
            case DEPRECATED_PINNED -> {
                await(service.publish(key));
                await(service.deprecate(key));
                scenario.insertInstance(key.definitionId(), key.definitionVersion(), planHash, "RUNNING");
            }
            case RETIRED -> {
                await(service.publish(key));
                await(service.deprecate(key));
                await(service.retire(key));
            }
        }
    }

    private static void assertRefusedAs(Throwable failure, Reason reason, DefinitionKey key) {
        assertThat(failure).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
            assertThat(refusal.reason()).isEqualTo(reason);
            assertThat(refusal.key()).isEqualTo(key);
        });
    }

    /** Runs one pin admission in its own transaction, rolls it back, and returns the refusal or null. */
    private static Throwable admit(DefinitionScenario scenario, boolean resurrection, DefinitionKey key, String hash) {
        SqlConnection connection = await(scenario.admin().getConnection());
        try {
            Transaction transaction = await(connection.begin());
            PgDbExceptionMapper mapper = new PgDbExceptionMapper();
            Future<Void> admission = resurrection
                    ? DefinitionPinAdmission.admitResurrection(connection, "workflow_definitions", key, hash, mapper)
                    : DefinitionPinAdmission.admitPin(connection, "workflow_definitions", key, hash, mapper);
            Throwable failure = failureOf(admission);
            await(transaction.rollback());
            return failure;
        } finally {
            await(connection.close());
        }
    }

    private static StartCommand start(String id, String idempotencyKey, long version, String targetScope) {
        return new StartCommand(
                id,
                PublicationContract.class.getName(),
                new PlaceOrderDoc("order-" + idempotencyKey),
                idempotencyKey,
                null,
                null,
                version,
                null,
                targetScope);
    }

    private static List<Row> rows(DefinitionScenario scenario, String id) {
        List<Row> rows = new ArrayList<>();
        for (io.vertx.sqlclient.Row row :
                await(scenario.admin().preparedQuery("""
                        SELECT scope_key, definition_version, status, publish_token::text AS publish_token, plan_hash,
                               status_changed_at
                        FROM workflow_definitions WHERE definition_id = $1
                        ORDER BY scope_key, definition_version
                        """).execute(Tuple.of(id)))) {
            rows.add(new Row(
                    row.getString("scope_key"),
                    row.getLong("definition_version"),
                    row.getString("status"),
                    row.getString("publish_token"),
                    row.getString("plan_hash"),
                    row.getOffsetDateTime("status_changed_at")));
        }
        return rows;
    }

    private static Row row(DefinitionScenario scenario, String id, long version) {
        return rows(scenario, id).stream()
                .filter(row -> row.scopeKey().equals(WorkflowScopes.DEFAULT) && row.version() == version)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No default-scope row of " + id + " v" + version));
    }

    /** The distinct statuses found anywhere in the table. */
    private static List<String> statuses(DefinitionScenario scenario) {
        List<String> statuses = new ArrayList<>();
        for (io.vertx.sqlclient.Row row : await(scenario.admin()
                .query("SELECT DISTINCT status FROM workflow_definitions ORDER BY status")
                .execute())) {
            statuses.add(row.getString("status"));
        }
        return statuses;
    }

    /** Moves one outstanding claim's start to the given age, measured by the database clock. */
    private static void ageClaim(DefinitionScenario scenario, DefinitionKey key, Duration age) {
        scenario.ageStatus(key, age.toSeconds());
    }

    /** A document that completes as soon as it starts, so a started instance finishes at once. */
    private static byte[] document(String id, long version) {
        String yaml = documentText(id, version, PublicationContract.class);
        return completingGraph(yaml, "done").getBytes(StandardCharsets.UTF_8);
    }

    /** The same document with another step graph, so its plan hash differs from {@link #document}. */
    private static byte[] otherGraphDocument(String id, long version) {
        String yaml = documentText(id, version, PublicationContract.class);
        return completingGraph(yaml, "finished").getBytes(StandardCharsets.UTF_8);
    }

    private static String completingGraph(String yaml, String stepId) {
        int steps = yaml.indexOf("initialStep:");
        if (steps < 0) throw new IllegalStateException("The workflow fixture has no initial step");
        return yaml.substring(0, steps) + "initialStep: " + stepId + "\nsteps:\n  - id: " + stepId
                + "\n    type: complete\n";
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }

    private static BootObservation unobserved() {
        return new BootObservation("publication-unused", 1, "unused", false);
    }

    /** Holds a definition id's lock in a test-owned transaction so that competing operations queue behind it. */
    private static final class IdLock {
        private final DefinitionScenario scenario;
        private final String definitionId;
        private final SqlConnection connection;
        private final Transaction transaction;
        private boolean released;

        private IdLock(
                DefinitionScenario scenario, String definitionId, SqlConnection connection, Transaction transaction) {
            this.scenario = scenario;
            this.definitionId = definitionId;
            this.connection = connection;
            this.transaction = transaction;
        }

        static IdLock hold(DefinitionScenario scenario, String definitionId) {
            SqlConnection connection = await(scenario.admin().getConnection());
            try {
                Transaction transaction = await(connection.begin());
                await(connection
                        .preparedQuery("SELECT pg_advisory_xact_lock($1)")
                        .execute(Tuple.of(DefinitionIdLock.lockKey(definitionId))));
                IdLock lock = new IdLock(scenario, definitionId, connection, transaction);
                scenario.onClose(lock::release);
                return lock;
            } catch (RuntimeException | Error failure) {
                await(connection.close());
                throw failure;
            }
        }

        /** Waits until the expected number of operations is blocked on this lock. */
        void awaitQueued(int expected) {
            scenario.awaitAdvisory(definitionId, "ExclusiveLock", false, expected);
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

    private record CodeState(String value) {}

    private record CodeStart(String value) {}

    /** A code definition whose only step is its end step. */
    private static final class CodeWorkflow implements WorkflowDefinition<CodeState, Object> {

        private final String definitionId;
        private final long version;

        private CodeWorkflow(String definitionId, long version) {
            this.definitionId = definitionId;
            this.version = version;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<Object> contract() {
            return (Class<Object>) (Class<?>) PublicationContract.class;
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
                    .initialStep("done")
                    .complete("done");
        }
    }
}
