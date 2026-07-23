// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks.di;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.tasks.DefaultTaskService;
import dev.vertique.workflow.tasks.TaskService;
import dev.vertique.workflow.tasks.TransactionalDefaultTaskService;
import dev.vertique.workflow.tasks.TransactionalTaskService;
import dev.vertique.workflow.tasks.compose.WorkflowTasksComposeValidator;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;

/**
 * Dagger module that wires the workflow-tasks layer into the application graph.
 *
 * <p>Provides two bindings:
 * <ol>
 *   <li>{@link TransactionalTaskService}{@code <SqlClient>} — the tx-aware task service backed by
 *       {@link dev.vertique.workflow.tasks.TransactionalDefaultTaskService}.</li>
 *   <li>{@link TaskService} — the developer-facing task API backed by
 *       {@link DefaultTaskService}, which opens {@code pool.withTransaction(...)} per call and
 *       delegates to the tx-aware service.</li>
 * </ol>
 *
 * <p>This module must be included alongside:
 * <ul>
 *   <li>{@code WorkflowPostgresqlModule} — provides {@link dev.vertique.workflow.tasks.TaskStore}
 *       {@code <SqlClient>} and
 *       {@link dev.vertique.workflow.ops.TransactionalTaskCallbacks}{@code <SqlClient>}.
 *       Without it, Dagger will report missing bindings for those SPIs.</li>
 *   <li>A module that provides {@link io.vertx.sqlclient.Pool} — required by
 *       {@link DefaultTaskService} to open transactions.</li>
 * </ul>
 *
 * <p>Includes {@link WorkflowCoreModule} automatically so applications do not need to list it
 * separately when they include this module.
 */
@Module(includes = WorkflowCoreModule.class)
public abstract class WorkflowTasksModule {

    /**
     * Binds {@link TransactionalDefaultTaskService} as the {@link TransactionalTaskService}
     * {@code <SqlClient>} singleton.
     *
     * @param impl the concrete implementation
     * @return the implementation cast to the interface type
     */
    @Binds
    @Singleton
    abstract TransactionalTaskService<SqlClient> bindTxTaskService(TransactionalDefaultTaskService impl);

    /**
     * Binds {@link DefaultTaskService} as the {@link TaskService} singleton.
     *
     * @param impl the concrete implementation
     * @return the implementation cast to the interface type
     */
    @Binds
    @Singleton
    abstract TaskService bindTaskService(DefaultTaskService impl);

    /**
     * Contributes the {@link WorkflowTasksComposeValidator} into the
     * {@code Set<ComposeValidator>} multibinding so the framework materializes it in the
     * {@link dev.vertique.core.lifecycle.LifecyclePhase#VALIDATE VALIDATE} lifecycle phase (forcing
     * its construction-time, constructible-as-validation check) with no app code referencing it.
     *
     * <p>Dagger constructs the validator through its {@code @Inject} constructor, which establishes
     * the required dependency edges ({@link TaskService}, {@link dev.vertique.workflow.tasks.TaskStore}).
     *
     * @param impl the compose validator, constructed via its {@code @Inject} constructor
     * @return the validator as a {@link ComposeValidator}
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator tasksComposeValidator(WorkflowTasksComposeValidator impl) {
        return impl;
    }
}
