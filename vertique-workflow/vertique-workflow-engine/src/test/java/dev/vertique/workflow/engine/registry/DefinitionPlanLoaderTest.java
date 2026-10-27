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
}
