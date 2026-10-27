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
 * This node's own evaluation of the scope rows that newly deployed code conflicts with, kept by the propagation pass
 * and cleared when a version retires. A start reads none of it: a failing version's start is recorded and fails at the
 * executor. The stored mark only feeds the listing.
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
}
