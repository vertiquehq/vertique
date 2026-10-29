// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dev.vertique.workflow.registry.store.DefinitionLifecycleChange;
import dev.vertique.workflow.registry.store.DefinitionLifecycleFailure;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycleListener;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records the changes and the failures the registry reports, in the order it reports them. */
@Singleton
public final class RecordingDefinitionListener implements WorkflowDefinitionLifecycleListener {

    private final List<DefinitionLifecycleChange> changes = new CopyOnWriteArrayList<>();
    private final List<DefinitionLifecycleFailure> failures = new CopyOnWriteArrayList<>();

    @Inject
    RecordingDefinitionListener() {}

    @Override
    public void onDefinitionLifecycleChange(DefinitionLifecycleChange change) {
        changes.add(change);
    }

    @Override
    public void onDefinitionLifecycleFailed(DefinitionLifecycleFailure failure) {
        failures.add(failure);
    }

    List<DefinitionLifecycleChange> changes() {
        return List.copyOf(changes);
    }

    List<DefinitionLifecycleFailure> failures() {
        return List.copyOf(failures);
    }

    /** Forgets what was reported so far, such as the changes of startup. */
    void clear() {
        changes.clear();
        failures.clear();
    }
}
