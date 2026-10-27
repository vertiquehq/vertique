// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowCodeDefinitionCatalog.CodeDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry.StateComparison;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.EchoedNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The mechanics that every managed publisher of an executable definition shares: choosing the loader of a document
 * format, checking a stored code row against the deployed code, and building a stored definition into a plan to
 * validate it.
 *
 * <p>The methods are stateless. Callers make them under the {@link DefinitionApplyQueue} slot of the id. The loader,
 * hash and name checks are public for the engine's plan loader, which builds a stored plan on demand; the rest is
 * package-private.
 */
public final class DefinitionPublication {

    /** The most unresolved names a refusal lists; the message states the total when there are more. */
    static final int MAX_LISTED_NAMES = 10;

    private DefinitionPublication() {}

    /**
     * Fails when a name in the row's binding no longer resolves to a class, so a version bound to the binary name
     * of a class that moved is refused before anything is registered instead of failing its instances later.
     * Names resolve only through the type-name index; no class is loaded by a stored name. The message lists at
     * most {@value #MAX_LISTED_NAMES} names, each bounded and printable, and states the total.
     *
     * @throws WorkflowUnknownTypeNameException when the binding names a class that no longer resolves
     */
    public static void requireBoundNamesResolve(WorkflowTypeNames typeNames, WorkflowDefinitionRecord row) {
        List<String> unresolved = row.nameBinding().values().stream()
                .distinct()
                .filter(name -> typeNames.find(name).isEmpty())
                .sorted()
                .toList();
        if (unresolved.isEmpty()) return;
        DefinitionKey key = row.key();
        String listed = unresolved.stream()
                .limit(MAX_LISTED_NAMES)
                .map(EchoedNames::bounded)
                .collect(Collectors.joining(", "));
        String count = unresolved.size() > MAX_LISTED_NAMES ? " (" + unresolved.size() + " names in total)" : "";
        throw new WorkflowUnknownTypeNameException("definition '" + EchoedNames.bounded(key.definitionId())
                + "' version " + key.definitionVersion() + " in scope '" + EchoedNames.bounded(key.scopeKey())
                + "' was recorded with type names that no class resolves any more: " + listed + count
                + "; keep the class under that name, or retire the version before moving the class");
    }

    /** The one loader that supports the record's document format; any other count fails as a missing loader. */
    public static PersistedDefinitionLoader loaderFor(
            Set<PersistedDefinitionLoader> loaders, WorkflowDefinitionRecord record) {
        List<PersistedDefinitionLoader> matches = loaders.stream()
                .filter(loader -> loader.supports(record.documentFormat()))
                .toList();
        if (matches.size() != 1)
            throw failure(
                    Reason.LOADER_MISSING,
                    record.key(),
                    "expected one loader for " + record.documentFormat() + ", found " + matches.size());
        return matches.getFirst();
    }

    /**
     * The plan hash of the compiled definition under the type names the row was recorded with; a row without a
     * binding hashes under current names.
     */
    public static String planHash(
            PersistedDefinitionLoader loader, WorkflowDefinition<?, ?> definition, WorkflowDefinitionRecord record) {
        return record.nameBinding().isEmpty()
                ? loader.planHash(definition)
                : loader.planHash(definition, record.nameBinding());
    }

    /**
     * Returns the code definition the deployed code provides for the row, after its plan hash was checked against
     * the row's.
     *
     * @throws WorkflowDefinitionLifecycleException with {@link Reason#CODE_UNAVAILABLE} when the deployed code
     *     does not provide a retained definition of the version, and with {@link Reason#DRIFT} when its plan hash
     *     differs from the stored one
     */
    static CodeDefinition verifiedCode(WorkflowCodeDefinitionCatalog catalog, WorkflowDefinitionRecord record) {
        DefinitionKey key = record.key();
        CodeDefinition code = catalog.find(key.definitionId(), key.definitionVersion())
                .filter(entry -> entry.definition() != null)
                .orElseThrow(() -> failure(
                        Reason.CODE_UNAVAILABLE,
                        key,
                        "the deployed code does not provide " + key.definitionId() + " version "
                                + key.definitionVersion()));
        if (!record.planHash().equals(code.planHash()))
            throw failure(
                    Reason.DRIFT,
                    key,
                    "stored hash " + record.planHash() + " differs from deployed hash " + code.planHash());
        return code;
    }

    /**
     * Builds the plan of a stored definition under the names its row was recorded with, so that every rule a plan
     * build applies refuses an invalid definition before the row is published. Nothing is registered and no plan is
     * kept: the plan cache builds the plan of a published row when it is first used.
     *
     * @param registry the registry whose validators the build applies; another registry than the framework's builds
     *     with the framework's rules alone
     * @param key the scope, definition id and version of the row
     * @param definition the compiled definition of the row
     * @param nameBinding the names the row's plan hash used; an empty binding builds under current names
     * @return the built plan
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionException when the definition does not build
     */
    static RuntimeWorkflow build(
            WorkflowRegistry registry,
            DefinitionKey key,
            WorkflowDefinition<?, ?> definition,
            Map<String, String> nameBinding) {
        DefaultWorkflowRegistry builder =
                registry instanceof DefaultWorkflowRegistry framework ? framework : new DefaultWorkflowRegistry();
        return builder.build(key.scopeKey(), definition, nameBinding);
    }

    /**
     * Builds the plan of a stored document for publication or reinstatement: the build of {@link #build}, with a
     * failure of the document always leaving as a {@link WorkflowDefinitionException}, and, for a scope other than
     * the default one, the check that the state type extends the state type of the default scope's code definition
     * of the id.
     *
     * <p>The default scope's current state type is the one of the code definition registered for the id when the
     * check runs; a document of the default scope is never registered and so does not count. A registry other than
     * the framework's has no default scope to compare with and builds with the framework's rules alone. A refusal
     * names the scope, id and version and never the document's content.
     *
     * @return the built plan, which the caller may validate but does not register
     * @throws WorkflowDefinitionException when the document does not build or its state type does not extend the
     *     default scope's; a failure that is not one is the cause of the exception thrown and its message ends the message
     */
    static RuntimeWorkflow buildPublished(
            WorkflowRegistry registry,
            DefinitionKey key,
            WorkflowDefinition<?, ?> definition,
            Map<String, String> nameBinding) {
        RuntimeWorkflow plan;
        try {
            plan = build(registry, key, definition, nameBinding);
        } catch (WorkflowDefinitionException refusal) {
            throw refusal;
        } catch (RuntimeException failure) {
            throw new WorkflowDefinitionException(
                    "Cannot build DOCUMENT " + key + " from its stored source: " + failure.getMessage(), failure);
        }
        if (!WorkflowScopes.DEFAULT.equals(key.scopeKey()) && registry instanceof DefaultWorkflowRegistry framework) {
            StateComparison comparison = framework.compareWithDefault(plan);
            if (!comparison.accepted())
                throw new WorkflowDefinitionException("Definition " + key + ": the state type does not extend the"
                        + " state type of the default scope's '" + EchoedNames.bounded(key.definitionId())
                        + "' of the same contract");
        }
        return plan;
    }

    static WorkflowDefinitionLifecycleException failure(Reason reason, DefinitionKey key, String message) {
        return new WorkflowDefinitionLifecycleException(reason, key, "Definition " + key + ": " + message);
    }
}
