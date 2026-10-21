// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps the id and version arguments of the default-scope registry methods to a key. Input that a
 * {@link DefinitionKey} rejects can never be registered, so it maps to empty and each method keeps its
 * answer for an unregistered version.
 */
final class DefinitionKeys {

    private DefinitionKeys() {}

    static Optional<DefinitionKey> defaultScope(String definitionId, long version) {
        Objects.requireNonNull(definitionId, "definitionId");
        try {
            return Optional.of(new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, version));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
