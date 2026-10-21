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
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Verifies real document restoration and fail-closed startup through generated application graphs. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DurableDefinitionRestartIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_restart_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("a durable YAML definition is restored before bootstrap deployment after restart")
    void restoresActivatedYamlBeforeTraffic(Vertx vertx, VertxTestContext context) {
        String definitionId = "order-fulfillment-doc";
        long version = 1;
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, version);
        SourceMetadata metadata = new SourceMetadata(
                "restart-test",
                "classpath:definitions/it/order-fulfillment.workflow.yaml",
                "revision-1",
                null,
                null,
                Instant.now());
        byte[] yaml = ManagedDefinitionDatabase.yamlFixture();
        List<Pool> pools = new ArrayList<>();
        List<VertiqueApplicationHandle<?>> handles = new ArrayList<>();

        Pool firstPool = ManagedDefinitionDatabase.openPool(vertx, db);
        pools.add(firstPool);
        BootObservation firstObservation = new BootObservation(definitionId, version, "unused", false);
        AppComponent first = ManagedDefinitionDatabase.app(vertx, firstPool, firstObservation);

        Future<Void> work = firstPool
                .preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                .execute(io.vertx.sqlclient.Tuple.of(definitionId))
                .compose(ignored -> VertiqueApplicationBootstrap.start(
                        VertiqueRuntime.of(vertx, new JsonObject()), ignoredRuntime -> first))
                .compose(firstHandle -> {
                    handles.add(firstHandle);
                    WorkflowDefinitionService service = first.definitionService();
                    return service.load(yaml, DocumentFormat.YAML, metadata, null)
                            .compose(compiled -> service.activate(key).map(compiled))
                            .compose(compiled -> service.list(new DefinitionFilter(WorkflowScopes.DEFAULT, null, null))
                                    .map(records -> {
                                        assertThat(compiled.planHash()).isNotBlank();
                                        assertThat(first.workflowRegistry().allRegistered())
                                                .hasSize(1);
                                        assertThat(records).singleElement().satisfies(record -> {
                                            assertThat(record.key()).isEqualTo(key);
                                            assertThat(record.status()).isEqualTo(DefinitionStatus.ACTIVE);
                                            assertThat(record.planHash()).isEqualTo(compiled.planHash());
                                        });
                                        return compiled;
                                    }))
                            .compose(compiled -> firstHandle.shutdown().map(compiled))
                            .compose(compiled -> firstPool.close().map(compiled));
                })
                .compose(compiled -> {
                    Pool secondPool = ManagedDefinitionDatabase.openPool(vertx, db);
                    pools.add(secondPool);
                    BootObservation secondObservation =
                            new BootObservation(definitionId, version, compiled.planHash(), true);
                    AppComponent second = ManagedDefinitionDatabase.app(vertx, secondPool, secondObservation);
                    return VertiqueApplicationBootstrap.start(
                                    VertiqueRuntime.of(vertx, new JsonObject()), ignored -> second)
                            .compose(secondHandle -> {
                                handles.add(secondHandle);
                                assertThat(second.workflowRegistry()
                                                .resolve(new DefinitionKey(
                                                        WorkflowScopes.DEFAULT, definitionId, version))
                                                .plan()
                                                .planHash())
                                        .isEqualTo(compiled.planHash());
                                assertThat(secondObservation.events())
                                        .containsExactly("bootstrap-complete", "bootstrap-verticle-deployed");
                                return second.definitionService()
                                        .list(new DefinitionFilter(WorkflowScopes.DEFAULT, null, null))
                                        .map(records -> {
                                            assertThat(records).singleElement().satisfies(record -> {
                                                assertThat(record.key()).isEqualTo(key);
                                                assertThat(record.status()).isEqualTo(DefinitionStatus.ACTIVE);
                                                assertThat(record.planHash()).isEqualTo(compiled.planHash());
                                            });
                                            return (Void) null;
                                        })
                                        .compose(ignoredResult -> secondHandle.shutdown())
                                        .compose(ignoredResult -> secondPool.close());
                            });
                });

        finish(context, work, handles, pools);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RestorationFailureCase.class)
    @DisplayName("restoration rejects drift and a missing document loader before bootstrap deployment")
    void rejectsDriftAndMissingLoader(RestorationFailureCase failureCase, Vertx vertx, VertxTestContext context) {
        String definitionId = "order-fulfillment-doc";
        long version = 1;
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, version);
        byte[] original = ManagedDefinitionDatabase.yamlFixture();
        byte[] changed = new String(original, java.nio.charset.StandardCharsets.UTF_8)
                .replace("wait-inventory", "wait-inventory-drift")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SourceMetadata metadata =
                new SourceMetadata("restart-test", "fixture", "revision-1", null, null, Instant.now());
        List<Pool> pools = new ArrayList<>();
        List<VertiqueApplicationHandle<?>> handles = new ArrayList<>();

        Pool firstPool = ManagedDefinitionDatabase.openPool(vertx, db);
        pools.add(firstPool);
        AppComponent first = ManagedDefinitionDatabase.app(
                vertx, firstPool, new BootObservation(definitionId, version, "unused", false));
        Future<Void> work = firstPool
                .preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                .execute(io.vertx.sqlclient.Tuple.of(definitionId))
                .compose(ignored -> VertiqueApplicationBootstrap.start(
                        VertiqueRuntime.of(vertx, new JsonObject()), ignoredRuntime -> first))
                .compose(firstHandle -> {
                    handles.add(firstHandle);
                    return first.definitionService()
                            .load(original, DocumentFormat.YAML, metadata, null)
                            .compose(loaded ->
                                    first.definitionService().activate(key).map(loaded))
                            .compose(loaded -> {
                                assertThat(first.workflowRegistry()
                                                .resolve(new DefinitionKey(
                                                        WorkflowScopes.DEFAULT, definitionId, version))
                                                .plan()
                                                .planHash())
                                        .isEqualTo(loaded.planHash());
                                if (failureCase == RestorationFailureCase.DRIFT) {
                                    WorkflowDefinitionPipeline pipeline = first.definitionPipeline();
                                    CompiledDefinition originalCompiled = pipeline.load(
                                            new DefinitionResource(original, DocumentFormat.YAML, metadata));
                                    CompiledDefinition changedCompiled = pipeline.load(
                                            new DefinitionResource(changed, DocumentFormat.YAML, metadata));
                                    CallbackId originalUpdater = updaterCallbackId(originalCompiled, "wait-inventory");
                                    CallbackId changedUpdater =
                                            updaterCallbackId(changedCompiled, "wait-inventory-drift");
                                    assertThat(originalCompiled.planHash()).isEqualTo(loaded.planHash());
                                    assertThat(changedUpdater).isNotEqualTo(originalUpdater);
                                    assertThat(changedCompiled.planHash()).isNotEqualTo(originalCompiled.planHash());
                                    return firstPool
                                            .preparedQuery("""
                                                UPDATE workflow_definitions
                                                SET document = $1,
                                                    document_digest = encode(sha256(convert_to($1::text, 'UTF8')), 'hex')
                                                WHERE scope_key = 'default' AND definition_id = $2
                                                  AND definition_version = $3
                                                """)
                                            .execute(io.vertx.sqlclient.Tuple.of(
                                                    new String(changed, java.nio.charset.StandardCharsets.UTF_8),
                                                    definitionId,
                                                    version))
                                            .compose(ignoredUpdate -> firstPool
                                                    .preparedQuery("""
                                                            SELECT plan_hash FROM workflow_definitions
                                                            WHERE scope_key = 'default' AND definition_id = $1
                                                              AND definition_version = $2
                                                            """)
                                                    .execute(io.vertx.sqlclient.Tuple.of(definitionId, version)))
                                            .compose(rows -> {
                                                assertThat(rows.iterator()
                                                                .next()
                                                                .getString("plan_hash"))
                                                        .isEqualTo(loaded.planHash());
                                                return firstHandle.shutdown();
                                            })
                                            .compose(ignoredShutdown -> {
                                                Pool driftPool = ManagedDefinitionDatabase.openPool(vertx, db);
                                                pools.add(driftPool);
                                                BootObservation driftObservation = new BootObservation(
                                                        definitionId, version, loaded.planHash(), true);
                                                AppComponent drift = ManagedDefinitionDatabase.app(
                                                        vertx, driftPool, driftObservation);
                                                return VertiqueApplicationBootstrap.start(
                                                                VertiqueRuntime.of(vertx, new JsonObject()),
                                                                ignoredRuntime -> drift)
                                                        .transform(started -> {
                                                            assertThat(started.failed())
                                                                    .isTrue();
                                                            assertThat(started.cause())
                                                                    .isInstanceOf(
                                                                            WorkflowDefinitionLifecycleException.class);
                                                            WorkflowDefinitionLifecycleException failure =
                                                                    (WorkflowDefinitionLifecycleException)
                                                                            started.cause();
                                                            assertThat(failure.reason())
                                                                    .isEqualTo(
                                                                            WorkflowDefinitionLifecycleException.Reason
                                                                                    .DRIFT);
                                                            assertThat(failure.key())
                                                                    .isEqualTo(key);
                                                            assertThat(failure.getMessage())
                                                                    .contains(
                                                                            definitionId,
                                                                            loaded.planHash(),
                                                                            changedCompiled.planHash());
                                                            assertThat(driftObservation.events())
                                                                    .isEmpty();
                                                            return Future.succeededFuture();
                                                        });
                                            });
                                }

                                return firstHandle.shutdown().compose(ignoredShutdown -> {
                                    Pool loaderPool = ManagedDefinitionDatabase.openPool(vertx, db);
                                    pools.add(loaderPool);
                                    BootObservation loaderObservation =
                                            new BootObservation(definitionId, version, "unused", true);
                                    ManagedDefinitionDatabase.NoLoaderAppComponent noLoader =
                                            ManagedDefinitionDatabase.appWithoutLoader(
                                                    vertx, loaderPool, loaderObservation);
                                    return VertiqueApplicationBootstrap.start(
                                                    VertiqueRuntime.of(vertx, new JsonObject()),
                                                    ignoredRuntime -> noLoader)
                                            .transform(started -> {
                                                assertThat(started.failed()).isTrue();
                                                assertThat(started.cause())
                                                        .isInstanceOf(WorkflowDefinitionLifecycleException.class);
                                                WorkflowDefinitionLifecycleException failure =
                                                        (WorkflowDefinitionLifecycleException) started.cause();
                                                assertThat(failure.reason())
                                                        .isEqualTo(
                                                                WorkflowDefinitionLifecycleException.Reason
                                                                        .LOADER_MISSING);
                                                assertThat(failure.key()).isEqualTo(key);
                                                assertThat(failure.getMessage())
                                                        .contains(definitionId, DefinitionKind.DOCUMENT.name());
                                                assertThat(loaderObservation.events())
                                                        .isEmpty();
                                                return Future.succeededFuture();
                                            });
                                });
                            });
                });

        finish(context, work, handles, pools);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static CallbackId updaterCallbackId(CompiledDefinition compiled, String stepId) {
        WorkflowBuilder builder = new WorkflowBuilder();
        compiled.definition().define(builder);
        WorkflowPlan plan = builder.build(
                compiled.definition().definitionId(),
                compiled.definition().definitionVersion(),
                compiled.definition().stateType().getName());
        return plan.nodes().stream()
                .filter(WaitSignalNode.class::isInstance)
                .map(WaitSignalNode.class::cast)
                .filter(node -> node.stepId().equals(stepId))
                .map(WaitSignalNode::stateUpdaterCallbackId)
                .findFirst()
                .orElseThrow();
    }

    private enum RestorationFailureCase {
        DRIFT,
        MISSING_LOADER
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
                cleanup = cleanup.compose(ignored -> pool.close().recover(cause -> {
                    cleanupFailures.add(cause);
                    return Future.succeededFuture();
                }));
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
}
