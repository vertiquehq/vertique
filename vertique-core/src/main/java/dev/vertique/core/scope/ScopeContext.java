// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.scope;

import dev.vertique.core.context.ContextValue;
import java.util.Objects;

/**
 * Immutable {@link ContextValue} that carries one validated {@link ScopeKey}.
 *
 * <p>Construction stores the supplied key without revalidating its text, substituting
 * {@link ScopeKey#DEFAULT}, or installing ambient state. The {@link ContextValue} marker makes the
 * type eligible for explicit use of the existing context-holder write API; it provides no binding,
 * trusted provenance, authentication, or authorization.
 *
 * <p>A missing context remains absent through the holder's existing optional semantics.
 * {@link ScopeKey#DEFAULT} does not itself create or bind a {@code ScopeContext}. Instances are
 * immutable and safe to share across threads.
 *
 * @param scopeKey the partition key; must not be {@code null}
 */
public record ScopeContext(ScopeKey scopeKey) implements ContextValue {

    /**
     * Stores one non-null validated scope key.
     *
     * @param scopeKey the partition key
     * @throws NullPointerException if {@code scopeKey} is {@code null}
     */
    public ScopeContext {
        Objects.requireNonNull(scopeKey, "scopeKey");
    }
}
