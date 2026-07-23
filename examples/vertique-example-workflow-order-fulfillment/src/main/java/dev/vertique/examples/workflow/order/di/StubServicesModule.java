// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.di;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.examples.workflow.order.service.StubScenario;
import dev.vertique.examples.workflow.order.service.StubServicesContributor;
import dev.vertique.services.ServiceContractContributor;
import jakarta.inject.Singleton;

/**
 * Dagger module that wires the stub services and scenario toggle into the application graph.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link StubScenario} — a shared singleton toggle used by stub service implementations
 *       to switch between success and failure modes in integration tests.</li>
 *   <li>{@link StubServicesContributor} contributed as a {@link ServiceContractContributor} so
 *       the services framework deploys inventory, payment, and shipping service verticles.</li>
 * </ul>
 */
@Module
public abstract class StubServicesModule {

    /**
     * Provides the shared scenario toggle singleton.
     *
     * @return a new {@link StubScenario} in success mode (all services succeed by default)
     */
    @Provides
    @Singleton
    static StubScenario stubScenario() {
        return new StubScenario();
    }

    /**
     * Contributes the stub services contributor into the {@code Set<ServiceContractContributor>}
     * multibinding.
     *
     * @param contributor the contributor that registers inventory, payment, and shipping contracts
     * @return the contributor
     */
    @Provides
    @Singleton
    @IntoSet
    static ServiceContractContributor stubServicesContributor(StubServicesContributor contributor) {
        return contributor;
    }
}
