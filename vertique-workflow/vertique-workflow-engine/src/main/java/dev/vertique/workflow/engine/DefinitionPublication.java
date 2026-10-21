// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowCodeDefinitionCatalog.CodeDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * The mechanics that every managed publisher of an executable definition shares: reading what the registry holds
 * for a version, choosing the loader of a document format, checking a stored code row against the deployed code,
 * and registering a definition under the owner's attribution.
 *
 * <p>The methods are stateless. Callers make them under the {@link DefinitionApplyQueue} slot of the id.
 */
final class DefinitionPublication {

    private DefinitionPublication() {}

    /** The runtime the registry holds for the exact key, or {@code null} when it holds none. */
    static @Nullable RuntimeWorkflow held(WorkflowRegistry local, DefinitionKey key) {
        try {
            return local.resolve(key);
        } catch (WorkflowVersionPinUnavailableException | WorkflowDefinitionMissingException absent) {
            return null;
        }
    }

    /** The one loader that supports the record's document format; any other count fails as a missing loader. */
    static PersistedDefinitionLoader loaderFor(
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
     * Registers the definition and attributes the exact registered runtime to the key's owner. When the
     * attribution cannot be recorded, the registration this call made is removed again.
     *
     * @return the runtime the registry now holds for the key
     */
    static RuntimeWorkflow publish(
            WorkflowRegistry local,
            ManagedRegistrations registrations,
            DefinitionKey key,
            WorkflowDefinition<?, ?> definition) {
        local.register(key.scopeKey(), definition);
        RuntimeWorkflow published = null;
        try {
            published = local.resolve(key);
            registrations.attribute(key.scopeKey(), key.definitionId(), key.definitionVersion(), published);
            return published;
        } catch (RuntimeException e) {
            // The registration was made by this call, so it is removed whatever the registry holds under the key.
            try {
                local.unregister(key);
                if (published != null) registrations.clear(key.definitionId(), key.definitionVersion(), published);
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
