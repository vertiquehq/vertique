// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.di;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;
import dev.vertique.workflow.services.compose.WorkflowOutboxComposeValidator;
import dev.vertique.workflow.services.recorder.OutboxSideEffectRecorder;
import dev.vertique.workflow.services.signal.WorkflowSignalContributor;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;

/**
 * Dagger module that wires the workflow-services layer into the application graph.
 *
 * <p>Includes {@link WorkflowSourceModule}, which provides the correlated-signal delivery bean, and
 * provides three bindings of its own:
 * <ol>
 *   <li>{@link OutboxSideEffectRecorder} contributed into the {@code @WorkflowRecorders}
 *       {@code Set<WorkflowSideEffectRecorder<SqlClient>>} multibinding so the workflow engine
 *       can route {@code SERVICE} intents to it.</li>
 *   <li>{@link WorkflowSignalContributor} contributed into the
 *       {@code Set<ServiceContractContributor>} multibinding so the services registry deploys the
 *       synthetic {@code workflow.signals.post} endpoint.</li>
 *   <li>{@link WorkflowOutboxComposeValidator} contributed into the
 *       {@code Set<WorkflowDefinitionValidator>} multibinding. The registry runs it once for every
 *       plan it builds, at startup and for definitions registered later, so a plan that dispatches
 *       to an unknown service target or without a {@code SERVICE} outbox handler is refused.</li>
 * </ol>
 */
@Module(includes = WorkflowSourceModule.class)
public abstract class WorkflowServicesModule {

    /**
     * Contributes the {@link OutboxSideEffectRecorder} into the qualified
     * {@code @WorkflowRecorders} set multibinding.
     *
     * @param impl the outbox recorder implementation
     * @return the recorder cast to the interface type
     */
    @Provides
    @Singleton
    @IntoSet
    @WorkflowRecorders
    static WorkflowSideEffectRecorder<SqlClient> outboxRecorder(OutboxSideEffectRecorder impl) {
        return impl;
    }

    /**
     * Contributes the {@link WorkflowSignalContributor} into the
     * {@code Set<ServiceContractContributor>} multibinding.
     *
     * @param impl the signal contributor implementation
     * @return the contributor cast to the interface type
     */
    @Provides
    @Singleton
    @IntoSet
    static ServiceContractContributor signalContributor(WorkflowSignalContributor impl) {
        return impl;
    }

    /**
     * Contributes the {@link WorkflowOutboxComposeValidator} into the
     * {@code Set<WorkflowDefinitionValidator>} multibinding so the registry checks the service
     * handler and every service target of each plan it builds.
     *
     * @param impl the validator, constructed via its {@code @Inject} constructor
     * @return the validator as a {@link WorkflowDefinitionValidator}
     */
    @Provides
    @Singleton
    @IntoSet
    static WorkflowDefinitionValidator outboxComposeValidator(WorkflowOutboxComposeValidator impl) {
        return impl;
    }
}
