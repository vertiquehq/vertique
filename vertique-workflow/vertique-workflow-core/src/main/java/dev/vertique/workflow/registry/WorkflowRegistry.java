// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Central registry for workflow definitions and their runtime metadata.
 *
 * <p>Definitions are registered at application startup via {@link WorkflowContributor}
 * implementations contributed to the Dagger multibinding. The registry validates each definition
 * at registration time and rejects invalid plans with a {@code WorkflowDefinitionException}.
 *
 * <p>A registered version is identified by a {@link DefinitionKey}: the scope that owns it, the definition
 * id and the version. Code definitions register in the default scope, {@link WorkflowScopes#DEFAULT}, and a
 * scope may hold its own versions of an id the default scope also holds. Every pinned lookup takes the
 * full key.
 *
 * <p>Any number of definition ids and versions may implement one contract. Within one scope and
 * definition id every version must name the same contract. The registry maps the class of a registered
 * definition to its definition id and contract for {@code WorkflowClientFactory}.
 *
 * <p>The framework implements this interface ({@link DefaultWorkflowRegistry}); applications call it.
 * Members added in later releases, such as {@link #definitionClass(Class)}, {@link #resolve(DefinitionKey)}
 * and {@link #find(DefinitionKey)}, are abstract, so a third-party implementation must be updated with
 * the framework.
 */
public interface WorkflowRegistry {

    /**
     * Registers a workflow definition in the default scope, builds its plan via the DSL, and stores the
     * resulting {@link RuntimeWorkflow}.
     *
     * <p>Registration validates the plan: it must call {@code wf.init(...)} exactly once, and all
     * {@code WaitSignalNode} signal names must be unique within the plan. Violations throw
     * {@code WorkflowDefinitionException}.
     *
     * @param def the workflow definition to register
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionException if the plan is invalid
     */
    void register(WorkflowDefinition<?, ?> def);

    /**
     * Registers a workflow definition in the given scope, builds its plan via the DSL, and stores the
     * resulting {@link RuntimeWorkflow}.
     *
     * <p>The plan rules of {@link #register(WorkflowDefinition)} apply. In addition, the definition must
     * name the same contract as the versions already registered under the same scope and definition id,
     * and registering a key twice is refused. Another scope may register the same id under another
     * contract.
     *
     * @param scopeKey the scope that owns the definition; {@link WorkflowScopes#DEFAULT} for the default
     *     scope
     * @param def the workflow definition to register
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionException if the plan is invalid, the
     *     contract differs from the one of the scope and id, or the key is already registered
     */
    void register(String scopeKey, WorkflowDefinition<?, ?> def);

    /**
     * Returns the {@link RuntimeWorkflow} for the highest-version plan of the given definition in the
     * default scope that is not marked deprecated.
     *
     * @param definitionId id of the workflow definition
     * @return the runtime workflow for the current (highest non-deprecated) version
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionMissingException if no default-scope
     *     definition with the given id is registered, or every registered version of it is deprecated
     */
    RuntimeWorkflow resolveCurrent(String definitionId);

    /**
     * Returns the {@link RuntimeWorkflow} for one version of a definition in one scope.
     *
     * <p>This method is used by the engine when resuming an in-flight instance that was pinned to
     * a specific plan version at start time; the key is the instance's definition scope, id and version.
     *
     * @param key the scope, definition id and exact plan version to resolve
     * @return the runtime workflow for the key; its {@link RuntimeWorkflow#definitionScopeKey()} is the
     *     scope of the key
     * @throws WorkflowVersionPinUnavailableException if the key is not registered; the exception names the
     *     scope, the definition id and the version
     */
    RuntimeWorkflow resolve(DefinitionKey key);

    /**
     * Returns the {@link RuntimeWorkflow} for one version of a definition in one scope, if registered.
     *
     * @param key the scope, definition id and exact plan version to look up
     * @return the runtime workflow, or empty when the key is not registered
     */
    Optional<RuntimeWorkflow> find(DefinitionKey key);

    /**
     * Lists the keys of every registered version of a definition id across all scopes, ordered by scope
     * and then by version.
     *
     * <p>This is for listings. A start path asks {@link #contractOf(String, String)} and
     * {@link #find(DefinitionKey)}, which cost one lookup regardless of the number of scopes.
     *
     * @param definitionId id of the workflow definition
     * @return an immutable list of the registered keys; empty when no scope holds the id
     */
    List<DefinitionKey> keysFor(String definitionId);

    /**
     * Returns the contract name of the registered versions of one definition id in one scope.
     *
     * <p>The default implementation derives the answer from {@link #keysFor(String)}; the framework
     * registry answers from an index it keeps for its registration rules.
     *
     * @param scopeKey the scope to ask
     * @param definitionId id of the workflow definition
     * @return the contract name the versions of the scope and id name, or empty when the scope holds no
     *     version of the id
     */
    default Optional<String> contractOf(String scopeKey, String definitionId) {
        return keysFor(definitionId).stream()
                .filter(key -> key.scopeKey().equals(scopeKey))
                .findFirst()
                .flatMap(this::find)
                .map(RuntimeWorkflow::contractName);
    }

    /**
     * Returns the definition id and contract of a registered definition class.
     *
     * <p>The lookup is by the exact runtime class of the registered definition objects. A class whose
     * instances were registered under more than one definition id has no single answer, so the result
     * is empty for it, as it is for a class that was never registered.
     *
     * @param definitionClass the class of a registered {@link WorkflowDefinition}
     * @return the definition id, contract name and contract of the class, or empty when the class is
     *     not registered under exactly one definition id
     */
    Optional<RegisteredDefinitionClass> definitionClass(Class<?> definitionClass);

    /**
     * Returns an immutable snapshot of every registered {@link RuntimeWorkflow} across all scopes and
     * definitionId/version pairs. Used by startup validators to walk all plans without resolving
     * each one individually.
     *
     * @return immutable collection of all registered runtime workflows; never null, may be empty
     */
    Collection<RuntimeWorkflow> allRegistered();

    /** Removes a version. Unsupported registries fail explicitly. */
    default void unregister(DefinitionKey key) {
        throw new UnsupportedOperationException("unregister is not supported");
    }

    /**
     * Marks one registered version of a definition as deprecated. A deprecated version is skipped
     * by current resolution and by explicit new starts and migration targets, yet remains available
     * through pinned resolution so in-flight instances keep running. Unregistering the version
     * clears the mark.
     *
     * <p>Implementations must make the mark atomic with registration changes, so that no caller
     * observes a version that is registered but not yet marked after this method returns. The
     * default implementation throws, so registries that do not support deprecation fail explicitly.
     *
     * @param key the scope, definition id and exact plan version to mark as deprecated
     * @throws UnsupportedOperationException if this registry does not support deprecation
     */
    default void markDeprecated(DefinitionKey key) {
        throw new UnsupportedOperationException(
                getClass().getName() + " does not support the markDeprecated operation");
    }

    /**
     * Clears the deprecation mark of one registered version of a definition, so that current resolution and new
     * starts see it again. The registration itself is untouched.
     *
     * <p>Implementations must make the change atomic with registration changes. Clearing the mark of a version
     * that is not registered or not marked is a no-op. The default implementation throws, so registries that do
     * not support deprecation fail explicitly.
     *
     * @param key the scope, definition id and exact plan version whose mark is cleared
     * @throws UnsupportedOperationException if this registry does not support deprecation
     */
    default void unmarkDeprecated(DefinitionKey key) {
        throw new UnsupportedOperationException(
                getClass().getName() + " does not support the unmarkDeprecated operation");
    }

    /**
     * Returns whether one registered version of a definition is marked deprecated.
     *
     * <p>Implementations must read the mark atomically with registration changes. The default
     * implementation returns {@code false}, which preserves the behavior of existing registries
     * that do not track deprecation.
     *
     * @param key the scope, definition id and exact plan version to check
     * @return {@code true} if the version is registered and marked deprecated; {@code false}
     *     otherwise
     */
    default boolean isDeprecated(DefinitionKey key) {
        return false;
    }

    /**
     * Returns the definition that was passed to {@link #register} for one version.
     *
     * <p>This is an internal framework seam: the engine uses it to keep the source of the definitions
     * that the deployed code provides, so it can publish them again after the registry dropped them.
     * Applications do not call it. The default implementation returns an empty result, which preserves
     * the behavior of existing registries that do not retain their sources.
     *
     * @param key the scope, definition id and exact plan version
     * @return the definition registered for this version, or empty when none is registered or this
     *     registry does not retain sources
     */
    default Optional<WorkflowDefinition<?, ?>> registeredDefinition(DefinitionKey key) {
        return Optional.empty();
    }
}
