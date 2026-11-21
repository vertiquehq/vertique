// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.Readiness;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module that contributes the framework's built-in non-verticle lifecycle steps and declares
 * the {@link ComposeValidator} multibinding.
 *
 * <p>An application {@code @Component} includes this module to get the framework's
 * {@link LifecyclePhase#VALIDATE VALIDATE} step wired automatically:
 * {@link ComposeValidationStep} forces construction of every {@link ComposeValidator} for fail-fast
 * graph-composition validation.
 *
 * <p>The module also contributes {@link StartupReadinessCheck}, as a {@code @Readiness} check and as the last
 * {@link LifecyclePhase#AFTER_START AFTER_START} step, so the readiness probe of an application with
 * {@code ManagementModule} stays {@code DOWN} until the runner has finished every phase.
 *
 * <p>The step is contributed {@code @IntoSet ApplicationStartupStep}. Dagger merges multibinding
 * contributions across modules, so this entry joins the {@code Set<ApplicationStartupStep>}
 * multibinding declared (empty-by-default) in {@code DeployerModule} — this module therefore takes
 * <em>no</em> dependency on {@code vertique-deploy}. The runner ({@code vertique-application})
 * consumes the merged set and drives the steps in lifecycle-phase order.
 *
 * <p>The {@code @Multibinds Set<ComposeValidator>} declaration makes the compose-validator set
 * empty-by-default, so a {@code @Component} that includes this module compiles even when no module
 * contributes a validator. Modules that own a compose validator contribute it via
 * {@code @Provides @IntoSet ComposeValidator}.
 *
 * <p>This module is <em>self-contained</em>: it includes no other module, and its only step depends
 * on nothing beyond the multibinding it declares here.
 */
@Module
public abstract class CoreLifecycleStepsModule {

    /**
     * Declares the empty-by-default {@link ComposeValidator} multibinding set so a component
     * including this module compiles with no contributed validators.
     *
     * @return the set of compose validators (may be empty)
     */
    @Multibinds
    abstract Set<ComposeValidator> composeValidators();

    /**
     * Contributes the {@link ComposeValidationStep} into the {@code Set<ApplicationStartupStep>}
     * multibinding.
     *
     * @param step the compose-validation step
     * @return the step as an {@link ApplicationStartupStep}
     */
    @Provides
    @Singleton
    @IntoSet
    static ApplicationStartupStep composeValidationStep(ComposeValidationStep step) {
        return step;
    }

    /**
     * Contributes the startup readiness gate as a startup step: it marks the application started in the last
     * {@link LifecyclePhase#AFTER_START} step.
     *
     * @param check the singleton gate
     * @return the gate as an {@link ApplicationStartupStep}
     */
    @Provides
    @Singleton
    @IntoSet
    static ApplicationStartupStep startupReadinessStep(StartupReadinessCheck check) {
        return check;
    }

    /**
     * Contributes the same singleton gate as a readiness check, so {@code /health/ready} is {@code DOWN} until the
     * runner has finished every phase.
     *
     * @param check the singleton gate
     * @return the gate as a {@link HealthCheck}
     */
    @Provides
    @Singleton
    @IntoSet
    @Readiness
    static HealthCheck startupReadinessCheck(StartupReadinessCheck check) {
        return check;
    }
}
