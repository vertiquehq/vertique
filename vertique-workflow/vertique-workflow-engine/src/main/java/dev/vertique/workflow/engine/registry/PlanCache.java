// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.registry;

import dev.vertique.cache.caffeine.ObjectCache;
import dev.vertique.cache.caffeine.ObjectCaches;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.DocumentPlanSource;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * INTERNAL: the size-bounded cache of document plans, bound as the {@link DocumentPlanSource} of the registry.
 *
 * <p>It wraps an object cache named {@value #NAME}. A request for a plan the cache does not hold reads the row and
 * builds the plan once, whatever the number of concurrent requests; a deterministic failure is remembered, a
 * transient one is not. A plan stays resident until the cache evicts it, which can be at any moment, so a caller
 * that needs a plan across a transaction takes a {@linkplain #hold hold} on it: a held plan is answered by
 * {@link #resident} until the hold is released, whatever the cache did.
 *
 * <p>The cache reports every miss, successful and failed load and size eviction to its observers and nothing for a
 * hit.
 */
public final class PlanCache implements DocumentPlanSource {

    /** The cache name its events carry. */
    public static final String NAME = "workflow-plans";

    /** A plan held across a segment, and by how many holders. */
    private record Held(RuntimeWorkflow plan, int holders) {}

    private final DefinitionPlanLoader loader;
    private final ObjectCache<DefinitionKey, RuntimeWorkflow> cache;
    private final ConcurrentHashMap<DefinitionKey, Held> held = new ConcurrentHashMap<>();

    /**
     * Creates the cache.
     *
     * @param loader     reads and builds the plan of a key the cache does not hold
     * @param maxEntries the most plans kept resident; at least 1
     * @param observers  the observers of the cache's events; may be empty
     * @throws IllegalArgumentException if {@code maxEntries} is below 1
     */
    public PlanCache(DefinitionPlanLoader loader, long maxEntries, Set<CacheObserver> observers) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.cache = ObjectCaches.create(NAME, maxEntries, PlanFailures::isDeterministic, observers);
    }

    /**
     * Builds the cache over the durable rows and binds it as the document plan source of the registry, when the
     * registry is the framework's. A registry of another kind keeps its own {@code load}: the cache then builds
     * through a registry of its own.
     *
     * @param store          the durable definition rows
     * @param loaders        the document loaders
     * @param registry       the registry the cache serves
     * @param vertx          the Vert.x instance whose worker threads run the builds, or {@code null} to build on the
     *     calling thread
     * @param schemaProducer when present, fills a legacy row after its first build
     * @param maxEntries     the most plans kept resident; at least 1
     * @param observers      the observers of the cache's events
     * @return the cache
     */
    public static PlanCache bound(
            WorkflowDefinitionRegistryStore store,
            Set<PersistedDefinitionLoader> loaders,
            WorkflowRegistry registry,
            @Nullable Vertx vertx,
            Optional<WorkflowPlanSchemaProducer> schemaProducer,
            long maxEntries,
            Set<CacheObserver> observers) {
        DefaultWorkflowRegistry builder =
                registry instanceof DefaultWorkflowRegistry framework ? framework : new DefaultWorkflowRegistry();
        PlanCache cache = new PlanCache(
                new DefinitionPlanLoader(store, loaders, builder, vertx, schemaProducer), maxEntries, observers);
        if (registry instanceof DefaultWorkflowRegistry framework) {
            framework.bindDocumentPlanSource(cache);
        }
        return cache;
    }

    /** {@inheritDoc} */
    @Override
    public Future<RuntimeWorkflow> load(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        return cache.getOrLoad(key, () -> loader.load(key));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RuntimeWorkflow> resident(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        Held holding = held.get(key);
        if (holding != null) {
            return Optional.of(holding.plan());
        }
        return cache.getIfPresent(key);
    }

    /**
     * Runs the cache's pending maintenance and returns how many plans it keeps.
     *
     * @return the number of resident plans, held plans the cache has evicted excluded
     */
    public long size() {
        return cache.size();
    }

    /**
     * Holds a plan so that {@link #resident} answers it until the hold is released, even after the cache evicts it.
     * Holds are counted, so two holders of one key release independently.
     *
     * @param key  the key of the plan
     * @param plan the plan the holder is working with
     * @return the hold; releasing it more than once has no further effect
     */
    public Hold hold(DefinitionKey key, RuntimeWorkflow plan) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(plan, "plan");
        held.merge(key, new Held(plan, 1), (current, added) -> new Held(current.plan(), current.holders() + 1));
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                held.computeIfPresent(
                        key,
                        (k, current) ->
                                current.holders() <= 1 ? null : new Held(current.plan(), current.holders() - 1));
            }
        };
    }

    /** A claim on a plan that keeps it resident until released. */
    @FunctionalInterface
    public interface Hold {

        /** Releases the hold. */
        void release();
    }
}
