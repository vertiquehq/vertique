// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowNoCurrentVersionException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This node's view of the activation records: the current version of a definition id in each scope that has a
 * record, keyed by scope and id. The lifecycle updates it after the records are committed, or alone when there is no
 * durable registry, and a start without a version reads it to choose the version to run. With a durable registry the
 * startup step restores every durable record into it.
 *
 * <p>With a durable registry the propagation pass also refreshes the view from the committed records, so that a
 * record written, replaced or removed on another node reaches this one within the propagation bound. A refresh never
 * writes the table and never overrides a change that this node's lifecycle made after the records were read.
 *
 * <p>The view keeps a record even when this node has no code for the version the record names; such a start fails
 * like an explicit start of that version.
 *
 * <p>A start consults only the start scope's record, then the default scope's record. With a durable registry a
 * start without either fails with {@link WorkflowNoCurrentVersionException}. Without one, the default scope's current
 * version of an id with no explicit record is computed from the registry: the highest non-deprecated version that the
 * deployment registered and that does not opt out of activation on deploy. A version published at run time never
 * counts, and {@code deactivateDefault} leaves a cleared mark so the computed version does not come back until the
 * next startup. Nothing computed is written anywhere. Nothing here weights, samples or splits traffic between
 * versions.
 */
@Singleton
final class DefinitionActivations {
    private record Slot(String scopeKey, String definitionId) {}

    private final ConcurrentMap<Slot, DefinitionActivation> records = new ConcurrentHashMap<>();
    private final Set<String> clearedDefaults = ConcurrentHashMap.newKeySet();
    /** The change sequence at which the lifecycle or the startup restore last changed each slot. */
    private final ConcurrentMap<Slot, Long> changed = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();
    private final boolean computed;
    private final Instant computedAt = Instant.now();

    /** Constructs a view for an engine without a durable registry, which computes the default scope's versions. */
    DefinitionActivations() {
        this.computed = true;
    }

    @Inject
    DefinitionActivations(Optional<WorkflowDefinitionRegistryStore> store) {
        this.computed = store.isEmpty();
    }

    /** Stores the record, replacing the scope's earlier record of the id. */
    void put(DefinitionActivation activation) {
        // The change is recorded before the map is mutated, so a refresh that reads the map after the mutation
        // always sees the change mark and leaves the slot alone.
        changed.put(new Slot(activation.scopeKey(), activation.definitionId()), sequence.incrementAndGet());
        store(activation);
    }

    private void store(DefinitionActivation activation) {
        records.put(new Slot(activation.scopeKey(), activation.definitionId()), activation);
        if (WorkflowScopes.DEFAULT.equals(activation.scopeKey())) clearedDefaults.remove(activation.definitionId());
    }

    /**
     * Removes the scope's record of the id; an absent record is a no-op. Removing the default scope's record also
     * marks the id as cleared, so that no computed version stands in for it.
     */
    void remove(String scopeKey, String definitionId) {
        changed.put(new Slot(scopeKey, definitionId), sequence.incrementAndGet());
        discard(scopeKey, definitionId);
    }

    private void discard(String scopeKey, String definitionId) {
        records.remove(new Slot(scopeKey, definitionId));
        if (WorkflowScopes.DEFAULT.equals(scopeKey)) clearedDefaults.add(definitionId);
    }

    /** The current change sequence; take it before reading the committed records and hand it to {@link #refresh}. */
    long mark() {
        return sequence.get();
    }

    /** The definition ids this view holds a record for. */
    Set<String> definitionIds() {
        return records.keySet().stream().map(Slot::definitionId).collect(Collectors.toSet());
    }

    /**
     * Brings the view's records of one definition id in line with the committed ones: a committed record replaces
     * the scope's entry as a whole, and an entry with no committed record is removed. A scope that the lifecycle or
     * the startup restore changed after {@code mark} was taken is left alone, because the committed records were
     * read before that change; the next refresh settles it. The records are kept whether or not this node has code
     * for the version they name. Nothing is written to the table.
     *
     * @param committed the committed records of the id, in any scope
     * @param mark the change sequence taken before the committed records were read
     */
    void refresh(String definitionId, List<DefinitionActivation> committed, long mark) {
        Map<String, DefinitionActivation> byScope = new HashMap<>();
        for (DefinitionActivation record : committed) byScope.put(record.scopeKey(), record);
        for (DefinitionActivation record : byScope.values()) {
            Slot slot = new Slot(record.scopeKey(), definitionId);
            if (changed.getOrDefault(slot, 0L) > mark) continue;
            if (!record.equals(records.get(slot))) store(record);
        }
        for (Slot slot : List.copyOf(records.keySet())) {
            if (!slot.definitionId().equals(definitionId) || byScope.containsKey(slot.scopeKey())) continue;
            if (changed.getOrDefault(slot, 0L) > mark) continue;
            discard(slot.scopeKey(), definitionId);
        }
    }

    /**
     * The records of one scope, or of every scope when {@code scopeKey} is {@code null}, ordered by id and scope.
     * Without a durable registry the computed default records are listed next to the explicit ones.
     */
    List<DefinitionActivation> list(WorkflowRegistry registry, @Nullable String scopeKey) {
        Stream<DefinitionActivation> listed = records.values().stream();
        if (computed && (scopeKey == null || WorkflowScopes.DEFAULT.equals(scopeKey)))
            listed = Stream.concat(listed, computedDefaults(registry));
        return listed.filter(activation -> scopeKey == null || scopeKey.equals(activation.scopeKey()))
                .sorted(Comparator.comparing(DefinitionActivation::definitionId)
                        .thenComparing(DefinitionActivation::scopeKey))
                .toList();
    }

    /**
     * Chooses the runtime a start without a version runs: the version the start scope's record names, else the one
     * the default scope's record names, else, without a durable registry, the computed default. A record names a
     * version of the default scope or of its own scope, and the runtime is resolved from the registry like an
     * explicit start of that version.
     *
     * @throws WorkflowNoCurrentVersionException when there is no record and no computed default
     */
    RuntimeWorkflow resolve(WorkflowRegistry registry, String startScope, String definitionId) {
        DefinitionActivation record = records.get(new Slot(startScope, definitionId));
        if (record == null) record = records.get(new Slot(WorkflowScopes.DEFAULT, definitionId));
        if (record == null && computed)
            record = computedDefault(registry, definitionId).orElse(null);
        if (record == null) throw new WorkflowNoCurrentVersionException(startScope, definitionId);
        return registry.resolve(new DefinitionKey(record.targetScopeKey(), definitionId, record.version()));
    }

    /** The computed default records for every id with no explicit default record and no cleared mark. */
    private Stream<DefinitionActivation> computedDefaults(WorkflowRegistry registry) {
        return registry.allRegistered().stream()
                .filter(runtime -> WorkflowScopes.DEFAULT.equals(runtime.definitionScopeKey()))
                .map(runtime -> runtime.plan().definitionId())
                .distinct()
                .flatMap(id -> computedDefault(registry, id).stream());
    }

    /**
     * The default scope's computed current version of the id: the highest version that is registered by the
     * deployment, is not deprecated and does not opt out. Empty when an explicit record or a cleared mark stands in
     * for it, or when no version qualifies.
     */
    private Optional<DefinitionActivation> computedDefault(WorkflowRegistry registry, String definitionId) {
        if (records.containsKey(new Slot(WorkflowScopes.DEFAULT, definitionId))
                || clearedDefaults.contains(definitionId)) return Optional.empty();
        return registry.keysFor(definitionId).stream()
                .filter(key -> WorkflowScopes.DEFAULT.equals(key.scopeKey()))
                .filter(registry::isDeployed)
                .filter(key -> !registry.isDeprecated(key))
                .filter(key -> registry.registeredDefinition(key)
                        .map(WorkflowDefinition::activateOnDeploy)
                        .orElse(true))
                .max(Comparator.comparingLong(DefinitionKey::definitionVersion))
                .map(key -> new DefinitionActivation(
                        WorkflowScopes.DEFAULT,
                        definitionId,
                        WorkflowScopes.DEFAULT,
                        key.definitionVersion(),
                        computedAt));
    }
}
