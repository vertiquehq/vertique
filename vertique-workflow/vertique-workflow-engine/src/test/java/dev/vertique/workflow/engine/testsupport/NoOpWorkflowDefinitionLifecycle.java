// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.testsupport;

import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link WorkflowDefinitionLifecycle} for tests whose graph needs a lifecycle but whose subject is not the
 * lifecycle: every operation succeeds without changing anything and is recorded in {@link #calls()}, and no
 * activation record is ever listed. A test that needs an operation to act overrides that operation.
 */
public class NoOpWorkflowDefinitionLifecycle implements WorkflowDefinitionLifecycle {
    private final List<String> calls = new CopyOnWriteArrayList<>();

    /** Creates a lifecycle that records its calls. */
    public NoOpWorkflowDefinitionLifecycle() {}

    /**
     * The operations received so far, oldest first, each as its name followed by its arguments, for example
     * {@code publish(scope:id:1)}.
     *
     * @return a snapshot of the recorded calls
     */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public Future<Void> publish(DefinitionKey key) {
        calls.add("publish(" + key + ")");
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> deprecate(DefinitionKey key) {
        calls.add("deprecate(" + key + ")");
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> retire(DefinitionKey key) {
        calls.add("retire(" + key + ")");
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> reinstate(DefinitionKey key) {
        calls.add("reinstate(" + key + ")");
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> activate(String scopeKey, String definitionId, String targetScopeKey, long version) {
        calls.add("activate(" + scopeKey + "," + definitionId + "," + targetScopeKey + "," + version + ")");
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> deactivateDefault(String definitionId) {
        calls.add("deactivateDefault(" + definitionId + ")");
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> follow(String scopeKey, String definitionId) {
        calls.add("follow(" + scopeKey + "," + definitionId + ")");
        return Future.succeededFuture();
    }

    @Override
    public Future<List<DefinitionActivation>> activations(@Nullable String scopeKey) {
        calls.add("activations(" + scopeKey + ")");
        return Future.succeededFuture(List.of());
    }
}
