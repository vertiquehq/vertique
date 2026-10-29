// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import java.util.Collection;
import java.util.Optional;

/**
 * Central registry for workflow definitions and their runtime metadata.
 *
 * <p>Definitions are registered at application startup via {@link WorkflowContributor}
 * implementations contributed to the Dagger multibinding. The registry validates each definition
 * at registration time and rejects invalid plans with a {@code WorkflowDefinitionException}.
 *
 * <p>A version is identified by a {@link DefinitionKey}: the scope that owns it, the definition id and the version.
 * The registry registers only code definitions, in the default scope, {@link WorkflowScopes#DEFAULT}, while the
 * application starts. Document plans, packaged or published, are not registered: the definition store holds their
 * rows and the plan cache builds a plan from its row when it is first used, see {@link #load(DefinitionKey)}. Every
 * pinned lookup takes the full key.
 *
 * <p>Any number of definition ids and versions may implement one contract. Within the default scope and
 * one definition id every code version must name the same contract. The registry maps the class of a registered
 * definition to its definition id and contract for {@code WorkflowClientFactory}.
 *
 * <p>The framework implements this interface ({@link DefaultWorkflowRegistry}); applications call it.
 * Members added in later releases, such as {@link #definitionClass(Class)}, {@link #resolve(DefinitionKey)}
 * and {@link #find(DefinitionKey)}, are abstract, so a third-party implementation must be updated with
 * the framework.
 */
public interface WorkflowRegistry {

    /**
     * Registers a code definition in the default scope, builds its plan via the DSL, and stores the
     * resulting {@link RuntimeWorkflow}. Registration is a startup operation: once the engine has started, the
     * implementation refuses it with an {@link IllegalStateException}.
     *
     * <p>Registration validates the plan: it must call {@code wf.init(...)} exactly once, and all
     * {@code WaitSignalNode} signal names must be unique within the plan. Violations throw
     * {@code WorkflowDefinitionException}.
     *
     * @param def the workflow definition to register
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionException if the plan is invalid
     * @throws IllegalStateException if the engine has started
     */
    void register(WorkflowDefinition<?, ?> def);

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
     * Returns the definition id and contract of a registered definition class.
     *
     * <p>The lookup is by the exact runtime class of the registered definition objects. A class whose
     * instances were registered under more than one definition id has no single answer, so the result
     * is empty for it, as it is for a class that was never registered. The answer does not depend on the
     * status of any stored version: a class whose every version has retired still selects its definition id,
     * though no version of it can then start.
     *
     * @param definitionClass the class of a registered {@link WorkflowDefinition}
     * @return the definition id, contract name and contract of the class, or empty when the class is
     *     not registered under exactly one definition id
     */
    Optional<RegisteredDefinitionClass> definitionClass(Class<?> definitionClass);

    /**
     * Returns an immutable snapshot of the code definitions deployed with this node: the runtimes of every
     * {@link #register(WorkflowDefinition)} call, across all versions of all definition ids. Used by startup
     * validators and by the engine's startup to walk the deployed code without resolving each plan individually.
     *
     * <p>Only code definitions are listed. A document plan, packaged or published, is never listed: the plan
     * cache holds it while it is in use and may evict it at any time, and the rows of the definition store list
     * the documents. A code definition is never evicted.
     *
     * @return immutable collection of the deployed code definitions; never null, may be empty
     */
    Collection<RuntimeWorkflow> codeDefinitions();
}
