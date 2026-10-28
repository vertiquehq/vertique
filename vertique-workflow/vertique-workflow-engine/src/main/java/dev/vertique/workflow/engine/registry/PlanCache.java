// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.registry;

import dev.vertique.cache.caffeine.ObjectCache;
import dev.vertique.cache.caffeine.ObjectCaches;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.workflow.engine.registry.DefinitionPlanLoader.Loaded;
import dev.vertique.workflow.engine.registry.FailingMarks.Evaluation;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.DocumentPlanSource;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.EchoedNames;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: the size-bounded cache of document plans, bound as the {@link DocumentPlanSource} of the registry.
 *
 * <p>It wraps an object cache named {@value #NAME}. A request for a plan the cache does not hold reads the row and
 * builds the plan once, whatever the number of concurrent requests; a deterministic failure is remembered, a
 * transient one is not. A plan stays resident until the cache evicts it, which can be at any moment, so a caller
 * that needs a plan across a transaction takes a {@linkplain #hold hold} on it: a held plan is answered by
 * {@link #resident} until the hold is released, whatever the cache did.
 *
 * <p>An entry of a scope other than the default one comes with the {@link Evaluation} of its plan, see
 * {@link FailingMarks}. The evaluation is made against the default scope's state type name at the time of the build and
 * is made again, in place, when {@link #evaluation} finds that name changed.
 *
 * <p>The cache reports every miss, successful and failed load and size eviction to its observers and nothing for a
 * hit.
 */
public final class PlanCache implements DocumentPlanSource {

    private static final Logger log = LoggerFactory.getLogger(PlanCache.class);

    /** The cache name its events carry. */
    public static final String NAME = "workflow-plans";

    /** The longest a load may take before it fails, in milliseconds; a timeout is never remembered. */
    static final long LOAD_TIMEOUT_MS = 30_000;

    /** A plan held across a segment with its evaluation, and by how many holders. */
    private record Held(Loaded loaded, int holders) {}

    private final DefinitionPlanLoader loader;
    private final ObjectCache<DefinitionKey, Loaded> cache;
    private final ConcurrentHashMap<DefinitionKey, Held> held = new ConcurrentHashMap<>();
    private final FailingMarks marks;
    private final long loadTimeoutMs;

    /**
     * Creates the cache.
     *
     * @param loader     reads and builds the plan of a key the cache does not hold
     * @param maxEntries the most plans kept resident; at least 1
     * @param observers  the observers of the cache's events; may be empty
     * @throws IllegalArgumentException if {@code maxEntries} is below 1
     */
    public PlanCache(DefinitionPlanLoader loader, long maxEntries, Set<CacheObserver> observers) {
        this(loader, maxEntries, observers, LOAD_TIMEOUT_MS);
    }

    PlanCache(DefinitionPlanLoader loader, long maxEntries, Set<CacheObserver> observers, long loadTimeoutMs) {
        this.loadTimeoutMs = loadTimeoutMs;
        this.loader = Objects.requireNonNull(loader, "loader");
        this.cache = ObjectCaches.create(NAME, maxEntries, PlanFailures::isDeterministic, observers);
        this.marks = new FailingMarks(loader.store(), this);
    }

    /**
     * The failing marks of the plans this cache builds.
     *
     * @return the marks
     */
    public FailingMarks marks() {
        return marks;
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
        return cache.getOrLoad(
                        key, () -> bounded(loader.loadEvaluated(key)).onFailure(failure -> warnOnce(key, failure)))
                .map(Loaded::plan);
    }

    /** Fails the load with a timeout when it does not end in time; a timeout is not a deterministic failure. */
    private Future<Loaded> bounded(Future<Loaded> load) {
        Vertx vertx = loader.vertx();
        if (vertx == null || load.isComplete()) {
            return load;
        }
        Promise<Loaded> bounded = Promise.promise();
        long timer = vertx.setTimer(
                loadTimeoutMs, ignored -> bounded.tryFail(new TimeoutException("the plan load took too long")));
        load.onComplete(result -> {
            vertx.cancelTimer(timer);
            if (result.succeeded()) {
                bounded.tryComplete(result.result());
            } else {
                bounded.tryFail(result.cause());
            }
        });
        return bounded.future();
    }

    /**
     * Logs a deterministic failure when it is first remembered. The cache keeps the failure, so a key that does not
     * build is reported once for as long as the cache holds it, not on every request.
     */
    private static void warnOnce(DefinitionKey key, Throwable failure) {
        if (!PlanFailures.isDeterministic(failure)) {
            return;
        }
        log.warn(
                "Definition scope '{}' id '{}' version {} does not build and is not runnable: {}",
                EchoedNames.bounded(key.scopeKey()),
                EchoedNames.bounded(key.definitionId()),
                key.definitionVersion(),
                failure.getClass().getName());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RuntimeWorkflow> resident(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(loadedOf(key)).map(Loaded::plan);
    }

    private @Nullable Loaded loadedOf(DefinitionKey key) {
        Held holding = held.get(key);
        if (holding != null) {
            return holding.loaded();
        }
        return cache.getIfPresent(key).orElse(null);
    }

    /**
     * The evaluation of the plan this cache holds for a key, made again first when the default scope's state type
     * name is no longer the one it was made against. Nothing is loaded.
     *
     * @param key the scope, definition id and version
     * @return the evaluation, or empty when the cache holds no plan of the key or the key is of the default scope
     */
    public Optional<Evaluation> evaluation(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        Loaded loaded = loadedOf(key);
        if (loaded == null || loaded.evaluation() == null) {
            return Optional.empty();
        }
        Evaluation evaluation = loaded.evaluation();
        if (!Objects.equals(loader.defaultStateTypeName(key.definitionId()), evaluation.defaultStateTypeName())) {
            loader.reevaluate(evaluation, loaded.plan());
        }
        return Optional.of(evaluation);
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
        Loaded loaded = cache.getIfPresent(key)
                .filter(entry -> entry.plan() == plan)
                .orElseGet(() -> new Loaded(
                        plan, WorkflowScopes.DEFAULT.equals(key.scopeKey()) ? null : loader.evaluateUnstored(plan)));
        held.merge(key, new Held(loaded, 1), (current, added) -> new Held(current.loaded(), current.holders() + 1));
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                held.computeIfPresent(
                        key,
                        (k, current) ->
                                current.holders() <= 1 ? null : new Held(current.loaded(), current.holders() - 1));
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
