// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.deploy;

import dagger.Module;
import dagger.multibindings.Multibinds;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import java.util.Set;

/**
 * Dagger module that declares the {@link VerticleDeployment} multibinding set, plus the
 * {@link ApplicationStartupStep}/{@link ApplicationShutdownStep} step multibinding sets.
 *
 * <p>Include this module in any Dagger component that uses {@link VerticleDeploymentManager}.
 * Applications contribute deployment entries via {@code @Provides @IntoSet VerticleDeployment}
 * methods in their own modules, and non-verticle lifecycle work via {@code @Provides @IntoSet
 * ApplicationStartupStep}/{@code ApplicationShutdownStep} methods.
 *
 * <p>Both {@link VerticleDeployer} and {@link VerticleDeploymentManager} are provided automatically
 * by Dagger via their {@code @Inject} constructors — no explicit {@code @Provides} methods are
 * needed here.
 */
@Module
public abstract class DeployerModule {

    /**
     * Declares the empty-by-default multibinding set of {@link VerticleDeployment} entries.
     *
     * @return the set of verticle deployments (may be empty)
     */
    @Multibinds
    abstract Set<VerticleDeployment> verticleDeployments();

    /**
     * Declares the empty-by-default multibinding set of {@link ApplicationStartupStep} entries so
     * modules can {@code @IntoSet}-contribute non-verticle startup steps.
     *
     * @return the set of application startup steps (may be empty)
     */
    @Multibinds
    abstract Set<ApplicationStartupStep> applicationStartupSteps();

    /**
     * Declares the empty-by-default multibinding set of {@link ApplicationShutdownStep} entries so
     * modules can {@code @IntoSet}-contribute non-verticle shutdown steps.
     *
     * @return the set of application shutdown steps (may be empty)
     */
    @Multibinds
    abstract Set<ApplicationShutdownStep> applicationShutdownSteps();
}
