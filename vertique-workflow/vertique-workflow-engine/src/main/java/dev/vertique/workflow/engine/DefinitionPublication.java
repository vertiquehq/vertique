// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowCodeDefinitionCatalog.CodeDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.types.EchoedNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The mechanics that every managed publisher of an executable definition shares: reading what the registry holds
 * for a version, choosing the loader of a document format, checking a stored code row against the deployed code,
 * and registering a definition under the key's attribution.
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

    /**
     * The runtime the registry holds for the exact key, or {@code null} when it holds none. A plan that only the plan
     * cache holds is not a registration, so it is not held: it was built from the row and no lifecycle operation
     * published it.
     */
    static @Nullable RuntimeWorkflow held(WorkflowRegistry local, DefinitionKey key) {
        try {
            RuntimeWorkflow runtime = local.resolve(key);
            // The framework registry also answers from the plan cache; it registered the plan if it kept its source
            boolean cacheOnly = local instanceof DefaultWorkflowRegistry
                    && local.registeredDefinition(key).isEmpty();
            return cacheOnly ? null : runtime;
        } catch (WorkflowVersionPinUnavailableException | WorkflowDefinitionMissingException absent) {
            return null;
        }
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
     * Registers the definition and attributes the exact registered runtime to the key. When the
     * attribution cannot be recorded, the registration this call made is removed again.
     *
     * @param nameBinding the names the row's plan hash used; an empty binding registers under current names
     * @return the runtime the registry now holds for the key
     */
    static RuntimeWorkflow publish(
            WorkflowRegistry local,
            ManagedRegistrations registrations,
            DefinitionKey key,
            WorkflowDefinition<?, ?> definition,
            Map<String, String> nameBinding) {
        if (nameBinding.isEmpty()) local.register(key.scopeKey(), definition);
        else local.register(key.scopeKey(), definition, nameBinding);
        return attribute(local, registrations, key);
    }

    /**
     * Registers the definition of a failing scope row, which skips only the comparison of the scope's state type with
     * the default scope's, and attributes the exact registered runtime to the key. The plan is then rebuilt under the
     * names the row's plan hash used when that gives the stored hash.
     *
     * @param nameBinding the names the row's plan hash used; an empty binding keeps the current names
     * @param planHash the plan hash stored for the row
     * @return the runtime the registry now holds for the key
     */
    static RuntimeWorkflow publishFailing(
            WorkflowRegistry local,
            ManagedRegistrations registrations,
            DefinitionKey key,
            WorkflowDefinition<?, ?> definition,
            Map<String, String> nameBinding,
            String planHash) {
        local.registerFailing(key.scopeKey(), definition);
        RuntimeWorkflow published = attribute(local, registrations, key);
        if (nameBinding.isEmpty() || planHash.equals(published.plan().planHash())) return published;
        Optional<RuntimeWorkflow> rebound = local.rebind(key, nameBinding, planHash);
        if (rebound.isEmpty()) return published;
        registrations.attribute(key.scopeKey(), key.definitionId(), key.definitionVersion(), rebound.get());
        return rebound.get();
    }

    private static RuntimeWorkflow attribute(
            WorkflowRegistry local, ManagedRegistrations registrations, DefinitionKey key) {
        RuntimeWorkflow published = null;
        try {
            published = local.resolve(key);
            registrations.attribute(key.scopeKey(), key.definitionId(), key.definitionVersion(), published);
            return published;
        } catch (RuntimeException e) {
            // The registration was made by this call, so it is removed whatever the registry holds under the key.
            try {
                local.unregister(key);
                if (published != null)
                    registrations.clear(key.scopeKey(), key.definitionId(), key.definitionVersion(), published);
            } catch (RuntimeException compensation) {
                if (compensation != e) e.addSuppressed(compensation);
            }
            throw e;
        }
    }

    static WorkflowDefinitionLifecycleException failure(Reason reason, DefinitionKey key, String message) {
        return new WorkflowDefinitionLifecycleException(reason, key, "Definition " + key + ": " + message);
    }
}
