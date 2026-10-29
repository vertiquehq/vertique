// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.registry;

import static dev.vertique.workflow.engine.registry.PlanStubs.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.engine.registry.PlanStubs.BuildProbe;
import dev.vertique.workflow.engine.registry.PlanStubs.CountingDefinitionStore;
import dev.vertique.workflow.exception.WorkflowDefinitionNotFoundException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Vertx;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the registry answers a document key from the plan cache without ever loading it: a resident plan is
 * returned at once, and a key that was never loaded or has been evicted is absent without a row read or a build.
 */
class PlanCacheResidentTest {

    private static final DefinitionKey K = new DefinitionKey("acme", "resident-document", 1);
    private static final DefinitionKey K2 = new DefinitionKey("acme", "resident-document", 2);

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
    @DisplayName("a resident hit is synchronous and a cold or evicted key is empty without a load")
    void residentHitIsSynchronousAndAColdOrEvictedKeyIsEmptyWithoutALoad() throws Exception {
        // Given a cache of one plan bound as the document plan source of a registry, over a counting store, and the
        // key K loaded and awaited while the key K2 was never loaded
        CountingDefinitionStore store = new CountingDefinitionStore().with(K).with(K2);
        BuildProbe probe = new BuildProbe();
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        PlanCache cache = PlanStubs.planCache(store, probe, registry, vertx, 1);
        registry.bindDocumentPlanSource(cache);
        RuntimeWorkflow loaded = await(registry.load(K));
        int reads = store.reads();
        int builds = probe.builds();
        assertThat(builds).isEqualTo(1);

        // When, on this thread and outside any Vert.x context, K is looked up with find and resolve
        Optional<RuntimeWorkflow> found = registry.find(K);
        RuntimeWorkflow resolved = registry.resolve(K);

        // Then both answer with K's plan at once and nothing was read or built
        assertThat(found).containsSame(loaded);
        assertThat(resolved).isSameAs(loaded);
        assertThat(store.reads()).isEqualTo(reads);
        assertThat(probe.builds()).isEqualTo(builds);

        // When the key that was never loaded is looked up
        Optional<RuntimeWorkflow> cold = registry.find(K2);

        // Then find is empty, resolve fails as an absent definition, and nothing was read or built
        assertThat(cold).isEmpty();
        assertThatThrownBy(() -> registry.resolve(K2)).isInstanceOf(WorkflowDefinitionNotFoundException.class);
        assertThat(store.reads()).isEqualTo(reads);
        assertThat(probe.builds()).isEqualTo(builds);

        // When K2 is loaded, which pushes K out of the one-plan cache, and the cache has run its maintenance
        await(registry.load(K2));
        assertThat(cache.size()).isEqualTo(1);
        int readsAfterSecondLoad = store.reads();
        int buildsAfterSecondLoad = probe.builds();

        // Then K is no longer resident: find is empty and looking it up read and built nothing
        assertThat(registry.find(K)).isEmpty();
        assertThat(store.reads()).isEqualTo(readsAfterSecondLoad);
        assertThat(probe.builds()).isEqualTo(buildsAfterSecondLoad);
    }
}
