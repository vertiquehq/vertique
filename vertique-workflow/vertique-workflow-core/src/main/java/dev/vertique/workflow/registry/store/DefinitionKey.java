// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import dev.vertique.workflow.types.ScopeKeyRules;

/**
 * Scoped durable identity of one definition version; the default scope is {@link WorkflowScopes#DEFAULT}.
 *
 * <p>The scope key and the definition id must each be nonblank, at most 255 code points long, and free of NUL,
 * unpaired surrogates and ISO control characters (U+0000 to U+001F and U+007F to U+009F). The scope key must
 * also be free of format characters (zero-width characters, bidirectional marks and overrides) and must not
 * start or end with whitespace; a supplementary
 * character (a surrogate pair) is valid and counts as one code point. The version must be positive. A missing
 * scope key or definition id is rejected with {@link NullPointerException}, and any other value that breaks a rule
 * with {@link IllegalArgumentException}.
 *
 * @param scopeKey the owning scope; {@link WorkflowScopes#DEFAULT} for the default scope
 * @param definitionId the workflow definition id
 * @param definitionVersion the positive definition version
 */
public record DefinitionKey(String scopeKey, String definitionId, long definitionVersion) {
    public DefinitionKey {
        ScopeKeyRules.require(scopeKey, "scopeKey");
        DefinitionValues.identifier(definitionId, "definitionId", 255);
        if (definitionVersion <= 0) throw new IllegalArgumentException("definitionVersion must be positive");
    }
}
