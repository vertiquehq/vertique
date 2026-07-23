// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.delayedjob;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;

/**
 * Dagger module that registers the delayed-job outbox destination handler.
 *
 * <p>The handler declares its own {@link dev.vertique.inboxoutbox.ClaimScope} via
 * {@link DelayedJobOutboxDestinationHandler#claimScope()}, so this module does not need to
 * contribute a separate target-id multibinding.
 *
 * <p>Include this module in your Dagger component alongside
 * {@link dev.vertique.inboxoutbox.TransactionalMessagingModule} and a PostgreSQL outbox module
 * to enable delayed-job relay support:
 * <pre>{@code
 * @Component(modules = {
 *     TransactionalMessagingModule.class,
 *     TransactionalMessagingPostgresqlModule.class,
 *     TransactionalMessagingDelayedJobModule.class,
 *     // ...
 * })
 * public interface AppComponent { ... }
 * }</pre>
 *
 * <p>The module contributes {@link DelayedJobOutboxDestinationHandler} into the
 * {@code Set<OutboxDestinationHandler>} multibinding declared by
 * {@link dev.vertique.inboxoutbox.TransactionalMessagingModule}.
 */
@Module
public abstract class TransactionalMessagingDelayedJobModule {

    /**
     * Contributes the {@link DelayedJobOutboxDestinationHandler} into the
     * {@code Set<OutboxDestinationHandler>} multibinding.
     *
     * @param handler the handler to register
     * @return the handler as an {@link OutboxDestinationHandler} set element
     */
    @Provides
    @IntoSet
    static OutboxDestinationHandler delayedJobHandler(DelayedJobOutboxDestinationHandler handler) {
        return handler;
    }
}
