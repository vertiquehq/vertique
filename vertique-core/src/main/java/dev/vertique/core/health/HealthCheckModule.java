// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import dagger.Module;
import dagger.multibindings.Multibinds;
import java.util.Set;

/**
 * Dagger module that declares the {@link HealthCheck} multibinding sets for
 * {@link Liveness @Liveness} and {@link Readiness @Readiness} qualifiers.
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
}
