// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.di;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.examples.workflow.order.TimerWorkflowDefinition;
import dev.vertique.workflow.registry.WorkflowContributor;
import jakarta.inject.Singleton;

/**
 * Dagger module that registers the cycle-2 timer workflow definitions.
 *
 * <p>Contributes two {@link WorkflowContributor} entries into the multibinding so that
 * {@link dev.vertique.workflow.registry.WorkflowRegistry} picks them up at startup alongside the
 * order-fulfillment saga definition:
 * <ul>
 *   <li>{@link TimerWorkflowDefinition.StandaloneTimer} — {@code "timer-standalone"}</li>
 *   <li>{@link TimerWorkflowDefinition.SignalWithTimeout} — {@code "timer-signal-timeout"}</li>
 * </ul>
 */
@Module
public abstract class TimerDefinitionModule {

    /**
     * Contributes the standalone-timer workflow definition as a {@link WorkflowContributor}.
     *
     * @param definition the singleton standalone-timer definition
     * @return a contributor that registers the definition into the workflow registry
     */
    @Provides
    @Singleton
    @IntoSet
    static WorkflowContributor standaloneTimerContributor(TimerWorkflowDefinition.StandaloneTimer definition) {
        return registry -> registry.register(definition);
    }

    /**
     * Contributes the signal-with-timeout workflow definition as a {@link WorkflowContributor}.
     *
     * @param definition the singleton signal-with-timeout definition
     * @return a contributor that registers the definition into the workflow registry
     */
    @Provides
    @Singleton
    @IntoSet
    static WorkflowContributor signalWithTimeoutContributor(TimerWorkflowDefinition.SignalWithTimeout definition) {
        return registry -> registry.register(definition);
    }
}
