// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import dagger.Module;
import dagger.multibindings.Multibinds;
import java.util.Set;

/**
 * INTERNAL framework seam — consumed by the inbox-outbox adapters and sibling framework modules; not
 * an application contract and outside the maturity promise. Applications use {@code OutboxService},
 * {@code InboxService}, and the extension points the module document lists.
 *
 * <p>Dagger module for the transactional messaging (inbox/outbox) core infrastructure.
 *
 * <p>Declares the {@link OutboxDestinationHandler} multibinding so that destination-specific
 * sub-modules can contribute handlers via {@code @Provides @IntoSet} methods.
 *
 * <p>Installed transitively: {@code TransactionalMessagingPostgresqlModule} includes this module, so an
 * application lists that store adapter module in its component rather than this one.
 */
@Module
public abstract class TransactionalMessagingModule {

    /** Declares the empty set binding for {@link OutboxDestinationHandler} contributions. */
    @Multibinds
    abstract Set<OutboxDestinationHandler> outboxDestinationHandlers();
}
