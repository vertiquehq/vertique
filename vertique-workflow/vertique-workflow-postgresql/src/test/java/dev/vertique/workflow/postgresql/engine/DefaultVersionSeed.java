// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Makes the code definitions of an application graph that never ran the startup step current in the default scope,
 * for tests that start workflows without a version: it records each registered default-scope version as available
 * and activates it explicitly, the way an operator would.
 */
final class DefaultVersionSeed {

    private DefaultVersionSeed() {}

    /**
     * Records every default-scope version of the given ids, or of every id when none is given, and activates the
     * highest version of each. An id that has any row in the table no longer admits a pin on a version that has no
     * row of its own, so a test that pins versions directly leaves those ids out.
     */
    static void recordAndActivate(
            PgWorkflowDefinitionRegistryStore store,
            WorkflowDefinitionLifecycle lifecycle,
            WorkflowRegistry registry,
            String... definitionIds) {
        Instant now = Instant.now();
        Set<String> wanted = Set.of(definitionIds);
        registry.codeDefinitions().stream()
                .filter(runtime -> WorkflowScopes.DEFAULT.equals(runtime.definitionScopeKey()))
                .filter(runtime ->
                        wanted.isEmpty() || wanted.contains(runtime.plan().definitionId()))
                .sorted(Comparator.comparing(
                                (RuntimeWorkflow runtime) -> runtime.plan().definitionId())
                        .thenComparingLong(runtime -> runtime.plan().definitionVersion()))
                .forEach(runtime -> {
                    String id = runtime.plan().definitionId();
                    long version = runtime.plan().definitionVersion();
                    await(store.upsertCode(new WorkflowDefinitionRecord(
                            new DefinitionKey(WorkflowScopes.DEFAULT, id, version),
                            DefinitionKind.CODE,
                            runtime.contractName(),
                            runtime.plan().stateTypeName(),
                            runtime.plan().planHash(),
                            DefinitionStatus.AVAILABLE,
                            new DefinitionProvenance("code", "test", null, null, null, now),
                            null,
                            now,
                            now,
                            Map.of(),
                            null)));
                    await(lifecycle.activate(WorkflowScopes.DEFAULT, id, WorkflowScopes.DEFAULT, version));
                });
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("seeding the default version failed", failure);
        }
    }
}
