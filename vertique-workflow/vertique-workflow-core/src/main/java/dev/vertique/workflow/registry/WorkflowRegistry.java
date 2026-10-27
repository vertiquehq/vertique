// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
     * Returns the {@link RuntimeWorkflow} for one version of a definition in one scope.
     *
     * <p>This method is used by the engine when resuming an in-flight instance that was pinned to
     * a specific plan version at start time; the key is the instance's definition scope, id and version.
     *
     * @param key the scope, definition id and exact plan version to resolve
     * @return the runtime workflow for the key; its {@link RuntimeWorkflow#definitionScopeKey()} is the
     *     scope of the key
     * @throws WorkflowVersionPinUnavailableException if the key is not registered; the exception names the
     *     scope, the definition id and the version. A key whose plan is not held, and may be loadable by
     *     {@link #load(DefinitionKey)}, fails with its subtype
     *     {@link dev.vertique.workflow.exception.WorkflowDefinitionNotFoundException}
     */
    RuntimeWorkflow resolve(DefinitionKey key);

    /**
     * Returns the {@link RuntimeWorkflow} for one version of a definition in one scope, if registered.
     *
     * <p>This never loads: a plan that is not held, or is no longer resident in the plan cache, is empty here even
     * though {@link #load(DefinitionKey)} could supply it.
     *
     * @param key the scope, definition id and exact plan version to look up
     * @return the runtime workflow, or empty when the key is not registered
     */
    Optional<RuntimeWorkflow> find(DefinitionKey key);

    /**
     * Returns the plan of one version, loading it when this node does not hold it.
     *
     * <p>The plan of a code definition, and of any version this registry holds, is returned at once. A document
     * plan the registry does not hold is read from its stored row and built on a worker thread, outside any
     * transaction, into the bounded plan cache; concurrent requests for one key share one read and one build, and the
     * plan stays resident until the cache evicts it. A deterministic failure, such as a document that no longer
     * compiles, a name that no class resolves or a rebuilt plan hash that differs from the stored one, is remembered
     * and fails every later request for the key. A failure to read the row is not remembered.
     *
     * <p>Members added in later releases are abstract, so a third-party implementation must supply this one.
     *
     * @param key the scope, definition id and exact plan version
     * @return a future of the plan; it fails with
     *     {@link dev.vertique.workflow.exception.WorkflowDefinitionNotFoundException} when no row supplies the
     *     version, with a {@link dev.vertique.workflow.exception.WorkflowDefinitionException} subtype when the plan
     *     cannot be built, or with the persistence failure that prevented the row from being read
     */
    Future<RuntimeWorkflow> load(DefinitionKey key);

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
     * is empty for it, as it is for a class that was never registered. The answer outlives
     * {@link #unregister(DefinitionKey)}: a class whose every registered version has retired still selects its
     * definition id, though no version of it can then start.
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

    /**
     * Registers the plan of a failing scope row: every registration rule of
     * {@link #register(String, WorkflowDefinition)} applies except the comparison of the scope's state type with the
     * default scope's, so the instances of a row that newly deployed code conflicts with keep running. Unsupported
     * registries fail explicitly.
     *
     * @param scopeKey the scope that owns the definition
     * @param definition the definition to register
     */
    default void registerFailing(String scopeKey, WorkflowDefinition<?, ?> definition) {
        throw new UnsupportedOperationException("registerFailing is not supported");
    }

    /** Removes a version. Unsupported registries fail explicitly. */
    default void unregister(DefinitionKey key) {
        throw new UnsupportedOperationException("unregister is not supported");
    }

    /**
     * Marks one registered version of a definition as deprecated. A deprecated version is refused
     * for new starts, whether named or selected by an activation record, and as a migration target, yet remains
     * available through pinned resolution so in-flight instances keep running. The mark does not move an
     * activation record; only the computed default, used when no store is bound, skips a deprecated version.
     * Unregistering the version clears the mark.
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
     * Clears the deprecation mark of one registered version of a definition, so that new starts and migration
     * targets accept it again, and the computed default, used when no store is bound, counts it. The registration itself is untouched.
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

    /**
     * Whether a version was registered through {@link #register(WorkflowDefinition)}, which is how the deployed
     * code and the packaged documents register, and not through {@link #register(String, WorkflowDefinition)}, which
     * publishes a version at run time.
     *
     * <p>This is an internal framework seam: an engine without a durable registry uses it to compute the default
     * scope's current versions from what the deployment registered. Applications do not call it. The default
     * implementation returns {@code true}, which preserves the behavior of existing registries that do not
     * distinguish the two.
     *
     * @param key the scope, definition id and exact plan version
     * @return {@code true} if the version is registered and was registered as deployed
     */
    default boolean isDeployed(DefinitionKey key) {
        return true;
    }

    /**
     * Registers a workflow definition in the given scope under the type names its plan hash was first computed
     * with.
     *
     * <p>This is an internal framework seam: the durable registry restores a version with the name binding
     * stored on its row, so annotating a state or payload class with a logical name does not change the hash of
     * a version published before. A class the binding holds is named by the bound name in the plan and its hash;
     * any other class is named by its current logical name. Applications do not call it. The default
     * implementation refuses the call.
     *
     * @param scopeKey the scope that owns the definition
     * @param def the workflow definition to register
     * @param nameBinding the binary name of a state or payload class mapped to the name to use for it
     * @throws UnsupportedOperationException if this registry does not support name bindings
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionException under the rules of
     *     {@link #register(String, WorkflowDefinition)}
     */
    default void register(String scopeKey, WorkflowDefinition<?, ?> def, Map<String, String> nameBinding) {
        throw new UnsupportedOperationException(
                getClass().getName() + " does not support registering a definition under a name binding");
    }

    /**
     * Returns the binding of a registered version: the binary name of its state class and of every signal and
     * decision payload class its plan names, mapped to the name its plan hash used.
     *
     * <p>This is an internal framework seam: the durable registry stores the binding with a version's plan hash
     * when it first records the version. Applications do not call it. The default implementation returns an
     * empty map.
     *
     * @param key the scope, definition id and exact plan version
     * @return the binding, or an empty map when the version is not registered or this registry keeps none
     */
    default Map<String, String> nameBinding(DefinitionKey key) {
        return Map.of();
    }

    /**
     * Replaces the runtime of a registered version with one whose plan was built under a name binding, when that
     * plan has the required hash.
     *
     * <p>This is an internal framework seam: at startup the durable registry rebuilds a version that deployed
     * code or a packaged document registered under current logical names, so that it carries the plan hash
     * stored on its row. The version keeps its deprecation mark and its deployed flag, and nothing changes when
     * the hash differs. Applications do not call it. The default implementation changes nothing.
     *
     * @param key the scope, definition id and exact plan version
     * @param nameBinding the binary name of a state or payload class mapped to the name to use for it
     * @param requiredPlanHash the plan hash the rebuilt plan must have
     * @return the runtime now registered, or empty when nothing was replaced
     */
    default Optional<RuntimeWorkflow> rebind(
            DefinitionKey key, Map<String, String> nameBinding, String requiredPlanHash) {
        return Optional.empty();
    }
}
