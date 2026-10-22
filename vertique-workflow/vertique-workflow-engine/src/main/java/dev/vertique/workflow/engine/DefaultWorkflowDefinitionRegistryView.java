// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The node's {@link WorkflowDefinitionRegistryView} together with the mutators the startup step, the lifecycle and
 * the periodic reconciliation use to feed it.
 *
 * <p>The state is concurrent because the view is read from any thread while a reconciliation applies rows. Each
 * mutation of one key is made under that definition id's {@link DefinitionApplyQueue} slot, so the entries of one
 * key never race each other.
 */
@Singleton
final class DefaultWorkflowDefinitionRegistryView implements WorkflowDefinitionRegistryView {
    private static final Comparator<WorkflowDefinitionRecord> ORDER = Comparator.comparing(
                    (WorkflowDefinitionRecord record) -> record.key().definitionId())
            .thenComparing(record -> record.key().scopeKey(), Comparator.naturalOrder())
            .thenComparingLong(record -> record.key().definitionVersion());

    private final Map<DefinitionKey, WorkflowDefinitionRecord> records = new ConcurrentHashMap<>();
    private final Set<DefinitionKey> failures = ConcurrentHashMap.newKeySet();

    @Inject
    DefaultWorkflowDefinitionRegistryView() {}

    @Override
    public List<WorkflowDefinitionRecord> registered() {
        return records.values().stream().sorted(ORDER).toList();
    }

    @Override
    public long applyFailures() {
        return failures.size();
    }

    /** Records the committed record whose executable form this node holds, replacing an older entry of the key. */
    void applied(WorkflowDefinitionRecord record) {
        records.put(record.key(), record);
        failures.remove(record.key());
    }

    /** Forgets the key: its record is not applied on this node any more, and it no longer counts as failing. */
    void removed(DefinitionKey key) {
        records.remove(key);
        failures.remove(key);
    }

    /** Counts the key as failing until a later attempt applies it or it leaves the table. */
    void failed(DefinitionKey key) {
        failures.add(key);
    }

    /** Whether the key is currently counted as failing. */
    boolean failing(DefinitionKey key) {
        return failures.contains(key);
    }

    /** Clears a failure without recording the row as applied. */
    void settled(DefinitionKey key) {
        failures.remove(key);
    }

    /** The record this node last applied for the key, if any. */
    Optional<WorkflowDefinitionRecord> recorded(DefinitionKey key) {
        return Optional.ofNullable(records.get(key));
    }

    /** Stops counting the failures of rows that are no longer in the table. */
    void retainFailures(Set<DefinitionKey> listed) {
        failures.retainAll(listed);
    }
}
