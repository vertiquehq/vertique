// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module that contributes the framework's built-in non-verticle lifecycle steps and declares
 * the {@link ComposeValidator} multibinding.
 *
 * <p>An application {@code @Component} includes this module to get the framework's
 * {@link LifecyclePhase#CONFIGURE CONFIGURE} and {@link LifecyclePhase#VALIDATE VALIDATE} steps wired
 * automatically:
 * <ul>
 *   <li>{@link JacksonConfigureStep} ({@code CONFIGURE}) — runs
 *       {@link dev.vertique.core.json.JacksonConfigurer#configure()}.</li>
 *   <li>{@link ComposeValidationStep} ({@code VALIDATE}) — forces construction of every
 *       {@link ComposeValidator} for fail-fast graph-composition validation.</li>
 * </ul>
 *
 * <p>Both steps are contributed {@code @IntoSet ApplicationStartupStep}. Dagger merges multibinding
 * contributions across modules, so these entries join the {@code Set<ApplicationStartupStep>}
 * multibinding declared (empty-by-default) in {@code DeployerModule} — this module therefore takes
 * <em>no</em> dependency on {@code vertique-deploy}. The runner ({@code vertique-application})
 * consumes the merged set and drives the steps in lifecycle-phase order.
 *
 * <p>The {@code @Multibinds Set<ComposeValidator>} declaration makes the compose-validator set
 * empty-by-default, so a {@code @Component} that includes this module compiles even when no module
 * contributes a validator. Modules that own a compose validator contribute it via
 * {@code @Provides @IntoSet ComposeValidator}.
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
     * Contributes the {@link JacksonConfigureStep} into the {@code Set<ApplicationStartupStep>}
     * multibinding.
     *
     * @param step the Jackson configure step
     * @return the step as an {@link ApplicationStartupStep}
     */
    @Provides
    @Singleton
    @IntoSet
    static ApplicationStartupStep jacksonConfigureStep(JacksonConfigureStep step) {
        return step;
    }

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
}
