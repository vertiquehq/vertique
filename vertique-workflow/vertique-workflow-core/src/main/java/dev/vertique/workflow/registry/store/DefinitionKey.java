// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import jakarta.annotation.Nullable;

/**
 * Tenant-scoped durable identity; null tenant denotes global scope.
 *
 * <p>The tenant key and the definition id must each be nonblank, at most 255 code points long, and free of NUL,
 * unpaired surrogates and ISO control characters (U+0000 to U+001F and U+007F to U+009F); a supplementary
 * character (a surrogate pair) is valid and counts as one code point. The version must be positive. A value
 * that breaks a rule is rejected with {@link IllegalArgumentException}.
 *
 * @param tenantKey the owning tenant, or {@code null} for the global scope
 * @param definitionId the workflow definition id
 * @param definitionVersion the positive definition version
 */
public record DefinitionKey(@Nullable String tenantKey, String definitionId, long definitionVersion) {
    public DefinitionKey {
        if (tenantKey != null) DefinitionValues.identifier(tenantKey, "tenantKey", 255);
        DefinitionValues.identifier(definitionId, "definitionId", 255);
        if (definitionVersion <= 0) throw new IllegalArgumentException("definitionVersion must be positive");
    }
}
