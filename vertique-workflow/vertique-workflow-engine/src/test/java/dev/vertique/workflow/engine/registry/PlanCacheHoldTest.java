// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.registry;

import static dev.vertique.workflow.engine.registry.PlanStubs.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.engine.registry.PlanCache.Hold;
import dev.vertique.workflow.engine.registry.PlanStubs.BuildProbe;
import dev.vertique.workflow.engine.registry.PlanStubs.CountingDefinitionStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the counted holds of the plan cache: a held plan is answered by {@code resident} after the cache evicted
 * it, two holders of one key release independently, a release takes effect once, and the last release lets the plan
 * go.
 */
class PlanCacheHoldTest {

    private static final DefinitionKey K = new DefinitionKey("acme", "hold-document", 1);
    private static final DefinitionKey K2 = new DefinitionKey("acme", "hold-document", 2);

    private Vertx vertx;
    private PlanCache cache;
    private RuntimeWorkflow planK;

    @BeforeEach
    void openCacheOfOnePlanHoldingK() throws Exception {
        vertx = Vertx.vertx();
        CountingDefinitionStore store = new CountingDefinitionStore().with(K).with(K2);
        cache = PlanStubs.planCache(store, new BuildProbe(), new DefaultWorkflowRegistry(), vertx, 1);
        planK = await(cache.load(K));
    }

    @AfterEach
    void closeVertx() throws Exception {
        await(vertx.close());
    }

    /** Loads the other key, which pushes K out of the cache of one plan, and runs the cache's maintenance. */
    private void evictK() throws Exception {
        await(cache.load(K2));
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a held plan stays resident after the cache evicted it and goes when the hold is released")
    void heldPlanStaysResidentAfterEviction() throws Exception {
        // Given a hold on K
        Hold hold = cache.hold(K, planK);

        // When the cache evicts K
        evictK();

        // Then the held plan is still answered, and after the release it is not
        assertThat(cache.resident(K)).containsSame(planK);
        hold.release();
        assertThat(cache.resident(K)).isEmpty();
    }

    @Test
    @DisplayName("two holders of one key release independently and a repeated release counts once")
    void twoHoldersReleaseIndependentlyAndARepeatedReleaseCountsOnce() throws Exception {
        // Given two holds on K, and K evicted from the cache
        Hold first = cache.hold(K, planK);
        Hold second = cache.hold(K, planK);
        evictK();

        // When the first holder releases twice
        first.release();
        first.release();

        // Then the plan is still held for the second holder
        assertThat(cache.resident(K)).containsSame(planK);

        // When the second holder releases
        second.release();

        // Then nothing holds the plan and the cache has evicted it
        assertThat(cache.resident(K)).isEmpty();
    }

    @Test
    @DisplayName("a plan the cache has already evicted can be held and is answered until released")
    void evictedPlanCanBeHeld() throws Exception {
        // Given K evicted from the cache
        evictK();
        assertThat(cache.resident(K)).isEmpty();

        // When a holder takes a hold on the plan it still has
        Hold hold = cache.hold(K, planK);

        // Then the plan is answered until the hold is released
        assertThat(cache.resident(K)).containsSame(planK);
        hold.release();
        assertThat(cache.resident(K)).isEmpty();
    }
}
