// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import dagger.Module;
import dagger.multibindings.Multibinds;
import java.util.Set;

/**
 * Dagger module for the transactional messaging (inbox/outbox) core infrastructure.
 *
 * <p>Declares the {@link OutboxDestinationHandler} multibinding so that destination-specific
 * sub-modules can contribute handlers via {@code @Provides @IntoSet} methods.
 *
 * <p>Include this module in your Dagger component when using transactional messaging:
 * <pre>{@code
 * @Component(modules = {TransactionalMessagingModule.class, InboxOutboxPostgresqlModule.class, ...})
 * public interface AppComponent { ... }
 * }</pre>
 */
@Module
public abstract class TransactionalMessagingModule {

    /** Declares the empty set binding for {@link OutboxDestinationHandler} contributions. */
    @Multibinds
    abstract Set<OutboxDestinationHandler> outboxDestinationHandlers();
}
