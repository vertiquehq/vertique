// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.services;

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.management.ManagementVerticle;
import dev.vertique.services.ServiceClientFactory;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.starter.services.ServicesApplicationModule;
import jakarta.inject.Singleton;

/**
 * Real {@code @VertiqueApp} application component naming only {@link ServicesApplicationModule}.
 *
 * <p>This fixture is the load-bearing proof of the services starter's composition contract: the
 * component names no dispatch or management module directly, yet Dagger must resolve the complete
 * service execution graph from the aggregate alone. The fixture also verifies the starter's bounded
 * dependency closure.
 *
 * <p>{@link #serviceClientFactory()} is the provision method the plan's services component contract
 * mandates: application code obtains typed event-bus clients through it. The remaining methods
 * request one representative binding per composed concern so a missing member of the aggregate
 * fails at annotation-processing time rather than at first dispatch: {@link ServiceContractRegistry}
 * for the dispatch runtime and {@link ManagementVerticle} for the management surface. The paired
 * {@code SERVICES}-phase deployment lifecycle steps are reached through the inherited
 * {@link VertiqueApplicationComponent#startupSteps()} and
 * {@link VertiqueApplicationComponent#shutdownSteps()} sets. Requesting these bindings builds the
 * objects only — no verticle is deployed and no port is bound.
 */
@VertiqueApp
@Singleton
@Component(modules = {ServicesApplicationModule.class})
public interface ServicesStarterConsumer extends VertiqueApplicationComponent {

    /**
     * Returns the factory creating typed event-bus clients for declared service contracts.
     *
     * <p>Mandated by the services component contract, and the representative binding for
     * {@code dev.vertique.services.DispatchModule}: constructing it resolves the request sender, the
     * supervisor, the contract registry, and the dispatch envelope builder.
     *
     * @return the service client factory; never {@code null}
     */
    ServiceClientFactory serviceClientFactory();

    /**
     * Returns the registry of service contracts discovered from the component's service set.
     *
     * <p>Representative binding for the dispatch runtime's typed {@code services} config boundary:
     * the registry is built from the multibound service implementations, the contract contributors,
     * and the parsed per-service configuration index.
     *
     * @return the service contract registry; never {@code null}
     */
    ServiceContractRegistry serviceContractRegistry();

    /**
     * Returns the management verticle exposing health and management endpoints.
     *
     * <p>Representative binding for {@code dev.vertique.management.ManagementModule}: constructing
     * it resolves the parsed {@code ManagementConfig}, the health-check sets — including the
     * readiness check the dispatch runtime contributes — and the endpoint contributor set.
     *
     * @return the management verticle; never {@code null}
     */
    ManagementVerticle managementVerticle();
}
