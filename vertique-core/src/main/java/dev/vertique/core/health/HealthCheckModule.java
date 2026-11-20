// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import java.util.Set;

/**
 * Dagger module that declares the {@link HealthCheck} multibinding sets for
 * {@link Liveness @Liveness} and {@link Readiness @Readiness} qualifiers, and binds the
 * {@link HealthCheckAggregator} that evaluates them.
 *
 * <p>Extracted into {@code core} so that non-management modules (e.g., {@code db-postgresql},
 * {@code services}) can contribute health checks without depending on the {@code management}
 * module.
 *
 * <p>Include this module in any Dagger component or module that contributes or consumes
 * health checks. {@code ManagementModule} includes it automatically.
 */
@Module
public abstract class HealthCheckModule {

    /**
     * Declares the empty-by-default multibinding set of liveness health checks.
     * Individual modules contribute entries via {@code @Provides @IntoSet @Liveness HealthCheck}.
     *
     * @return the set of liveness health checks (may be empty)
     */
    @Multibinds
    @Liveness
    abstract Set<HealthCheck> livenessChecks();

    /**
     * Declares the empty-by-default multibinding set of readiness health checks.
     * Individual modules contribute entries via {@code @Provides @IntoSet @Readiness HealthCheck}.
     *
     * @return the set of readiness health checks (may be empty)
     */
    @Multibinds
    @Readiness
    abstract Set<HealthCheck> readinessChecks();

    /**
     * Provides the aggregator that evaluates either set in process, with the
     * {@linkplain HealthCheckAggregator#DEFAULT_CHECK_TIMEOUT_SECONDS default} per-check timeout.
     * Inject it together with the qualified set to evaluate:
     * <pre>{@code
     * @Inject
     * ReadinessGate(HealthCheckAggregator aggregator, @Readiness Set<HealthCheck> readinessChecks) { ... }
     *
     * Future<HealthReport> ready = aggregator.evaluate(readinessChecks);
     * }</pre>
     *
     * <p>The binding is unscoped and the aggregator is stateless, so a component of any scope may
     * include this module. A consumer that applies its own timeout constructs its own
     * {@link HealthCheckAggregator#HealthCheckAggregator(long)}.
     *
     * @return an aggregator with the default per-check timeout
     */
    @Provides
    static HealthCheckAggregator healthCheckAggregator() {
        return new HealthCheckAggregator();
    }
}
