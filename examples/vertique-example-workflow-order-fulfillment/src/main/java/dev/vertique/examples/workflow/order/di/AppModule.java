// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.di;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.management.ManagementVerticle;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Clock;

/**
 * Application-specific Dagger module for the order-fulfillment example.
 *
 * <p>Provides:
 * <ul>
 *   <li>The management verticle deployment descriptor.</li>
 *   <li>{@link Clock} — used by {@link dev.vertique.workflow.postgresql.engine.PgWorkflowEngine}
 *       for timestamping history entries.</li>
 * </ul>
 *
 * <p>No HTTP or REST modules are included — this example focuses on the durable saga pattern
 * rather than REST API exposure.
 */
@Module
public abstract class AppModule {

    /**
     * Contributes the management verticle deployment into the {@code Set<VerticleDeployment>}
     * multibinding.
     *
     * @param provider lazy provider for {@link ManagementVerticle} instances
     * @return the deployment descriptor in the {@link LifecyclePhase#INFRA} phase
     */
    @Provides
    @IntoSet
    static VerticleDeployment managementVerticle(Provider<ManagementVerticle> provider) {
        return VerticleDeployment.of("management", provider::get, LifecyclePhase.INFRA);
    }

    /**
     * Provides the system UTC clock used by the workflow engine for timestamping.
     *
     * @return {@link Clock#systemUTC()}
     */
    @Provides
    @Singleton
    static Clock clock() {
        return Clock.systemUTC();
    }
}
