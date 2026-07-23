// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.services;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.logging.LoggingContextModule;

/**
 * Dagger module that registers the {@link ServiceOutboxDestinationHandler} as an
 * {@link OutboxDestinationHandler} contribution for {@link dev.vertique.inboxoutbox.DestinationType#SERVICE}
 * outbox entries.
 *
 * <p>The handler declares its own {@link dev.vertique.inboxoutbox.ClaimScope} via
 * {@link ServiceOutboxDestinationHandler#claimScope()}, so this module does not need to contribute
 * a separate target-id multibinding.
 *
 * <p>Include this module in your Dagger component alongside
 * {@link dev.vertique.inboxoutbox.TransactionalMessagingModule} and the PostgreSQL persistence
 * module to enable event bus service dispatch via the transactional outbox:
 *
 * <pre>{@code
 * @Component(modules = {
 *     TransactionalMessagingModule.class,
 *     TransactionalMessagingPostgresqlModule.class,
 *     TransactionalMessagingServiceModule.class,
 *     DispatchModule.class,
 *     ...
 * })
 * public interface AppComponent { ... }
 * }</pre>
 */
@Module(includes = {dev.vertique.context.ContextRuntimeModule.class, LoggingContextModule.class})
public abstract class TransactionalMessagingServiceModule {

    /**
     * Contributes the {@link ServiceOutboxDestinationHandler} to the set of registered
     * {@link OutboxDestinationHandler} implementations.
     *
     * @param handler the singleton handler instance provided by Dagger
     * @return the handler, contributed into the multibinding set
     */
    @Provides
    @IntoSet
    static OutboxDestinationHandler serviceHandler(ServiceOutboxDestinationHandler handler) {
        return handler;
    }
}
