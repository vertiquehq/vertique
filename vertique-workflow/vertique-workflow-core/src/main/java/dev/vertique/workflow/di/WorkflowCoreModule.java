// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.di;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.workflow.actor.DefaultWorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowActorMapper;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.RaceSafetyTargetContributor;
import dev.vertique.workflow.plan.RaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.WorkflowRegistry;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module for the workflow-core layer.
 *
 * <p>Provides {@link WorkflowRegistry}, populated by all {@link WorkflowContributor}
 * contributions at construction time.
 *
 * <p>This module is SQL-free and carries no runtime dependencies on database or service modules.
 * Include it in any {@code @Component} that needs to work with workflow definitions.
 *
 * <p>Declares the {@code Set<WorkflowContributor>} multibinding so that application modules can
 * contribute definitions via {@code @Provides @IntoSet WorkflowContributor}. An empty set is
 * valid (no-op registry).
 *
 * <p>Note: there is intentionally NO global {@code WorkflowCallbackRegistry} binding. Per-
 * definition callbacks are reachable only via {@link
 * dev.vertique.workflow.registry.RuntimeWorkflow#callbacks()} on a resolved instance. Callback
 * ids are scoped to {@code (stepId, role)} and would silently collide across definitions if
 * aggregated globally — see {@code DefaultWorkflowRegistry} class javadoc.
 */
@Module
public abstract class WorkflowCoreModule {

    /**
     * Provides the {@link WorkflowRegistry} populated with all contributed definitions.
     *
     * <p>Each {@link WorkflowContributor} is invoked once in an unspecified order. Registration
     * validates each plan immediately; invalid plans throw
     * {@link dev.vertique.workflow.exception.WorkflowDefinitionException}.
     *
     * @param contributors the set of all contributed workflow definitions
     * @return a fully-populated, immutable-after-build registry
     */
    @Provides
    @Singleton
    static WorkflowRegistry registry(Set<WorkflowContributor> contributors, WorkflowPlanValidator forkJoinValidator) {
        DefaultWorkflowRegistry reg = new DefaultWorkflowRegistry(forkJoinValidator);
        contributors.forEach(c -> c.contribute(reg));
        return reg;
    }

    /**
     * Declares the empty {@code Set<WorkflowContributor>} multibinding.
     *
     * <p>Application modules contribute definitions via
     * {@code @Provides @IntoSet WorkflowContributor}. Without this declaration, Dagger cannot
     * provide the set when no contributors are bound.
     *
     * @return the (initially empty) set of contributors
     */
    @Multibinds
    abstract Set<WorkflowContributor> contributors();

    /**
     * Declares the empty {@code Set<RaceSafetyTargetContributor>} multibinding so applications can
     * contribute service-target race-safety declarations via
     * {@code @Provides @IntoSet RaceSafetyTargetContributor} without requiring at least one
     * contributor to be bound.
     *
     * @return the (initially empty) set of race-safety target contributors
     */
    @Multibinds
    abstract Set<RaceSafetyTargetContributor> raceSafetyTargetContributors();

    /**
     * Binds the {@link RaceSafetyTargetRegistry} interface to its default
     * contributor-aggregating implementation.
     *
     * @param impl the default implementation populated from the contributor multibinding
     * @return the bound registry
     */
    @Binds
    @Singleton
    abstract RaceSafetyTargetRegistry raceSafetyTargetRegistry(DefaultRaceSafetyTargetRegistry impl);

    /**
     * Binds the {@link WorkflowActorMapper} interface to the {@link DefaultWorkflowActorMapper}
     * singleton.
     *
     * <p>The default mapper translates {@code USER}/{@code SERVICE}/{@code SYSTEM} identities
     * to the corresponding {@link dev.vertique.workflow.actor.WorkflowActor} permits. Anonymous
     * identities are rejected loudly — workflow operations require a named actor.
     *
     * @param impl the default implementation
     * @return the mapper bound as the SPI interface
     */
    @Binds
    @Singleton
    abstract WorkflowActorMapper workflowActorMapper(DefaultWorkflowActorMapper impl);
}
