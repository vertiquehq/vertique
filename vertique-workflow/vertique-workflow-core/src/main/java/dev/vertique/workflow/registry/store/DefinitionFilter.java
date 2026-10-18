// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.Set;

/** Explicit tenant and status selection; null statuses selects every status. */
public record DefinitionFilter(
        TenantScope scope,
        @Nullable String tenantKey,
        @Nullable Set<DefinitionStatus> statuses) {
    public enum TenantScope {
        ALL,
        GLOBAL,
        TENANT
    }

    public DefinitionFilter {
        Objects.requireNonNull(scope, "scope");
        if (scope == TenantScope.TENANT) DefinitionValues.text(tenantKey, "tenantKey", 255, true);
        else if (tenantKey != null) throw new IllegalArgumentException("ALL and GLOBAL require null tenantKey");
        if (statuses != null) statuses = Set.copyOf(statuses);
    }
}
