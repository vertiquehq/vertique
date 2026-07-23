// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen;

import dagger.Module;
import dagger.Provides;
import dev.vertique.examples.services.codegen.service.BillingService;
import dev.vertique.examples.services.codegen.service.ShippingService;
import dev.vertique.services.ServiceClientFactory;
import jakarta.inject.Singleton;

/**
 * Dagger module providing typed event bus client proxies for service contracts.
 *
 * <p>This module provides only the <em>client proxy</em> bindings injected into REST resources.
 * The service implementations are wired via the CG-005-generated
 * {@code GeneratedServicesModule}, which contributes
 * {@link dev.vertique.services.ServiceContractContributor} instances to the dispatch registry.
 *
 * <p>Using {@link ServiceClientFactory#create(Class)} creates a proxy that routes calls over
 * the Vert.x event bus to the deployed service verticle. Resilience policies declared on the
 * contract interface ({@link dev.vertique.core.resilience.Timeout},
 * {@link dev.vertique.core.resilience.CircuitBreaker}) are enforced server-side during dispatch.
 */
@Module
public class ServiceModule {

    /**
     * Provides the typed {@link BillingService} event bus proxy client.
     *
     * <p>Routes calls to {@code BillingServiceImpl} registered via the
     * generated contributor module. The {@link dev.vertique.core.resilience.Timeout} declared
     * on {@link BillingService#charge} is enforced server-side.
     *
     * @param factory the service client factory
     * @return a singleton proxy instance for {@link BillingService}
     */
    @Provides
    @Singleton
    static BillingService billingServiceClient(ServiceClientFactory factory) {
        return factory.create(BillingService.class);
    }

    /**
     * Provides the typed {@link ShippingService} event bus proxy client.
     *
     * <p>Routes calls to {@code ShippingServiceHandler} registered via the
     * generated contributor module. The class-level {@link dev.vertique.core.resilience.CircuitBreaker}
     * declared on {@link ShippingService} is enforced server-side for all operations.
     *
     * @param factory the service client factory
     * @return a singleton proxy instance for {@link ShippingService}
     */
    @Provides
    @Singleton
    static ShippingService shippingServiceClient(ServiceClientFactory factory) {
        return factory.create(ShippingService.class);
    }
}
