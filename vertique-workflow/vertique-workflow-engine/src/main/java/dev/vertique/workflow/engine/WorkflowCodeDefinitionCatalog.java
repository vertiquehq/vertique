// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Remembers which code-first definitions the deployed code provides, so that they stay known after the
 * registry stops serving them.
 *
 * <p>The startup step takes the snapshot once, before it applies any retired membership to the registry.
 * Applying a retirement removes the executable registration, but the deployed code still provides that
 * definition. Later work that publishes a definition again or that has to tell whether the deployed code still
 * provides a stored one therefore asks this catalog and not the registry.
 *
 * <p>The snapshot holds the definitions that were registered when the step started, except the ones that came
 * from packaged documents. It is immutable once taken and is replaced as a whole.
 */
@Singleton
final class WorkflowCodeDefinitionCatalog {
    private volatile Map<VersionKey, CodeDefinition> entries = Map.of();

    @Inject
    WorkflowCodeDefinitionCatalog() {}

    /**
     * Takes the snapshot of the code definitions currently registered and replaces the previous one.
     *
     * @param registry the registry that holds the definitions the deployed code registered
     * @param documentOrigins the ids and versions that were registered from packaged documents, which are not code
     * @return the snapshot, ordered by id and version
     */
    List<CodeDefinition> capture(WorkflowRegistry registry, Set<VersionKey> documentOrigins) {
        Map<VersionKey, CodeDefinition> snapshot = registry.allRegistered().stream()
                .filter(runtime -> WorkflowScopes.DEFAULT.equals(runtime.definitionScopeKey()))
                .filter(runtime -> !documentOrigins.contains(versionKey(runtime)))
                .collect(Collectors.toUnmodifiableMap(
                        WorkflowCodeDefinitionCatalog::versionKey,
                        runtime -> new CodeDefinition(
                                runtime.plan().definitionId(),
                                runtime.plan().definitionVersion(),
                                runtime.contractName(),
                                runtime.plan().stateTypeName(),
                                runtime.plan().planHash(),
                                registeredDefinition(registry, versionKey(runtime)),
                                nameBinding(registry, versionKey(runtime)))));
        entries = snapshot;
        return ordered(snapshot);
    }

    private static VersionKey versionKey(RuntimeWorkflow runtime) {
        return new VersionKey(runtime.plan().definitionId(), runtime.plan().definitionVersion());
    }

    /**
     * The definition the registry retained for a default-scope version. An id or version that no key can hold has
     * no retained definition; the startup step names such a definition when it records it.
     */
    private static @Nullable WorkflowDefinition<?, ?> registeredDefinition(WorkflowRegistry registry, VersionKey key) {
        try {
            return registry.registeredDefinition(
                            new DefinitionKey(WorkflowScopes.DEFAULT, key.definitionId(), key.version()))
                    .orElse(null);
        } catch (IllegalArgumentException invalidKey) {
            return null;
        }
    }

    /** The binding of the plan the registry holds for a default-scope version, empty for a key no key can hold. */
    private static Map<String, String> nameBinding(WorkflowRegistry registry, VersionKey key) {
        try {
            return registry.nameBinding(new DefinitionKey(WorkflowScopes.DEFAULT, key.definitionId(), key.version()));
        } catch (IllegalArgumentException invalidKey) {
            return Map.of();
        }
    }

    /**
     * Records that the registry now holds a rebuilt plan of the version, so that later checks compare the hash
     * the registry serves. Nothing changes for a version the snapshot does not hold.
     */
    void rebound(String definitionId, long version, String planHash) {
        VersionKey key = new VersionKey(definitionId, version);
        entries = entries.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> {
            CodeDefinition code = entry.getValue();
            return entry.getKey().equals(key)
                    ? new CodeDefinition(
                            code.definitionId(),
                            code.version(),
                            code.contractName(),
                            code.stateTypeName(),
                            planHash,
                            code.definition(),
                            code.nameBinding())
                    : code;
        }));
    }

    /** Returns the code definition the deployed code provides for the exact id and version, if any. */
    Optional<CodeDefinition> find(String definitionId, long version) {
        return Optional.ofNullable(entries.get(new VersionKey(definitionId, version)));
    }

    /** Returns every code definition of the snapshot, ordered by id and version. */
    List<CodeDefinition> all() {
        return ordered(entries);
    }

    private static List<CodeDefinition> ordered(Map<VersionKey, CodeDefinition> snapshot) {
        return snapshot.values().stream()
                .sorted(Comparator.comparing(CodeDefinition::definitionId).thenComparingLong(CodeDefinition::version))
                .toList();
    }

    /** An id and exact version. */
    record VersionKey(String definitionId, long version) {}

    /**
     * One code definition the deployed code provides.
     *
     * @param definitionId the definition id
     * @param version the exact version
     * @param contractName the logical name of the contract the registered runtime implements
     * @param stateTypeName the logical name of the state type of the registered runtime's plan
     * @param planHash the plan hash of the registered runtime
     * @param definition the definition as it was registered, or {@code null} when the registry does not retain it
     * @param nameBinding the binary name of each state and payload class of the registered plan, mapped to the name
     *     the registered plan hash used
     */
    record CodeDefinition(
            String definitionId,
            long version,
            String contractName,
            String stateTypeName,
            String planHash,
            @Nullable WorkflowDefinition<?, ?> definition,
            Map<String, String> nameBinding) {}
}
