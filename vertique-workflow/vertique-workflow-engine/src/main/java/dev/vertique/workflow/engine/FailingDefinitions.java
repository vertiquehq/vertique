// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.registry.store.DefinitionFailure;
import dev.vertique.workflow.registry.store.DefinitionKey;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * This node's own evaluation of the scope rows that newly deployed code conflicts with. The startup step fills it
 * from the code this node runs, and a new start reads it to refuse a failing version, so a node never refuses
 * a start because of what another node with other code wrote to the table. The stored mark only feeds the listing.
 */
@Singleton
final class FailingDefinitions {
    private record Entry(DefinitionFailure failure, String contractName) {}

    private final ConcurrentMap<DefinitionKey, Entry> failing = new ConcurrentHashMap<>();

    @Inject
    FailingDefinitions() {}

    /** Marks the version as failing on this node; {@code contractName} is the contract the version implements. */
    void mark(DefinitionKey key, DefinitionFailure failure, String contractName) {
        failing.put(key, new Entry(failure, contractName));
    }

    /** Clears this node's mark of the version. */
    void clear(DefinitionKey key) {
        failing.remove(key);
    }

    /** The failure this node evaluated for the version, when it is failing. */
    Optional<DefinitionFailure> find(DefinitionKey key) {
        Entry entry = failing.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.failure());
    }

    /** Whether this node holds a failing version of the id in the scope that implements the contract. */
    boolean holds(String scopeKey, String definitionId, String contractName) {
        return !failing.isEmpty()
                && failing.entrySet().stream()
                        .anyMatch(entry -> entry.getKey().scopeKey().equals(scopeKey)
                                && entry.getKey().definitionId().equals(definitionId)
                                && entry.getValue().contractName().equals(contractName));
    }

    /** Whether this node holds no failing version at all. */
    boolean isEmpty() {
        return failing.isEmpty();
    }
}
