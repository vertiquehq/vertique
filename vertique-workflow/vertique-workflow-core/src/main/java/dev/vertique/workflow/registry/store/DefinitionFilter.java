// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import jakarta.annotation.Nullable;
import java.util.Set;

/**
 * Selection of definition records by scope, definition id and status; a {@code null} component selects every value.
 *
 * @param scopeKey the exact scope to select, or {@code null} for every scope
 * @param definitionId the exact definition id to select, or {@code null} for every id
 * @param statuses the statuses to select, or {@code null} for every status
 */
public record DefinitionFilter(
        @Nullable String scopeKey,
        @Nullable String definitionId,
        @Nullable Set<DefinitionStatus> statuses) {
    public DefinitionFilter {
        if (scopeKey != null) DefinitionValues.text(scopeKey, "scopeKey", 255, true);
        if (definitionId != null) DefinitionValues.text(definitionId, "definitionId", 255, true);
        if (statuses != null) statuses = Set.copyOf(statuses);
    }
}
