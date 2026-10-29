// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.registry;

import static dev.vertique.workflow.engine.registry.PlanStubs.await;
import static dev.vertique.workflow.engine.registry.PlanStubs.failureOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.engine.registry.PlanStubs.BuildProbe;
import dev.vertique.workflow.engine.registry.PlanStubs.CountingDefinitionStore;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies which failures of the plan loader the plan cache keeps and which it forgets. */
class DefinitionPlanLoaderTest {

    private static final DefinitionKey KEY = new DefinitionKey("acme", "loader-document", 1);

    private Vertx vertx;

    @BeforeEach
    void openVertx() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void closeVertx() throws Exception {
        await(vertx.close());
    }

    @Test
    @DisplayName("a transient store failure is not cached and the next load builds the plan")
    void transientStoreFailureIsNotCachedAndTheNextLoadBuildsThePlan() throws Exception {
        // Given a store whose first row read fails like a closed connection and then answers with a valid row
        CountingDefinitionStore store = new CountingDefinitionStore().with(KEY);
        store.failOnce(new WorkflowPersistenceException(
                "connection closed", new java.io.IOException("Connection reset by peer"), true));
        BuildProbe probe = new BuildProbe();
        PlanCache cache = PlanStubs.planCache(store, probe, new DefaultWorkflowRegistry(), vertx, 10);

        // When the key is loaded twice
        Throwable first = failureOf(cache.load(KEY));
        RuntimeWorkflow second = await(cache.load(KEY));

        // Then the first load failed with the persistence error and the second one built the plan
        assertThat(first).isInstanceOf(WorkflowPersistenceException.class).hasMessageContaining("connection closed");
        assertThat(second.plan().definitionId()).isEqualTo(KEY.definitionId());
        assertThat(second.plan().definitionVersion()).isEqualTo(KEY.definitionVersion());
        assertThat(store.finds()).isEqualTo(2);
        assertThat(probe.builds()).isEqualTo(1);

        // When the key is loaded a third time
        int readsBefore = store.reads();
        RuntimeWorkflow third = await(cache.load(KEY));

        // Then the cached plan itself is returned and the store was not read again
        assertThat(third).isSameAs(second);
        assertThat(store.reads()).isEqualTo(readsBefore);
        assertThat(probe.builds()).isEqualTo(1);
    }

    @Test
    @DisplayName("a load that never ends fails with a timeout that is not cached")
    void loadThatNeverEndsFailsWithATimeoutThatIsNotCached() throws Exception {
        // Given a store whose first row read never ends, and a cache that allows a load 200 ms
        CountingDefinitionStore store = new CountingDefinitionStore().with(KEY).hangOnce();
        BuildProbe probe = new BuildProbe();
        PlanCache cache = PlanStubs.planCache(store, probe, new DefaultWorkflowRegistry(), vertx, 10, 200);

        // When the key is loaded twice
        Throwable first = failureOf(cache.load(KEY));
        RuntimeWorkflow second = await(cache.load(KEY));

        // Then the first load timed out and the second one built the plan
        assertThat(first).isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(second.plan().definitionId()).isEqualTo(KEY.definitionId());
        assertThat(probe.builds()).isEqualTo(1);
    }

    @Test
    @DisplayName("a document that does not compile is remembered and a failure of an application validator is not")
    void compileFailureIsRememberedAndValidatorFailureIsNot() throws Exception {
        // Given a document whose first compile fails, and, for another key, a validator that fails once
        DefinitionKey validated = new DefinitionKey("acme", "loader-validated", 1);
        CountingDefinitionStore store = new CountingDefinitionStore().with(KEY).with(validated);
        BuildProbe probe = new BuildProbe().failLoadOnce(new IllegalStateException("cannot compile"));
        java.util.concurrent.atomic.AtomicInteger validations = new java.util.concurrent.atomic.AtomicInteger();
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(null, java.util.Set.of(candidate -> {
            if (validations.incrementAndGet() == 1) throw new IllegalStateException("validator failed");
        }));
        PlanCache cache = PlanStubs.planCache(store, probe, registry, vertx, 10);

        // When the uncompilable document is loaded twice
        Throwable compile = failureOf(cache.load(KEY));
        int readsAfterFirst = store.reads();
        Throwable remembered = failureOf(cache.load(KEY));

        // Then it failed as a definition refusal and the second load answered from the cache
        assertThat(compile).isInstanceOf(dev.vertique.workflow.exception.WorkflowDefinitionException.class);
        assertThat(remembered).isInstanceOf(dev.vertique.workflow.exception.WorkflowDefinitionException.class);
        assertThat(store.reads()).isEqualTo(readsAfterFirst);

        // When the document whose validator fails once is loaded twice
        Throwable validator = failureOf(cache.load(validated));
        RuntimeWorkflow built = await(cache.load(validated));

        // Then the validator's failure was not turned into a definition refusal and was not remembered
        assertThat(validator)
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(dev.vertique.workflow.exception.WorkflowDefinitionException.class);
        assertThat(built.plan().definitionId()).isEqualTo(validated.definitionId());
    }
}
