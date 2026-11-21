// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import dev.vertique.security.SecurityContext;
import java.util.Objects;
import java.util.Optional;

/**
 * SPI for mapping the security context of a caller to the {@link OutboxCallerScopes} whose outbox entries it may
 * see.
 *
 * <p>The default is {@link DefaultOutboxCallerScopesMapper}: the {@code ENTITLEMENT} authorities of a user are its
 * scope keys. An application that grants scopes in another way binds its own implementation:
 *
 * <pre>{@code
 * @Provides
 * static OutboxCallerScopesMapper outboxCallerScopesMapper() {
 *     return context -> ...;
 * }
 * }</pre>
 *
 * <p>{@link TransactionalMessagingModule} declares this type with {@code @BindsOptionalOf}, so an application
 * binding is optional. A consumer injects {@code Optional<OutboxCallerScopesMapper>} and resolves it with
 * {@link #orDefault(Optional)}, which returns the application's binding or the default.
 *
 * <p>An implementation derives the scopes from the context alone, never from request input, and must not block:
 * it is called on the event loop.
 *
 * @see OutboxCallerScopes
 * @see OutboxQuery#visibleTo()
 */
@FunctionalInterface
public interface OutboxCallerScopesMapper {

    /**
     * Maps the caller's security context to the scopes it may see.
     *
     * @param context the authenticated security context of the caller; must not be {@code null}
     * @return the caller's scopes; never {@code null}
     * @throws NullPointerException if {@code context} is {@code null}
     */
    OutboxCallerScopes toCallerScopes(SecurityContext context);

    /**
     * Returns the application's mapper when it binds one, the {@link DefaultOutboxCallerScopesMapper} otherwise.
     * This is the one place a consumer resolves the optional binding.
     *
     * @param bound the injected optional binding; must not be {@code null}
     * @return the mapper to use; never {@code null}
     * @throws NullPointerException if {@code bound} is {@code null}
     */
    static OutboxCallerScopesMapper orDefault(Optional<OutboxCallerScopesMapper> bound) {
        return Objects.requireNonNull(bound, "bound").orElseGet(DefaultOutboxCallerScopesMapper::new);
    }
}
