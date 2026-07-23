// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.Multibinds;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module for the workflow migration SPI.
 *
 * <p>Declares the {@code Set<WorkflowMigrationHandler<?, ?>>} multibinding so application modules
 * can contribute handlers via {@code @Provides @IntoSet WorkflowMigrationHandler<?, ?>} without
 * requiring at least one handler to be bound.
 *
 * <p>Binds {@link WorkflowMigrationRegistry} to {@link DefaultWorkflowMigrationRegistry}. The
 * registry validates all contributed handlers against the
 * {@link dev.vertique.workflow.registry.WorkflowRegistry} at construction time.
 *
 * <p>Include this module in any {@code @Component} that requires migration support alongside
 * {@link dev.vertique.workflow.di.WorkflowCoreModule}.
 *
 * <p>PRD-WF-003 FR-WF-DEF-063.
 */
@Module
public abstract class WorkflowMigrationModule {

    /**
     * Declares the empty {@code Set<WorkflowMigrationHandler<?, ?>>} multibinding.
     *
     * <p>Application modules contribute handlers via
     * {@code @Provides @IntoSet WorkflowMigrationHandler<?, ?>}. Without this declaration,
     * Dagger cannot provide the set when no handlers are bound.
     *
     * @return the (initially empty) set of migration handlers
     */
    @Multibinds
    abstract Set<WorkflowMigrationHandler<?, ?>> migrationHandlers();

    /**
     * Binds the {@link WorkflowMigrationRegistry} interface to its default
     * contributor-aggregating implementation.
     *
     * @param impl the default implementation populated from the handler multibinding
     * @return the bound registry
     */
    @Binds
    @Singleton
    abstract WorkflowMigrationRegistry migrationRegistry(DefaultWorkflowMigrationRegistry impl);
}
