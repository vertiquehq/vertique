// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events.di;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.workflow.events.compose.WorkflowEventsComposeValidator;
import dev.vertique.workflow.events.recorder.WorkflowEventSideEffectRecorder;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;

/**
 * Dagger module that wires the workflow-events layer into the application graph.
 *
 * <p>Provides two bindings:
 * <ol>
 *   <li>{@link WorkflowEventSideEffectRecorder} contributed into the {@code @WorkflowRecorders}
 *       {@code Set<WorkflowSideEffectRecorder<SqlClient>>} multibinding so the workflow engine
 *       can route {@link dev.vertique.workflow.sideeffect.IntentKind#WORKFLOW_EVENT} intents to
 *       it.</li>
 *   <li>{@link WorkflowEventsComposeValidator} contributed into the {@code Set<ComposeValidator>}
 *       multibinding so the framework materializes it in the
 *       {@link dev.vertique.core.lifecycle.LifecyclePhase#VALIDATE VALIDATE} lifecycle phase,
 *       forcing its construction-time validation checks with no app code referencing it.</li>
 * </ol>
 *
 * <p>The recorder constructor pulls in
 * {@link WorkflowEventsComposeValidator} automatically via
 * Dagger constructor injection; no explicit {@code @Provides} is needed for the validator — Dagger
 * constructs it as a {@code @Singleton} class. The validator's startup checks therefore run
 * automatically whenever the engine resolves the {@code @WorkflowRecorders} multibinding.
 *
 * <p>This module must be included alongside:
 * <ul>
 *   <li>{@link dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule} — provides
 *       the workflow engine that consumes the {@code @WorkflowRecorders} multibinding.</li>
 *   <li>A module that provides a {@code @Singleton WorkflowEventOutboxBinding} for the desired
 *       destination type.</li>
 *   <li>A module that registers a matching {@link dev.vertique.inboxoutbox.OutboxDestinationHandler}
 *       for that destination type (e.g., {@code vertique-inbox-outbox-kafka} for
 *       {@link dev.vertique.inboxoutbox.DestinationType#KAFKA}).</li>
 * </ul>
 */
@Module
public abstract class WorkflowEventsModule {

    /**
     * Contributes the {@link WorkflowEventSideEffectRecorder} into the qualified
     * {@code @WorkflowRecorders} set multibinding.
     *
     * @param impl the workflow event recorder implementation
     * @return the recorder cast to the interface type
     */
    @Provides
    @Singleton
    @IntoSet
    @WorkflowRecorders
    static WorkflowSideEffectRecorder<SqlClient> eventRecorder(WorkflowEventSideEffectRecorder impl) {
        return impl;
    }

    /**
     * Contributes the {@link WorkflowEventsComposeValidator} into the
     * {@code Set<ComposeValidator>} multibinding so the framework materializes it in the
     * {@link dev.vertique.core.lifecycle.LifecyclePhase#VALIDATE VALIDATE} lifecycle phase,
     * forcing its construction-time validation check with no app code referencing it.
     *
     * @param impl the compose validator, constructed via its {@code @Inject} constructor
     * @return the validator as a {@link ComposeValidator}
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator eventsComposeValidator(WorkflowEventsComposeValidator impl) {
        return impl;
    }
}
