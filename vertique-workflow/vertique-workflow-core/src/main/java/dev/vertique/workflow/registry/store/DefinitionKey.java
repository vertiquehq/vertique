// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import jakarta.annotation.Nullable;

/** Tenant-scoped durable identity; null tenant denotes global scope. */
public record DefinitionKey(@Nullable String tenantKey, String definitionId, long definitionVersion) {
    public DefinitionKey {
        if (tenantKey != null) DefinitionValues.text(tenantKey, "tenantKey", 255, true);
        DefinitionValues.text(definitionId, "definitionId", 255, true);
        if (definitionVersion <= 0) throw new IllegalArgumentException("definitionVersion must be positive");
    }
}
