// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies activation compensation and database claim-token fencing through the real store graph. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionActivationIT {

    private static final String COMPLETION_FAILURE_MARKER = "definition-activation-completion-gate";

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_activation_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("a failed completion compensates only its registration and a drifted body never registers")
    void rollsBackOnlyItsOwnFailedAttempt(Vertx vertx, VertxTestContext context) {
        String definitionId = "order-fulfillment-doc";
        DefinitionKey v1 = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, 1);
        DefinitionKey v2 = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, 2);
        List<Pool> pools = new ArrayList<>();
        List<VertiqueApplicationHandle<?>> handles = new ArrayList<>();
        WorkflowDefinitionSqlObserver transactionObserver = new WorkflowDefinitionSqlObserver();
        Pool pool = transactionObserver.observe(ManagedDefinitionDatabase.openPool(vertx, db));
        pools.add(pool);
        AppComponent app =
                ManagedDefinitionDatabase.app(vertx, pool, new BootObservation(definitionId, 1, "unused", false));

        Future<Void> work = pool.preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                .execute(Tuple.of(definitionId))
                .compose(ignored -> VertiqueApplicationBootstrap.start(
                        VertiqueRuntime.of(vertx, new JsonObject()), ignoredRuntime -> app))
                .compose(handle -> {
                    handles.add(handle);
                    SourceMetadata metadata =
                            new SourceMetadata("activation-test", "fixture-v1", "r1", null, null, Instant.now());
                    return app.definitionService()
                            .load(
                                    WorkflowScopes.DEFAULT,
                                    ManagedDefinitionDatabase.yamlFixture(),
                                    DocumentFormat.YAML,
                                    metadata)
                            .compose(loaded -> installCompletionFailure(pool).map(loaded))
                            .compose(loaded -> app.definitionService()
                                    .publish(v1)
                                    .transform(result -> {
                                        assertThat(result.failed()).isTrue();
                                        assertThat(app.workflowRegistry().allRegistered())
                                                .noneMatch(runtime -> runtime.plan()
                                                        .definitionId()
                                                        .equals(definitionId));
                                        return Future.succeededFuture(loaded);
                                    }))
                            .eventually(() -> removeCompletionFailure(pool))
                            .compose(loaded -> app.definitionRegistryStore()
                                    .find(v1)
                                    .map(record -> {
                                        assertThat(record).isPresent();
                                        assertThat(record.orElseThrow().status())
                                                .isEqualTo(DefinitionStatus.CANDIDATE);
                                        assertThat(record.orElseThrow().planHash())
                                                .isEqualTo(loaded.planHash());
                                        return loaded;
                                    }))
                            .compose(loaded ->
                                    app.definitionService().publish(v1).map(loaded))
                            .compose(loaded -> app.definitionRegistryStore()
                                    .find(v1)
                                    .map(record -> {
                                        assertThat(record).isPresent();
                                        assertThat(record.orElseThrow().status())
                                                .isEqualTo(DefinitionStatus.AVAILABLE);
                                        assertThat(app.workflowRegistry()
                                                        .resolve(new DefinitionKey(
                                                                WorkflowScopes.DEFAULT, definitionId, 1))
                                                        .plan()
                                                        .planHash())
                                                .isEqualTo(loaded.planHash());
                                        return loaded;
                                    }))
                            .compose(ignoredResult -> {
                                SourceMetadata v2Metadata = new SourceMetadata(
                                        "activation-test", "fixture-v2", "r2", null, null, Instant.now());
                                byte[] original = ManagedDefinitionDatabase.jsonFixture();
                                return app.definitionService()
                                        .load(WorkflowScopes.DEFAULT, original, DocumentFormat.JSON, v2Metadata)
                                        .compose(loaded -> {
                                            String drifted = driftedJson(original);
                                            var driftedDefinition = app.definitionPipeline()
                                                    .load(new DefinitionResource(
                                                            drifted.getBytes(StandardCharsets.UTF_8),
                                                            DocumentFormat.JSON,
                                                            v2Metadata));
                                            assertThat(driftedDefinition.planHash())
                                                    .isNotEqualTo(loaded.planHash());
                                            // Text and digest change together, so only the plan hash can differ
                                            return pool.preparedQuery("""
                                                            UPDATE workflow_definitions
                                                            SET document = $1,
                                                                document_digest = encode(sha256(convert_to($1::text, 'UTF8')), 'hex')
                                                            WHERE scope_key = 'default' AND definition_id = $2
                                                              AND definition_version = $3
                                                            """)
                                                    .execute(Tuple.of(drifted, definitionId, 2L))
                                                    .map(loaded);
                                        })
                                        .compose(loaded -> app.definitionService()
                                                .publish(v2)
                                                .transform(result -> {
                                                    assertThat(result.failed()).isTrue();
                                                    assertThat(result.cause())
                                                            .isInstanceOf(WorkflowDefinitionLifecycleException.class);
                                                    WorkflowDefinitionLifecycleException failure =
                                                            (WorkflowDefinitionLifecycleException) result.cause();
                                                    assertThat(failure.reason())
                                                            .isEqualTo(
                                                                    WorkflowDefinitionLifecycleException.Reason.DRIFT);
                                                    assertThat(failure.key()).isEqualTo(v2);
                                                    assertThat(failure.getMessage())
                                                            .contains(
                                                                    loaded.planHash(),
                                                                    app.definitionPipeline()
                                                                            .load(new DefinitionResource(
                                                                                    driftedJson(original)
                                                                                            .getBytes(
                                                                                                    StandardCharsets
                                                                                                            .UTF_8),
                                                                                    DocumentFormat.JSON,
                                                                                    v2Metadata))
                                                                            .planHash());
                                                    assertThat(app.workflowRegistry()
                                                                    .allRegistered())
                                                            .extracting(runtime -> runtime.plan()
                                                                    .definitionVersion())
                                                            .containsExactly(1L);
                                                    return Future.succeededFuture(loaded);
                                                }))
                                        .compose(loaded -> app.definitionRegistryStore()
                                                .find(v2)
                                                .map(record -> {
                                                    assertThat(record).isPresent();
                                                    assertThat(record.orElseThrow()
                                                                    .status())
                                                            .isEqualTo(DefinitionStatus.CANDIDATE);
                                                    assertThat(record.orElseThrow()
                                                                    .planHash())
                                                            .isEqualTo(loaded.planHash());
                                                    return (Void) null;
                                                }))
                                        .compose(ignored -> pool.preparedQuery("""
                                                        UPDATE workflow_definitions
                                                        SET document = $1,
                                                            document_digest = encode(sha256(convert_to($1::text, 'UTF8')), 'hex')
                                                        WHERE scope_key = 'default' AND definition_id = $2
                                                          AND definition_version = $3
                                                        """)
                                                .execute(Tuple.of(
                                                        new String(original, StandardCharsets.UTF_8),
                                                        definitionId,
                                                        2L)))
                                        .compose(ignored -> exerciseStaleAttemptCompensation(
                                                pool, transactionObserver, app, v2, v2Metadata, original));
                            })
                            .compose(ignoredResult -> handle.shutdown());
                });

        finish(context, work, handles, pools);
    }

    @Test
    @DisplayName("a fresh claim fences stale completion and release without removing its registration")
    void reclaimsOwnStaleActivation(Vertx vertx, VertxTestContext context) {
        String definitionId = "order-fulfillment-doc";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, 1);
        List<Pool> pools = new ArrayList<>();
        List<VertiqueApplicationHandle<?>> handles = new ArrayList<>();
        Pool pool = ManagedDefinitionDatabase.openPool(vertx, db);
        pools.add(pool);
        AppComponent app =
                ManagedDefinitionDatabase.app(vertx, pool, new BootObservation(definitionId, 1, "unused", false));

        Future<Void> work = pool.preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                .execute(Tuple.of(definitionId))
                .compose(ignored -> VertiqueApplicationBootstrap.start(
                        VertiqueRuntime.of(vertx, new JsonObject()), ignoredRuntime -> app))
                .compose(handle -> {
                    handles.add(handle);
                    SourceMetadata metadata =
                            new SourceMetadata("claim-test", "fixture", "r1", null, null, Instant.now());
                    return app.definitionService()
                            .load(
                                    WorkflowScopes.DEFAULT,
                                    ManagedDefinitionDatabase.yamlFixture(),
                                    DocumentFormat.YAML,
                                    metadata)
                            .compose(loaded -> app.definitionRegistryStore()
                                    .claim(key)
                                    .map(oldClaim -> new ClaimPair(loaded, oldClaim)))
                            .compose(pair -> pool.preparedQuery("""
                                            UPDATE workflow_definitions
                                            SET status_changed_at = clock_timestamp() - interval '5 minutes'
                                            WHERE scope_key = 'default' AND definition_id = $1 AND definition_version = $2
                                            """)
                                    .execute(Tuple.of(definitionId, 1L))
                                    .map(pair))
                            .compose(pair -> app.definitionRegistryStore()
                                    .claim(key)
                                    .map(newClaim -> new ClaimPair(pair.loaded(), pair.oldClaim(), newClaim)))
                            .compose(pair -> {
                                assertThat(pair.newClaim().token())
                                        .isNotEqualTo(pair.oldClaim().token());
                                var compiled = app.definitionPipeline()
                                        .load(new dev.vertique.workflow.definition.source.DefinitionResource(
                                                ManagedDefinitionDatabase.yamlFixture(),
                                                DocumentFormat.YAML,
                                                metadata));
                                app.workflowRegistry().register(WorkflowScopes.DEFAULT, compiled.definition());
                                return app.definitionRegistryStore()
                                        .complete(pair.newClaim())
                                        .map(pair);
                            })
                            .compose(pair -> app.definitionRegistryStore()
                                    .complete(pair.oldClaim())
                                    .transform(staleComplete -> {
                                        assertThat(staleComplete.failed()).isTrue();
                                        assertThat(staleComplete.cause())
                                                .isInstanceOf(WorkflowDefinitionLifecycleException.class);
                                        WorkflowDefinitionLifecycleException failure =
                                                (WorkflowDefinitionLifecycleException) staleComplete.cause();
                                        assertThat(failure.reason())
                                                .isEqualTo(WorkflowDefinitionLifecycleException.Reason.STALE_CLAIM);
                                        return app.definitionRegistryStore()
                                                .release(pair.oldClaim())
                                                .map(pair);
                                    }))
                            .compose(pair -> app.definitionRegistryStore()
                                    .find(key)
                                    .map(record -> {
                                        assertThat(record).isPresent();
                                        assertThat(record.orElseThrow().status())
                                                .isEqualTo(DefinitionStatus.AVAILABLE);
                                        assertThat(record.orElseThrow().planHash())
                                                .isEqualTo(pair.loaded().planHash());
                                        assertThat(app.workflowRegistry()
                                                        .resolve(new DefinitionKey(
                                                                WorkflowScopes.DEFAULT, definitionId, 1))
                                                        .plan()
                                                        .planHash())
                                                .isEqualTo(pair.loaded().planHash());
                                        return (Void) null;
                                    }))
                            .compose(ignoredResult -> handle.shutdown());
                });

        finish(context, work, handles, pools);
    }

    @Test
    @DisplayName("a reclaimed completion fails stale and preserves the replacement publication")
    void surfacesStaleCompletionAfterReplacementActivates(Vertx vertx, VertxTestContext context) {
        String definitionId = "order-fulfillment-doc";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, 1);
        List<Pool> pools = new ArrayList<>();
        List<VertiqueApplicationHandle<?>> handles = new ArrayList<>();
        WorkflowDefinitionSqlObserver observerA = new WorkflowDefinitionSqlObserver();
        WorkflowDefinitionSqlObserver observerB = new WorkflowDefinitionSqlObserver();
        WorkflowDefinitionSqlObserver.CompletionLockGate gateA = observerA.holdNextCompletionLockAfterClaim();
        WorkflowDefinitionSqlObserver.CompletionLockGate gateB = observerB.holdNextCompletionLockAfterClaim();
        Pool poolA = observerA.observe(ManagedDefinitionDatabase.openPool(vertx, db));
        Pool poolB = observerB.observe(ManagedDefinitionDatabase.openPool(vertx, db));
        pools.add(poolA);
        pools.add(poolB);
        BootObservation observationA = new BootObservation(definitionId, 1, "unused", false);
        BootObservation observationB = new BootObservation(definitionId, 1, "unused", false);
        AppComponent appA = ManagedDefinitionDatabase.app(vertx, poolA, observationA);
        AppComponent appB = ManagedDefinitionDatabase.app(vertx, poolB, observationB);
        SourceMetadata metadata = new SourceMetadata(
                "activation-race-test", "same-persisted-document", "revision-1", null, null, Instant.now());

        Future<Void> work = poolA.preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                .execute(Tuple.of(definitionId))
                .compose(ignored -> VertiqueApplicationBootstrap.start(
                        VertiqueRuntime.of(vertx, new JsonObject()), ignoredRuntime -> appA))
                .compose(handleA -> {
                    handles.add(handleA);
                    return VertiqueApplicationBootstrap.start(
                                    VertiqueRuntime.of(vertx, new JsonObject()), ignoredRuntime -> appB)
                            .map(handleB -> {
                                handles.add(handleB);
                                return (Void) null;
                            });
                })
                .compose(ignored -> appA.definitionService()
                        .load(
                                WorkflowScopes.DEFAULT,
                                ManagedDefinitionDatabase.yamlFixture(),
                                DocumentFormat.YAML,
                                metadata)
                        .map(loaded -> new PendingActivation(
                                loaded, appA.definitionService().publish(key))))
                .compose(pending -> gateA.observed().map(pending))
                .compose(pending -> readActivationState(poolA, key).map(state -> {
                    assertThat(state.status()).isEqualTo(DefinitionStatus.PUBLISHING.name());
                    assertThat(state.publishToken()).isNotBlank();
                    assertThat(state.planHash()).isEqualTo(pending.loaded().planHash());
                    RuntimeWorkflow publicationA = appA.workflowRegistry()
                            .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, key.definitionVersion()));
                    assertThat(publicationA).isNotNull();
                    assertThat(publicationA.plan().planHash()).isEqualTo(state.planHash());
                    return new HeldPublication(
                            pending.activation(), publicationA, state.publishToken(), state.planHash());
                }))
                .compose(heldA -> poolB.preparedQuery("""
                                UPDATE workflow_definitions
                                SET status_changed_at = clock_timestamp() - interval '5 minutes'
                                WHERE scope_key = 'default' AND definition_id = $1 AND definition_version = $2
                                  AND status = 'PUBLISHING' AND publish_token = $3::uuid
                                """)
                        .execute(Tuple.of(definitionId, key.definitionVersion(), heldA.publishToken()))
                        .map(heldA))
                .compose(heldA -> readActivationState(poolB, key).map(state -> {
                    assertThat(state.status()).isEqualTo(DefinitionStatus.PUBLISHING.name());
                    assertThat(state.publishToken()).isEqualTo(heldA.publishToken());
                    return heldA;
                }))
                .compose(heldA -> {
                    Future<Void> activationB = appB.definitionService().publish(key);
                    return gateB.observed().map(ignored -> new PendingReplacement(heldA, activationB));
                })
                .compose(pendingB -> readActivationState(poolB, key).map(state -> {
                    assertThat(state.status()).isEqualTo(DefinitionStatus.PUBLISHING.name());
                    assertThat(state.publishToken()).isNotBlank();
                    assertThat(state.publishToken())
                            .isNotEqualTo(pendingB.original().publishToken());
                    assertThat(state.planHash()).isEqualTo(pendingB.original().planHash());
                    RuntimeWorkflow publicationB = appB.workflowRegistry()
                            .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, key.definitionVersion()));
                    assertThat(publicationB).isNotNull();
                    assertThat(publicationB).isNotSameAs(pendingB.original().publication());
                    assertThat(publicationB.plan().planHash()).isEqualTo(state.planHash());
                    return new HeldReplacement(
                            pendingB.original(), pendingB.activation(), publicationB, state.publishToken());
                }))
                .compose(heldB -> {
                    gateB.release();
                    return heldB.activation().map(ignored -> heldB);
                })
                .compose(heldB -> readActivationState(poolB, key).map(state -> {
                    assertThat(state.status()).isEqualTo(DefinitionStatus.AVAILABLE.name());
                    assertThat(state.publishToken()).isNull();
                    assertThat(state.planHash()).isEqualTo(heldB.original().planHash());
                    assertThat(appB.workflowRegistry()
                                    .resolve(new DefinitionKey(
                                            WorkflowScopes.DEFAULT, definitionId, key.definitionVersion())))
                            .isSameAs(heldB.publication());
                    return heldB;
                }))
                .compose(heldB -> {
                    gateA.release();
                    return heldB.original().activation().transform(result -> {
                        assertThat(result.failed()).isTrue();
                        assertThat(result.cause()).isInstanceOf(WorkflowDefinitionLifecycleException.class);
                        WorkflowDefinitionLifecycleException failure =
                                (WorkflowDefinitionLifecycleException) result.cause();
                        assertThat(failure.reason()).isEqualTo(WorkflowDefinitionLifecycleException.Reason.STALE_CLAIM);
                        assertThat(failure.key()).isEqualTo(key);
                        return Future.succeededFuture(heldB);
                    });
                })
                .compose(heldB -> readActivationState(poolB, key).map(state -> {
                    assertThat(state.status()).isEqualTo(DefinitionStatus.AVAILABLE.name());
                    assertThat(state.publishToken()).isNull();
                    assertThat(state.planHash()).isEqualTo(heldB.original().planHash());
                    assertThat(appA.workflowRegistry().allRegistered())
                            .noneMatch(runtime -> runtime == heldB.original().publication());
                    assertThat(appB.workflowRegistry()
                                    .resolve(new DefinitionKey(
                                            WorkflowScopes.DEFAULT, definitionId, key.definitionVersion())))
                            .isSameAs(heldB.publication());
                    assertThat(heldB.publication().plan().planHash()).isEqualTo(state.planHash());
                    return (Void) null;
                }))
                .eventually(() -> {
                    gateA.release();
                    gateB.release();
                    return Future.succeededFuture();
                });

        finish(context, work, handles, pools);
    }

    @Test
    @DisplayName("a committed activation succeeds after its transaction response is lost")
    void acceptsCommittedActivationAfterResponseLoss(Vertx vertx, VertxTestContext context) {
        String definitionId = "order-fulfillment-doc";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, 1);
        List<Pool> pools = new ArrayList<>();
        List<VertiqueApplicationHandle<?>> handles = new ArrayList<>();
        Pool verificationPool = ManagedDefinitionDatabase.openPool(vertx, db);
        WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
        Pool activationPool = observer.observe(ManagedDefinitionDatabase.openPool(vertx, db));
        pools.add(verificationPool);
        pools.add(activationPool);
        BootObservation preparationObservation = new BootObservation(definitionId, 1, "unused", false);
        BootObservation activationObservation = new BootObservation(definitionId, 1, "unused", false);
        AppComponent preparation = ManagedDefinitionDatabase.app(vertx, verificationPool, preparationObservation);
        AppComponent activation = ManagedDefinitionDatabase.app(vertx, activationPool, activationObservation);
        SourceMetadata metadata =
                new SourceMetadata("response-loss-test", "fixture", "revision-1", null, null, Instant.now());
        WorkflowDefinitionSqlObserver.CompletionResponseLossGate responseLoss =
                observer.loseNextSuccessfulCompletionResponse();

        Future<Void> work = verificationPool
                .preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                .execute(Tuple.of(definitionId))
                .compose(ignored -> preparation
                        .definitionService()
                        .load(
                                WorkflowScopes.DEFAULT,
                                ManagedDefinitionDatabase.yamlFixture(),
                                DocumentFormat.YAML,
                                metadata))
                .compose(loaded -> VertiqueApplicationBootstrap.start(
                                VertiqueRuntime.of(vertx, new JsonObject()), ignoredRuntime -> activation)
                        .compose(handle -> {
                            handles.add(handle);
                            assertThat(activation.workflowDefinitionStore().get(definitionId, key.definitionVersion()))
                                    .isEmpty();
                            Future<Void> publicActivation =
                                    activation.definitionService().publish(key);
                            return responseLoss
                                    .injected()
                                    .compose(failure -> {
                                        assertThat(failure)
                                                .hasMessageContaining("controlled committed activation response loss");
                                        return publicActivation;
                                    })
                                    .compose(ignoredActivation -> readActivationState(verificationPool, key))
                                    .map(state -> {
                                        assertThat(state.status()).isEqualTo(DefinitionStatus.AVAILABLE.name());
                                        assertThat(state.publishToken()).isNull();
                                        assertThat(state.planHash()).isEqualTo(loaded.planHash());
                                        RuntimeWorkflow publication = activation
                                                .workflowRegistry()
                                                .resolve(new DefinitionKey(
                                                        WorkflowScopes.DEFAULT, definitionId, key.definitionVersion()));
                                        assertThat(publication).isNotNull();
                                        assertThat(publication.plan().planHash())
                                                .isEqualTo(loaded.planHash());
                                        assertThat(activation
                                                        .workflowDefinitionStore()
                                                        .get(definitionId, key.definitionVersion()))
                                                .isPresent();
                                        return (Void) null;
                                    })
                                    .compose(ignoredActivation -> handle.shutdown());
                        }));

        finish(context, work, handles, pools);
    }

    private static Future<Void> exerciseStaleAttemptCompensation(
            Pool pool,
            WorkflowDefinitionSqlObserver transactionObserver,
            AppComponent app,
            DefinitionKey oldKey,
            SourceMetadata oldMetadata,
            byte[] originalJson) {
        return installCompletionFailure(pool).compose(ignored -> {
            WorkflowDefinitionSqlObserver.CompletionFailureGate gate =
                    transactionObserver.holdNextTransactionFailure(COMPLETION_FAILURE_MARKER);
            Future<Void> oldAttempt = app.definitionService().publish(oldKey);
            Future<Void> scenario = gate.observed()
                    .compose(failure -> {
                        assertThat(failure).hasMessageContaining(COMPLETION_FAILURE_MARKER);
                        return removeCompletionFailure(pool);
                    })
                    .compose(ignoredRemoval -> pool.preparedQuery("""
                                    SELECT publish_token::text AS publish_token
                                    FROM workflow_definitions
                                    WHERE scope_key = 'default' AND definition_id = $1 AND definition_version = $2
                                    """)
                            .execute(Tuple.of(oldKey.definitionId(), oldKey.definitionVersion()))
                            .map(rows -> rows.iterator().next().getString("publish_token")))
                    .compose(oldToken -> pool.preparedQuery("""
                                    UPDATE workflow_definitions
                                    SET status_changed_at = clock_timestamp() - interval '5 minutes'
                                    WHERE scope_key = 'default' AND definition_id = $1 AND definition_version = $2
                                    """)
                            .execute(Tuple.of(oldKey.definitionId(), oldKey.definitionVersion()))
                            .map(oldToken))
                    .compose(oldToken -> app.definitionRegistryStore()
                            .claim(oldKey)
                            .map(newClaim -> new ReclaimedClaim(oldToken, newClaim)))
                    .compose(reclaimed -> {
                        ActivationClaim newClaim = reclaimed.newClaim();
                        assertThat(newClaim.token().toString()).isNotEqualTo(reclaimed.oldToken());
                        assertThat(app.workflowRegistry()
                                        .resolve(new DefinitionKey(
                                                WorkflowScopes.DEFAULT,
                                                oldKey.definitionId(),
                                                oldKey.definitionVersion())))
                                .isNotNull();
                        RuntimeWorkflow oldPublication = app.workflowRegistry()
                                .resolve(new DefinitionKey(
                                        WorkflowScopes.DEFAULT, oldKey.definitionId(), oldKey.definitionVersion()));
                        app.workflowRegistry()
                                .unregister(new DefinitionKey(
                                        WorkflowScopes.DEFAULT, oldKey.definitionId(), oldKey.definitionVersion()));
                        String replacementJson = new String(originalJson, StandardCharsets.UTF_8);
                        var replacement = app.definitionPipeline()
                                .load(new DefinitionResource(
                                        replacementJson.getBytes(StandardCharsets.UTF_8),
                                        DocumentFormat.JSON,
                                        oldMetadata));
                        app.workflowRegistry().register(WorkflowScopes.DEFAULT, replacement.definition());
                        RuntimeWorkflow replacementPublication = app.workflowRegistry()
                                .resolve(new DefinitionKey(
                                        WorkflowScopes.DEFAULT, oldKey.definitionId(), oldKey.definitionVersion()));
                        assertThat(replacementPublication).isNotSameAs(oldPublication);
                        assertThat(replacementPublication.plan().planHash()).isEqualTo(replacement.planHash());
                        assertThat(replacementPublication.plan().planHash())
                                .isEqualTo(oldPublication.plan().planHash());

                        DefinitionKey queuedKey = new DefinitionKey(WorkflowScopes.DEFAULT, oldKey.definitionId(), 3);
                        String queuedJson = new String(originalJson, StandardCharsets.UTF_8)
                                .replace("\"definitionVersion\": 2", "\"definitionVersion\": 3");
                        SourceMetadata queuedMetadata =
                                new SourceMetadata("activation-test", "fixture-v3", "r3", null, null, Instant.now());
                        Future<Void> queuedActivation = app.definitionService()
                                .load(
                                        WorkflowScopes.DEFAULT,
                                        queuedJson.getBytes(StandardCharsets.UTF_8),
                                        DocumentFormat.JSON,
                                        queuedMetadata)
                                .compose(loaded -> {
                                    assertThat(loaded.definitionVersion()).isEqualTo(3);
                                    Future<Void> activation =
                                            app.definitionService().publish(queuedKey);
                                    gate.release();
                                    return activation;
                                });
                        return oldAttempt
                                .transform(result -> {
                                    assertThat(result.failed()).isTrue();
                                    assertThat(result.cause()).isNotNull();
                                    return Future.succeededFuture();
                                })
                                .compose(ignoredOld -> queuedActivation)
                                .compose(ignoredQueued -> app.definitionRegistryStore()
                                        .find(oldKey)
                                        .map(record -> {
                                            assertThat(record).isPresent();
                                            assertThat(record.orElseThrow().status())
                                                    .isEqualTo(DefinitionStatus.PUBLISHING);
                                            return (Void) null;
                                        }))
                                .compose(ignoredQueued -> pool.preparedQuery("""
                                                SELECT status, publish_token::text AS publish_token
                                                FROM workflow_definitions
                                                WHERE scope_key = 'default' AND definition_id = $1
                                                  AND definition_version = $2
                                                """)
                                        .execute(Tuple.of(oldKey.definitionId(), oldKey.definitionVersion()))
                                        .map(rows -> {
                                            Row row = rows.iterator().next();
                                            assertThat(row.getString("status")).isEqualTo("PUBLISHING");
                                            assertThat(row.getString("publish_token"))
                                                    .isEqualTo(newClaim.token().toString());
                                            assertThat(app.workflowRegistry()
                                                            .resolve(new DefinitionKey(
                                                                    WorkflowScopes.DEFAULT,
                                                                    oldKey.definitionId(),
                                                                    oldKey.definitionVersion())))
                                                    .isSameAs(replacementPublication);
                                            assertThat(app.workflowRegistry()
                                                            .resolve(new DefinitionKey(
                                                                    WorkflowScopes.DEFAULT,
                                                                    oldKey.definitionId(),
                                                                    oldKey.definitionVersion()))
                                                            .plan()
                                                            .planHash())
                                                    .isEqualTo(replacement.planHash());
                                            return (Void) null;
                                        }))
                                .compose(ignoredQueued ->
                                        app.definitionRegistryStore().complete(newClaim))
                                .compose(ignoredComplete -> app.definitionRegistryStore()
                                        .find(oldKey)
                                        .map(record -> {
                                            assertThat(record).isPresent();
                                            assertThat(record.orElseThrow().status())
                                                    .isEqualTo(DefinitionStatus.AVAILABLE);
                                            assertThat(app.workflowRegistry()
                                                            .resolve(new DefinitionKey(
                                                                    WorkflowScopes.DEFAULT,
                                                                    oldKey.definitionId(),
                                                                    oldKey.definitionVersion())))
                                                    .isSameAs(replacementPublication);
                                            assertThat(app.workflowRegistry()
                                                            .resolve(new DefinitionKey(
                                                                    WorkflowScopes.DEFAULT,
                                                                    oldKey.definitionId(),
                                                                    oldKey.definitionVersion()))
                                                            .plan()
                                                            .planHash())
                                                    .isEqualTo(replacement.planHash());
                                            return (Void) null;
                                        }));
                    });
            return scenario.eventually(() -> {
                gate.release();
                return Future.succeededFuture();
            });
        });
    }

    private static Future<Void> installCompletionFailure(Pool pool) {
        return pool.query("""
                        CREATE FUNCTION fail_test_definition_completion() RETURNS trigger AS $$
                        BEGIN
                            IF OLD.status = 'PUBLISHING' AND NEW.status = 'AVAILABLE' THEN
                            RAISE EXCEPTION 'definition-activation-completion-gate' USING ERRCODE = '23514';
                            END IF;
                            RETURN NEW;
                        END;
                        $$ LANGUAGE plpgsql
                        """)
                .execute()
                .compose(ignored -> pool.query("""
                                CREATE TRIGGER fail_test_definition_completion
                                BEFORE UPDATE ON workflow_definitions
                                FOR EACH ROW EXECUTE FUNCTION fail_test_definition_completion()
                                """).execute())
                .mapEmpty();
    }

    private static Future<Void> removeCompletionFailure(Pool pool) {
        return pool.query("DROP TRIGGER IF EXISTS fail_test_definition_completion ON workflow_definitions")
                .execute()
                .compose(ignored -> pool.query("DROP FUNCTION IF EXISTS fail_test_definition_completion()")
                        .execute())
                .mapEmpty();
    }

    private static Future<ActivationState> readActivationState(Pool pool, DefinitionKey key) {
        return pool.preparedQuery("""
                        SELECT status, publish_token::text AS publish_token, plan_hash
                        FROM workflow_definitions
                        WHERE scope_key IS NOT DISTINCT FROM $1 AND definition_id = $2 AND definition_version = $3
                        """)
                .execute(Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion()))
                .map(rows -> {
                    var iterator = rows.iterator();
                    assertThat(iterator.hasNext()).isTrue();
                    Row row = iterator.next();
                    return new ActivationState(
                            row.getString("status"), row.getString("publish_token"), row.getString("plan_hash"));
                });
    }

    private static String driftedJson(byte[] original) {
        return new String(original, java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"wait-inventory\"", "\"wait-inventory-drift\"");
    }

    private static void finish(
            VertxTestContext context, Future<Void> work, List<VertiqueApplicationHandle<?>> handles, List<Pool> pools) {
        work.onComplete(result -> {
            Future<Void> cleanup = Future.succeededFuture();
            List<Throwable> cleanupFailures = new ArrayList<>();
            for (int i = handles.size() - 1; i >= 0; i--) {
                VertiqueApplicationHandle<?> handle = handles.get(i);
                cleanup = cleanup.compose(ignored -> handle.shutdown().recover(cause -> {
                    cleanupFailures.add(cause);
                    return Future.succeededFuture();
                }));
            }
            for (int i = pools.size() - 1; i >= 0; i--) {
                Pool pool = pools.get(i);
                cleanup = cleanup.compose(ignored -> removeCompletionFailure(pool)
                        .recover(cause -> {
                            cleanupFailures.add(cause);
                            return Future.succeededFuture();
                        })
                        .compose(closed -> pool.close().recover(cause -> {
                            cleanupFailures.add(cause);
                            return Future.succeededFuture();
                        })));
            }
            cleanup.onComplete(cleaned -> {
                if (cleaned.failed()) {
                    cleanupFailures.add(cleaned.cause());
                }
                if (result.failed()) {
                    cleanupFailures.forEach(result.cause()::addSuppressed);
                    context.failNow(result.cause());
                } else if (!cleanupFailures.isEmpty()) {
                    Throwable failure = cleanupFailures.get(0);
                    cleanupFailures.stream().skip(1).forEach(failure::addSuppressed);
                    context.failNow(failure);
                } else {
                    context.completeNow();
                }
            });
        });
    }

    private record ReclaimedClaim(String oldToken, ActivationClaim newClaim) {}

    private record ActivationState(String status, String publishToken, String planHash) {}

    private record PendingActivation(CompiledDefinitionRef loaded, Future<Void> activation) {}

    private record HeldPublication(
            Future<Void> activation, RuntimeWorkflow publication, String publishToken, String planHash) {}

    private record PendingReplacement(HeldPublication original, Future<Void> activation) {}

    private record HeldReplacement(
            HeldPublication original, Future<Void> activation, RuntimeWorkflow publication, String publishToken) {}

    private record ClaimPair(CompiledDefinitionRef loaded, ActivationClaim oldClaim, ActivationClaim newClaim) {

        ClaimPair(CompiledDefinitionRef loaded, ActivationClaim oldClaim) {
            this(loaded, oldClaim, null);
        }
    }
}
