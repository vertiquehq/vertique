// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.ActivationOutcome;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies the claim release of {@link DefaultWorkflowDefinitionLifecycle} when a durable activation
 * fails and the follow-up read of the definition row fails as well.
 */
class DefaultWorkflowDefinitionLifecycleTest {

    private static final DefinitionKey KEY = new DefinitionKey(null, "wf", 1);
    private static final String PLAN_HASH = "a".repeat(64);

    @Test
    @DisplayName("a failed reread after an activation that published nothing still releases the claim once")
    void releasesClaimWhenNothingWasPublishedAndRereadFails() {
        // Given a CANDIDATE document row, a claim that succeeds, and no loader bound
        FakeStore store = new FakeStore();
        DefaultWorkflowDefinitionLifecycle lifecycle = lifecycle(store, Set.of(), Mockito.mock(WorkflowRegistry.class));

        // When the activation fails with LOADER_MISSING and the reread of the row fails
        Future<Void> result = lifecycle.activate(KEY);

        // Then the activation failed with the original LOADER_MISSING refusal ...
        assertThat(result.failed()).isTrue();
        assertThat(result.cause())
                .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, e -> assertThat(e.reason())
                        .isEqualTo(WorkflowDefinitionLifecycleException.Reason.LOADER_MISSING));
        // ... and the claim was released exactly once
        assertThat(store.releases.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("control: a failed reread after a local publication does not release the claim")
    void keepsClaimWhenPublicationExistsAndRereadFails() {
        // Given a loader that publishes the definition locally, and a completion that fails unknown
        FakeStore store = new FakeStore();
        store.completion = Future.failedFuture(new IllegalStateException("commit outcome unknown"));
        WorkflowRegistry registry = Mockito.mock(WorkflowRegistry.class);
        Mockito.when(registry.resolvePinned("wf", 1)).thenReturn(Mockito.mock(RuntimeWorkflow.class));
        DefaultWorkflowDefinitionLifecycle lifecycle = lifecycle(store, Set.of(publishingLoader()), registry);

        // When the reread of the row fails after the publication
        Future<Void> result = lifecycle.activate(KEY);

        // Then the activation failed and the claim stays owned by reconciliation
        assertThat(result.failed()).isTrue();
        assertThat(store.releases.get()).isZero();
    }

    private static DefaultWorkflowDefinitionLifecycle lifecycle(
            FakeStore store, Set<PersistedDefinitionLoader> loaders, WorkflowRegistry registry) {
        return new DefaultWorkflowDefinitionLifecycle(
                Optional.of(store), () -> registry, loaders, new DefinitionApplyQueue());
    }

    private static PersistedDefinitionLoader publishingLoader() {
        WorkflowDefinition<?, ?> definition = Mockito.mock(WorkflowDefinition.class);
        Mockito.when(definition.definitionId()).thenReturn("wf");
        Mockito.when(definition.definitionVersion()).thenReturn(1L);
        PersistedDefinitionLoader loader = Mockito.mock(PersistedDefinitionLoader.class);
        Mockito.when(loader.supports("YAML")).thenReturn(true);
        Mockito.doReturn(definition).when(loader).load(Mockito.any(), Mockito.any());
        Mockito.when(loader.planHash(definition)).thenReturn(PLAN_HASH);
        return loader;
    }

    /** Serves the candidate row for the first two reads, fails every later read, counts releases. */
    private static final class FakeStore implements WorkflowDefinitionRegistryStore {
        final AtomicInteger releases = new AtomicInteger();
        private final AtomicInteger finds = new AtomicInteger();
        Future<ActivationOutcome> completion = Future.succeededFuture(ActivationOutcome.ACTIVE);

        private static WorkflowDefinitionRecord candidate() {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            return new WorkflowDefinitionRecord(
                    KEY,
                    DefinitionKind.DOCUMENT,
                    PLAN_HASH,
                    DefinitionStatus.CANDIDATE,
                    new DefinitionProvenance("test", null, null, null, null, now),
                    "YAML",
                    now,
                    now);
        }

        @Override
        public Future<Optional<WorkflowDefinitionRecord>> find(DefinitionKey key) {
            if (finds.incrementAndGet() > 2) return Future.failedFuture(new IllegalStateException("reread failed"));
            return Future.succeededFuture(Optional.of(candidate()));
        }

        @Override
        public Future<ActivationClaim> claim(DefinitionKey key) {
            return Future.succeededFuture(new ActivationClaim(key, UUID.randomUUID()));
        }

        @Override
        public Future<Optional<WorkflowDefinitionDocument>> findDocument(DefinitionKey key) {
            return Future.succeededFuture(Optional.of(new WorkflowDefinitionDocument(key, "{}", "YAML")));
        }

        @Override
        public Future<ActivationOutcome> complete(ActivationClaim claim) {
            return completion;
        }

        @Override
        public Future<Void> release(ActivationClaim claim) {
            releases.incrementAndGet();
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> insertCandidate(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<ReconcileOutcome> upsertDocument(
                WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> recomputeActive(String tenantKey, String definitionId) {
            throw new UnsupportedOperationException();
        }
    }
}
