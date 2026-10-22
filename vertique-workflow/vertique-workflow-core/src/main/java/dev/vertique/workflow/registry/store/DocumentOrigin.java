// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import java.util.Objects;

/** Static source retained for durable reconciliation. */
public record DocumentOrigin(
        DefinitionKey key, WorkflowDefinitionDocument document, DefinitionProvenance provenance, String planHash) {
    public DocumentOrigin {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(planHash, "planHash");
        if (!key.equals(document.key())) throw new IllegalArgumentException("document key must match origin key");
    }
}
