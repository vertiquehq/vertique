// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.compiler;

import dev.vertique.workflow.definition.callbacks.BranchResultReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultBranchResultReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultFailMessageFactoryRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateMutatorRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultSubjectResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTaskAssignmentResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTimerResolverRegistry;
import dev.vertique.workflow.definition.callbacks.FailMessageFactoryRegistry;
import dev.vertique.workflow.definition.callbacks.NamedConditionRegistry;
import dev.vertique.workflow.definition.callbacks.PayloadMapperRegistry;
import dev.vertique.workflow.definition.callbacks.RegisteredIdentifierLookup;
import dev.vertique.workflow.definition.callbacks.StartStateMapperRegistry;
import dev.vertique.workflow.definition.callbacks.StateMutatorRegistry;
import dev.vertique.workflow.definition.callbacks.StateReducerRegistry;
import dev.vertique.workflow.definition.callbacks.SubjectResolverRegistry;
import dev.vertique.workflow.definition.callbacks.TaskAssignmentResolverRegistry;
import dev.vertique.workflow.definition.callbacks.TimerResolverRegistry;
import java.util.Set;

/**
 * Shared test helpers for compiler tests in this package.
 *
 * <p>Provides factory methods for building {@link RegisteredIdentifierLookup} instances with
 * specific registries pre-configured for testing.
 */
final class Helpers {

    private Helpers() {
        // Utility class — no instances.
    }

    /**
     * Builds a {@link RegisteredIdentifierLookup} with the given payload mapper and start-state
     * mapper registries, and all other registries empty.
     *
     * @param payloadMappers the payload mapper registry to use; non-null
     * @param startStateMappers the start-state mapper registry to use; non-null
     * @param namedConditions the named condition registry to use; non-null
     * @return a fully-initialized lookup facade
     */
    static RegisteredIdentifierLookup lookupWith(
            PayloadMapperRegistry payloadMappers,
            StartStateMapperRegistry startStateMappers,
            NamedConditionRegistry namedConditions) {
        StateReducerRegistry stateReducers = new DefaultStateReducerRegistry(Set.of());
        StateMutatorRegistry stateMutators = new DefaultStateMutatorRegistry(Set.of());
        TimerResolverRegistry timerResolvers = new DefaultTimerResolverRegistry(Set.of());
        FailMessageFactoryRegistry failMessageFactories = new DefaultFailMessageFactoryRegistry(Set.of());
        SubjectResolverRegistry subjectResolvers = new DefaultSubjectResolverRegistry(Set.of());
        TaskAssignmentResolverRegistry taskAssignmentResolvers = new DefaultTaskAssignmentResolverRegistry(Set.of());
        BranchResultReducerRegistry branchResultReducers = new DefaultBranchResultReducerRegistry(Set.of());

        return new RegisteredIdentifierLookup(
                payloadMappers,
                startStateMappers,
                stateReducers,
                stateMutators,
                timerResolvers,
                failMessageFactories,
                subjectResolvers,
                taskAssignmentResolvers,
                branchResultReducers,
                namedConditions);
    }
}
