// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.di;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.workflow.actor.DefaultWorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowActorMapper;
import dev.vertique.workflow.migration.WorkflowStateCompatibility;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.RaceSafetyTargetContributor;
import dev.vertique.workflow.plan.RaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeCatalog;
import dev.vertique.workflow.types.WorkflowTypeNames;
import jakarta.inject.Singleton;
import java.util.Optional;
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
     * Declares the optional state comparison; the schema module binds it. Without it the registry refuses
     * differing state classes of a scope-owned and a default definition of one id and contract.
     */
    @BindsOptionalOf
    abstract WorkflowStateCompatibility stateCompatibility();

    @BindsOptionalOf
    abstract WorkflowDefinitionsConfig definitionsConfig();

    /**
     * Declares the optional schema producer; the schema module binds it. Without it definition rows are written
     * without a start schema and without declared signal names.
     */
    @BindsOptionalOf
    abstract WorkflowPlanSchemaProducer planSchemaProducer();

    @Multibinds
    abstract Set<PersistedDefinitionLoader> persistedDefinitionLoaders();

    /**
     * Provides the {@link WorkflowRegistry} populated with all contributed definitions.
     *
     * <p>Each {@link WorkflowContributor} is invoked once in an unspecified order. Registration
     * validates each plan immediately; invalid plans throw
     * {@link dev.vertique.workflow.exception.WorkflowDefinitionException}.
     *
     * @param contributors the set of all contributed workflow definitions
     * @param forkJoinValidator the plan validator consulted on each registered plan
     * @param definitionValidators the contributed registration-time definition validators; may be
     *     empty
     * @param typeNames the shared type-name index the registry fills as definitions register
     * @param stateCompatibility the state comparison when the application installs one; empty otherwise
     * @return a fully-populated, immutable-after-build registry
     */
    @Provides
    @Singleton
    static WorkflowRegistry registry(
            Set<WorkflowContributor> contributors,
            WorkflowPlanValidator forkJoinValidator,
            Set<WorkflowDefinitionValidator> definitionValidators,
            DefaultWorkflowTypeNames typeNames,
            Optional<WorkflowStateCompatibility> stateCompatibility) {
        DefaultWorkflowRegistry reg =
                new DefaultWorkflowRegistry(forkJoinValidator, definitionValidators, typeNames, stateCompatibility);
        contributors.forEach(c -> c.contribute(reg));
        return reg;
    }

    /**
     * Provides the one process-wide type-name index. It starts with the entries of every contributed
     * {@link WorkflowTypeCatalog}; the registry adds the classes of each definition as it registers,
     * and the engine, the schema module and the document compiler resolve logical names through it.
     *
     * @param catalogs the contributed type catalogs; may be empty
     * @return the singleton index
     * @throws dev.vertique.workflow.exception.WorkflowTypeNameConflictException if two catalogs list
     *     different classes under one name
     */
    @Provides
    @Singleton
    static DefaultWorkflowTypeNames defaultTypeNames(Set<WorkflowTypeCatalog> catalogs) {
        return new DefaultWorkflowTypeNames(catalogs);
    }

    /**
     * Declares the empty {@code Set<WorkflowTypeCatalog>} multibinding. The annotation processor's
     * generated catalog module and any hand-written {@code @Provides @IntoSet WorkflowTypeCatalog}
     * method contribute to it; the contributed catalogs are merged into the type-name index at
     * startup.
     *
     * @return the (initially empty) set of type catalogs
     */
    @Multibinds
    abstract Set<WorkflowTypeCatalog> typeCatalogs();

    /**
     * Binds {@link WorkflowTypeNames} to the singleton index.
     *
     * @param impl the singleton index
     * @return the index bound as the lookup interface
     */
    @Binds
    abstract WorkflowTypeNames typeNames(DefaultWorkflowTypeNames impl);

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
     * INTERNAL framework seam — the framework implementation behind a contract this module
     * documents; not an application contract and outside the maturity promise.
     *
     * <p>Declares the empty {@code Set<WorkflowDefinitionValidator>} multibinding so that
     * framework modules can contribute registration-time definition validators via
     * {@code @IntoSet} without requiring at least one to be bound.
     *
     * @return the (initially empty) set of definition validators
     */
    @Multibinds
    abstract Set<WorkflowDefinitionValidator> definitionValidators();

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
