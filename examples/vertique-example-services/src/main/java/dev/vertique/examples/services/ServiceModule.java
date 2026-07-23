// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services;

import dagger.Module;
import dagger.Provides;
import dev.vertique.examples.services.service.UserService;
import dev.vertique.services.ServiceClientFactory;
import jakarta.inject.Singleton;

/**
 * Dagger module providing the typed {@link UserService} client proxy.
 *
 * <p>Service implementation registration is handled by the codegen-generated
 * {@code GeneratedServicesModule} (from {@code vertique-codegen-services}), which discovers
 * {@link dev.vertique.examples.services.service.UserServiceHandler} (unconditional default) and
 * {@link dev.vertique.examples.services.service.UserServiceSandbox} (conditional on
 * {@code sandboxEnabled=true} via {@link dev.vertique.codegen.ConditionalOnProperty}).
 *
 * <p>{@link dev.vertique.examples.services.service.UserServiceImpl} is excluded from codegen
 * via {@link dev.vertique.codegen.NoAutoWire} — it remains in the source tree as a reference
 * for the direct-implementation pattern.
 */
@Module
public class ServiceModule {

    /**
     * Provides the typed {@link UserService} event bus proxy client.
     *
     * <p>The proxy routes calls over the event bus to the deployed service verticle.
     * Policy annotations ({@link dev.vertique.services.policy.Timeout},
     * {@link dev.vertique.services.policy.CircuitBreaker},
     * {@link dev.vertique.services.policy.Retry}) declared on the contract are
     * enforced server-side.
     *
     * @param factory the service client factory
     * @return a singleton proxy instance for {@link UserService}
     */
    @Provides
    @Singleton
    static UserService userServiceClient(ServiceClientFactory factory) {
        return factory.create(UserService.class);
    }
}
