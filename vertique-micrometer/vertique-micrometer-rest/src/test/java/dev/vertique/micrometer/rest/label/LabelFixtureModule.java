// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest.label;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.rest.core.events.RestRequestCompletedListener;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;

/**
 * The collaborators both label compositions share: a {@link SimpleMeterRegistry} as the
 * {@link MeterRegistry} the metrics adapter records into, the real config parser the JAX-RS module
 * needs, the unsecured {@link SecurityPolicyValidator} stand-in, the {@link AuthEnforcementCapability}
 * marker that lets a role-restricted operation register, the {@link StubScheme} authentication,
 * the {@link RoleCheck} contributor, and the {@link CompletionLog} listener beside the adapter's own.
 */
@Module
final class LabelFixtureModule {

    private LabelFixtureModule() {}

    @Provides
    @Singleton
    static MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Provides
    @Singleton
    static CompletionLog completionLog() {
        return new CompletionLog();
    }

    @Provides
    @IntoSet
    static RestRequestCompletedListener completionLogListener(CompletionLog log) {
        return log;
    }

    /**
     * The unsecured {@link SecurityPolicyValidator} stand-in. {@code null} is a legal value: the
     * consuming constructor parameter is {@code @Nullable}.
     *
     * @return {@code null}
     */
    @Provides
    @Nullable
    static SecurityPolicyValidator securityPolicyValidator() {
        return null;
    }

    @Provides
    static AuthEnforcementCapability authEnforcementCapability() {
        return AuthEnforcementCapability.INSTANCE;
    }

    @Provides
    static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    @Provides
    @IntoSet
    static SecuritySchemeHandler stubScheme() {
        return new StubScheme();
    }

    @Provides
    @IntoSet
    static OperationHandlerContributor roleCheck() {
        return new RoleCheck();
    }
}
