// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionNotFoundException;
import dev.vertique.workflow.registry.RegisteredDefinitionClass;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A registry that holds no plan: {@code find} is empty and {@code resolve} fails the calling test, so a plan can only be
 * reached through {@code load}. {@code load} completes on a worker thread once the test releases it, or fails with a
 * given cause.
 */
final class DeferredRegistry implements WorkflowRegistry {

    private final DefinitionKey key;
    private final RuntimeWorkflow plan;
    private final Throwable failure;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger loads = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();

    private DeferredRegistry(DefinitionKey key, RuntimeWorkflow plan, Throwable failure) {
        this.key = key;
        this.plan = plan;
        this.failure = failure;
    }

    /**
     * A registry whose {@code load} of {@code key} completes with {@code plan} on a worker thread after {@link #release()}.
     *
     * @param key the only key that loads
     * @param plan the plan the load completes with
     * @return the registry
     */
    static DeferredRegistry loadsLater(DefinitionKey key, RuntimeWorkflow plan) {
        return new DeferredRegistry(key, plan, null);
    }

    /**
     * A registry whose {@code load} of {@code key} fails with {@code failure} on a worker thread after {@link #release()}.
     *
     * @param key the only key that loads
     * @param failure the cause the load fails with
     * @return the registry
     */
    static DeferredRegistry failsLater(DefinitionKey key, Throwable failure) {
        return new DeferredRegistry(key, null, failure);
    }

    /** Lets the pending load complete. */
    void release() {
        release.countDown();
    }

    /**
     * The number of {@code load} calls so far.
     *
     * @return the count
     */
    int loads() {
        return loads.get();
    }

    /**
     * The number of {@code find} and {@code resolve} calls so far.
     *
     * @return the count
     */
    int lookups() {
        return lookups.get();
    }

    @Override
    public Future<RuntimeWorkflow> load(DefinitionKey requested) {
        loads.incrementAndGet();
        if (!key.equals(requested)) {
            return Future.failedFuture(new WorkflowDefinitionNotFoundException(requested));
        }
        Promise<RuntimeWorkflow> promise = Promise.promise();
        Thread worker = new Thread(
                () -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        promise.fail(e);
                        return;
                    }
                    if (failure != null) {
                        promise.fail(failure);
                    } else {
                        promise.complete(plan);
                    }
                },
                "deferred-registry-load");
        worker.setDaemon(true);
        worker.start();
        return promise.future();
    }

    @Override
    public Optional<RuntimeWorkflow> find(DefinitionKey requested) {
        lookups.incrementAndGet();
        return Optional.empty();
    }

    @Override
    public RuntimeWorkflow resolve(DefinitionKey requested) {
        lookups.incrementAndGet();
        throw new AssertionError("the plan must be reached through load, not resolve: " + requested);
    }

    @Override
    public void register(WorkflowDefinition<?, ?> def) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<RegisteredDefinitionClass> definitionClass(Class<?> definitionClass) {
        return Optional.empty();
    }

    @Override
    public Collection<RuntimeWorkflow> codeDefinitions() {
        return List.of();
    }
}
