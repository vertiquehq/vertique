// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.di;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.services.compose.WorkflowOutboxComposeValidator;
import dev.vertique.workflow.services.recorder.OutboxSideEffectRecorder;
import dev.vertique.workflow.services.signal.WorkflowSignalContributor;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module that wires the workflow-services layer into the application graph.
 *
 * <p>Provides three bindings:
 * <ol>
 *   <li>{@link OutboxSideEffectRecorder} contributed into the {@code @WorkflowRecorders}
 *       {@code Set<WorkflowSideEffectRecorder<SqlClient>>} multibinding so the workflow engine
 *       can route {@code SERVICE} intents to it.</li>
 *   <li>{@link WorkflowSignalContributor} contributed into the
 *       {@code Set<ServiceContractContributor>} multibinding so the services registry deploys the
 *       synthetic {@code workflow.signals.post} endpoint.</li>
 *   <li>{@link WorkflowOutboxComposeValidator} as a normal {@code @Singleton}. The validator is
 *       enforced through the recorder constructor: {@link OutboxSideEffectRecorder} takes the
 *       validator as a required dependency, so Dagger must construct the validator before the
 *       recorder. The recorder participates in the {@code @WorkflowRecorders} multibinding
 *       consumed by {@code RecorderRouter} → {@code PgWorkflowEngine}, and Dagger constructs that
 *       chain when the engine is requested. Applications still MAY expose
 *       {@code WorkflowOutboxComposeValidator workflowComposeValidator()} on their
 *       {@code AppComponent} for an even earlier fail-fast (e.g., before deploying any verticles),
 *       but it is no longer required for the validation to run.</li>
 * </ol>
 */
@Module
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
     * Provides the {@link WorkflowOutboxComposeValidator} as a {@code @Singleton}.
     *
     * <p>The validator is enforced through {@link OutboxSideEffectRecorder}'s constructor: the
     * recorder takes this validator as a required dependency, so when Dagger constructs the
     * recorder (which happens whenever the engine resolves the {@code @WorkflowRecorders}
     * multibinding) the validator's checks run first. Apps need not do anything beyond including
     * this module to get startup validation.
     *
     * <p>Optionally, applications MAY expose
     * {@code WorkflowOutboxComposeValidator workflowComposeValidator()} on their
     * {@code AppComponent} and call it during {@code MainVerticle.start()} for an even earlier
     * fail-fast (before deploying any verticles). This is now belt-and-suspenders, not required.
     *
     * @param handlers       the full set of registered {@link OutboxDestinationHandler} instances
     * @param registry       the workflow registry, used to walk every registered plan's service
     *                       dispatch and compensation nodes
     * @param targetResolver the service target resolver, used to validate each step's target id
     *                       and shape against the services registry
     * @return the validator; throws {@link IllegalStateException} if no {@code SERVICE} handler is
     *     registered or if any registered plan references an unknown or wrong-shape service target
     */
    @Provides
    @Singleton
    static WorkflowOutboxComposeValidator validator(
            Set<OutboxDestinationHandler> handlers, WorkflowRegistry registry, ServiceTargetResolver targetResolver) {
        return new WorkflowOutboxComposeValidator(handlers, registry, targetResolver);
    }

    /**
     * Contributes the {@link WorkflowOutboxComposeValidator} into the
     * {@code Set<ComposeValidator>} multibinding so the framework materializes it in the
     * {@link dev.vertique.core.lifecycle.LifecyclePhase#VALIDATE VALIDATE} lifecycle phase (forcing
     * its construction-time validation) with no app code referencing it.
     *
     * @param impl the singleton validator provided above
     * @return the validator as a {@link ComposeValidator}
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator outboxComposeValidator(WorkflowOutboxComposeValidator impl) {
        return impl;
    }
}
