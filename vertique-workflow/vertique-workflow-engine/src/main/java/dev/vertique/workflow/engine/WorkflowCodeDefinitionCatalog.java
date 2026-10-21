// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
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
                .map(RuntimeWorkflow::plan)
                .map(plan -> new VersionKey(plan.definitionId(), plan.definitionVersion()))
                .filter(key -> !documentOrigins.contains(key))
                .collect(Collectors.toUnmodifiableMap(key -> key, key -> {
                    RuntimeWorkflow runtime = registry.resolvePinned(key.definitionId(), key.version());
                    return new CodeDefinition(
                            key.definitionId(),
                            key.version(),
                            runtime.contractName(),
                            runtime.plan().stateTypeName(),
                            runtime.plan().planHash(),
                            registry.registeredDefinition(key.definitionId(), key.version())
                                    .orElse(null));
                }));
        entries = snapshot;
        return ordered(snapshot);
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
     */
    record CodeDefinition(
            String definitionId,
            long version,
            String contractName,
            String stateTypeName,
            String planHash,
            @Nullable WorkflowDefinition<?, ?> definition) {}
}
