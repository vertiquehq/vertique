// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.inboxoutbox.query.DefaultOutboxCallerScopesMapper;
import dev.vertique.inboxoutbox.query.OutboxCallerScopes;
import dev.vertique.inboxoutbox.query.OutboxCallerScopesMapper;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies with Dagger-compiled components that an application replaces the default
 * {@link OutboxCallerScopesMapper} with a plain binding next to {@link TransactionalMessagingModule}, and gets
 * the default without one.
 */
class TransactionalMessagingModuleCallerScopesBindingTest {

    static final OutboxCallerScopesMapper CUSTOM = context -> OutboxCallerScopes.ofUser(Set.of("custom"));

    /** The application's own mapper. */
    @Module
    abstract static class ApplicationMapperModule {

        @Provides
        static OutboxCallerScopesMapper outboxCallerScopesMapper() {
            return CUSTOM;
        }
    }

    @Singleton
    @Component(modules = {TransactionalMessagingModule.class, ApplicationMapperModule.class})
    interface OverridingComponent {

        Optional<OutboxCallerScopesMapper> mapper();
    }

    @Singleton
    @Component(modules = TransactionalMessagingModule.class)
    interface PlainComponent {

        Optional<OutboxCallerScopesMapper> mapper();
    }

    @Test
    @DisplayName("an application binding compiles next to the core module and wins")
    void applicationBindingWins() {
        Optional<OutboxCallerScopesMapper> bound =
                DaggerTransactionalMessagingModuleCallerScopesBindingTest_OverridingComponent.create()
                        .mapper();

        assertSame(CUSTOM, OutboxCallerScopesMapper.orDefault(bound));
    }

    @Test
    @DisplayName("without an application binding the default is used")
    void defaultWithoutBinding() {
        Optional<OutboxCallerScopesMapper> bound =
                DaggerTransactionalMessagingModuleCallerScopesBindingTest_PlainComponent.create()
                        .mapper();

        assertTrue(bound.isEmpty());
        assertTrue(OutboxCallerScopesMapper.orDefault(bound) instanceof DefaultOutboxCallerScopesMapper);
    }
}
