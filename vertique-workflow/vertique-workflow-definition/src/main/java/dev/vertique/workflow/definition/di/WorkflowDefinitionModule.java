// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.di;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.workflow.definition.callbacks.BranchResultReducerContributor;
import dev.vertique.workflow.definition.callbacks.BranchResultReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultBranchResultReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultFailMessageFactoryRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultNamedConditionRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultPayloadMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStartStateMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateMutatorRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultSubjectResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTaskAssignmentResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTimerResolverRegistry;
import dev.vertique.workflow.definition.callbacks.FailMessageFactoryContributor;
import dev.vertique.workflow.definition.callbacks.FailMessageFactoryRegistry;
import dev.vertique.workflow.definition.callbacks.NamedConditionContributor;
import dev.vertique.workflow.definition.callbacks.NamedConditionRegistry;
import dev.vertique.workflow.definition.callbacks.PayloadMapperContributor;
import dev.vertique.workflow.definition.callbacks.PayloadMapperRegistry;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.callbacks.StartStateMapperRegistry;
import dev.vertique.workflow.definition.callbacks.StateMutatorContributor;
import dev.vertique.workflow.definition.callbacks.StateMutatorRegistry;
import dev.vertique.workflow.definition.callbacks.StateReducerContributor;
import dev.vertique.workflow.definition.callbacks.StateReducerRegistry;
import dev.vertique.workflow.definition.callbacks.SubjectResolverContributor;
import dev.vertique.workflow.definition.callbacks.SubjectResolverRegistry;
import dev.vertique.workflow.definition.callbacks.TaskAssignmentResolverContributor;
import dev.vertique.workflow.definition.callbacks.TaskAssignmentResolverRegistry;
import dev.vertique.workflow.definition.callbacks.TimerResolverContributor;
import dev.vertique.workflow.definition.callbacks.TimerResolverRegistry;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import dev.vertique.workflow.definition.service.DefaultWorkflowDefinitionService;
import dev.vertique.workflow.definition.service.WorkflowDefinitionBootstrap;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.registry.WorkflowContributor;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module for the {@code vertique-workflow-definition} layer.
 *
 * <p>Source-agnostic declarative workflow definitions. Declares:
 * <ul>
 *   <li>11 {@code @Multibinds} methods — one empty-set default per named callback registry, and
 *       one for {@link WorkflowDefinitionSource} contributions.</li>
 *   <li>10 {@code @Binds @Singleton} methods — binding each {@code Default*Registry}
 *       implementation to its corresponding interface.</li>
 *   <li>1 {@code @Binds @Singleton} binding — {@link CelExpressionProfile} to
 *       {@link ExpressionProfile}.</li>
 *   <li>1 {@code @Binds @Singleton} binding — {@link DefaultWorkflowDefinitionService} to
 *       {@link WorkflowDefinitionService}.</li>
 *   <li>1 {@code @Provides @IntoSet @Singleton} method — exposes
 *       {@link WorkflowDefinitionBootstrap} as a {@link WorkflowContributor} into the
 *       core multibinding so it runs at registry construction time.</li>
 *   <li>{@link dev.vertique.workflow.definition.compiler.DecisionRouteCompiler},
 *       {@link dev.vertique.workflow.definition.compiler.WorkflowDefinitionCompiler} — registered
 *       as concrete {@code @Singleton} beans; they are injected directly without interface
 *       bindings (simpler, per the Slice F plan).</li>
 * </ul>
 *
 * <p>This module is SQL-free and depends only on {@code vertique-workflow-core},
 * {@code vertique-core}, and Jackson. It must remain optional per NFR-WF-DEF-001 and must not
 * leak its dependencies into {@code workflow-core} or {@code workflow-postgresql} per
 * NFR-WF-DEF-003 / NFR-WF-DEF-007.
 */
@Module
public abstract class WorkflowDefinitionModule {

    /** Constructor for the abstract Dagger module. Not for direct instantiation. */
    protected WorkflowDefinitionModule() {
        // Dagger instantiates the generated subclass.
    }

    // --- Multibinds (empty-set defaults, one per registry + sources) ---

    /**
     * Declares the empty-set default for {@link BranchResultReducerContributor} multibinding.
     *
     * @return empty set (Dagger-generated)
     */
    @Multibinds
    abstract Set<BranchResultReducerContributor> branchResultReducerContributors();

    /**
     * Declares the empty-set default for {@link WorkflowDefinitionSource} multibinding.
     *
     * <p>Applications that install this module with no source contributions still compile and
     * start cleanly (the bootstrap iterates an empty set and registers nothing).
     *
     * @return empty set (Dagger-generated)
     */
    @Multibinds
    abstract Set<WorkflowDefinitionSource> definitionSources();

    /**
     * Declares the empty-set default for {@link FailMessageFactoryContributor} multibinding.
     *
     * @return empty set (Dagger-generated)
     */
    @Multibinds
    abstract Set<FailMessageFactoryContributor> failMessageFactoryContributors();

    /**
     * Declares the empty-set default for {@link NamedConditionContributor} multibinding.
     *
     * @return empty set (Dagger-generated)
     */
    @Multibinds
    abstract Set<NamedConditionContributor> namedConditionContributors();

    /**
     * Declares the empty-set default for {@link PayloadMapperContributor} multibinding.
     *
     * @return empty set (Dagger-generated)
     */
    @Multibinds
    abstract Set<PayloadMapperContributor> payloadMapperContributors();

    /**
     * Declares the empty-set default for {@link StartStateMapperContributor} multibinding.
     *
     * @return empty set (Dagger-generated)
     */
    @Multibinds
    abstract Set<StartStateMapperContributor> startStateMapperContributors();

    /**
     * Declares the empty-set default for {@link StateMutatorContributor} multibinding.
     *
     * @return empty set (Dagger-generated)
     */
    @Multibinds
    abstract Set<StateMutatorContributor> stateMutatorContributors();

    /**
     * Declares the empty-set default for {@link StateReducerContributor} multibinding.
     *
     * @return empty set (Dagger-generated)
     */
    @Multibinds
    abstract Set<StateReducerContributor> stateReducerContributors();

    /**
     * Declares the empty-set default for {@link SubjectResolverContributor} multibinding.
     *
     * @return empty set (Dagger-generated)
     */
    @Multibinds
    abstract Set<SubjectResolverContributor> subjectResolverContributors();

    /**
     * Declares the empty-set default for {@link TaskAssignmentResolverContributor} multibinding.
     *
     * @return empty set (Dagger-generated)
     */
    @Multibinds
    abstract Set<TaskAssignmentResolverContributor> taskAssignmentResolverContributors();

    /**
     * Declares the empty-set default for {@link TimerResolverContributor} multibinding.
     *
     * @return empty set (Dagger-generated)
     */
    @Multibinds
    abstract Set<TimerResolverContributor> timerResolverContributors();

    // --- Binds (interface → implementation) ---

    /**
     * Binds {@link CelExpressionProfile} as the singleton {@link ExpressionProfile}.
     *
     * <p>To replace the expression engine, provide an alternative implementation and re-bind this
     * method — no other class in the module requires changes (NFR-WF-DEF-007).
     *
     * @param impl the singleton CEL implementation; non-null
     * @return the profile interface
     */
    @Binds
    @Singleton
    abstract ExpressionProfile expressionProfile(CelExpressionProfile impl);

    /**
     * Binds {@link DefaultBranchResultReducerRegistry} to {@link BranchResultReducerRegistry}.
     *
     * @param impl the singleton implementation; non-null
     * @return the registry interface
     */
    @Binds
    @Singleton
    abstract BranchResultReducerRegistry branchResultReducerRegistry(DefaultBranchResultReducerRegistry impl);

    /**
     * Binds {@link DefaultFailMessageFactoryRegistry} to {@link FailMessageFactoryRegistry}.
     *
     * @param impl the singleton implementation; non-null
     * @return the registry interface
     */
    @Binds
    @Singleton
    abstract FailMessageFactoryRegistry failMessageFactoryRegistry(DefaultFailMessageFactoryRegistry impl);

    /**
     * Binds {@link DefaultNamedConditionRegistry} to {@link NamedConditionRegistry}.
     *
     * @param impl the singleton implementation; non-null
     * @return the registry interface
     */
    @Binds
    @Singleton
    abstract NamedConditionRegistry namedConditionRegistry(DefaultNamedConditionRegistry impl);

    /**
     * Binds {@link DefaultPayloadMapperRegistry} to {@link PayloadMapperRegistry}.
     *
     * @param impl the singleton implementation; non-null
     * @return the registry interface
     */
    @Binds
    @Singleton
    abstract PayloadMapperRegistry payloadMapperRegistry(DefaultPayloadMapperRegistry impl);

    /**
     * Binds {@link DefaultStartStateMapperRegistry} to {@link StartStateMapperRegistry}.
     *
     * @param impl the singleton implementation; non-null
     * @return the registry interface
     */
    @Binds
    @Singleton
    abstract StartStateMapperRegistry startStateMapperRegistry(DefaultStartStateMapperRegistry impl);

    /**
     * Binds {@link DefaultStateMutatorRegistry} to {@link StateMutatorRegistry}.
     *
     * @param impl the singleton implementation; non-null
     * @return the registry interface
     */
    @Binds
    @Singleton
    abstract StateMutatorRegistry stateMutatorRegistry(DefaultStateMutatorRegistry impl);

    /**
     * Binds {@link DefaultStateReducerRegistry} to {@link StateReducerRegistry}.
     *
     * @param impl the singleton implementation; non-null
     * @return the registry interface
     */
    @Binds
    @Singleton
    abstract StateReducerRegistry stateReducerRegistry(DefaultStateReducerRegistry impl);

    /**
     * Binds {@link DefaultSubjectResolverRegistry} to {@link SubjectResolverRegistry}.
     *
     * @param impl the singleton implementation; non-null
     * @return the registry interface
     */
    @Binds
    @Singleton
    abstract SubjectResolverRegistry subjectResolverRegistry(DefaultSubjectResolverRegistry impl);

    /**
     * Binds {@link DefaultTaskAssignmentResolverRegistry} to
     * {@link TaskAssignmentResolverRegistry}.
     *
     * @param impl the singleton implementation; non-null
     * @return the registry interface
     */
    @Binds
    @Singleton
    abstract TaskAssignmentResolverRegistry taskAssignmentResolverRegistry(DefaultTaskAssignmentResolverRegistry impl);

    /**
     * Binds {@link DefaultTimerResolverRegistry} to {@link TimerResolverRegistry}.
     *
     * @param impl the singleton implementation; non-null
     * @return the registry interface
     */
    @Binds
    @Singleton
    abstract TimerResolverRegistry timerResolverRegistry(DefaultTimerResolverRegistry impl);

    /**
     * Binds {@link DefaultWorkflowDefinitionService} as the singleton
     * {@link WorkflowDefinitionService}.
     *
     * @param impl the singleton implementation; non-null
     * @return the service interface
     */
    @Binds
    @Singleton
    abstract WorkflowDefinitionService workflowDefinitionService(DefaultWorkflowDefinitionService impl);

    // --- Provides (contributes bootstrap into WorkflowContributor multibinding) ---

    /**
     * Exposes {@link WorkflowDefinitionBootstrap} as a {@link WorkflowContributor} into the
     * core multibinding.
     *
     * <p>This causes the bootstrap to run at {@link dev.vertique.workflow.registry.WorkflowRegistry}
     * construction time (via {@code WorkflowCoreModule.registry(...)}), loading all definitions
     * from registered {@link WorkflowDefinitionSource}s before the application starts serving
     * traffic.
     *
     * @param bootstrap the singleton bootstrap instance; non-null
     * @return the bootstrap as a {@link WorkflowContributor}
     */
    @Provides
    @IntoSet
    @Singleton
    static WorkflowContributor definitionBootstrapContributor(WorkflowDefinitionBootstrap bootstrap) {
        return bootstrap;
    }
}
