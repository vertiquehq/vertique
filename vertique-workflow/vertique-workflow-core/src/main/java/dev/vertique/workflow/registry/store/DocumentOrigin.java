// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import java.util.Objects;

/**
 * Static source retained for durable reconciliation.
 *
 * @param key the scoped identity of the definition version
 * @param document the source text; its key must equal {@code key}
 * @param provenance where the definition was loaded from
 * @param planHash the plan hash of the compiled definition
 * @param contractName the logical name of the definition's contract
 * @param stateTypeName the logical name of the definition's state type
 */
public record DocumentOrigin(
        DefinitionKey key,
        WorkflowDefinitionDocument document,
        DefinitionProvenance provenance,
        String planHash,
        String contractName,
        String stateTypeName) {
    public DocumentOrigin {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(planHash, "planHash");
        Objects.requireNonNull(contractName, "contractName");
        Objects.requireNonNull(stateTypeName, "stateTypeName");
        if (!key.equals(document.key())) throw new IllegalArgumentException("document key must match origin key");
    }
}
