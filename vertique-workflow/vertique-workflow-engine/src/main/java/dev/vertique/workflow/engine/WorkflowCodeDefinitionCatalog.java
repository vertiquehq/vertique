// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Remembers which code-first definitions the deployed code provides, with the definition objects and name bindings
 * they were registered with.
 *
 * <p>The startup step takes the snapshot once, after the application registered its code and before the registry
 * is sealed. Work that has to tell whether the deployed code still provides a stored definition, or that records
 * the deployed code in the definition store, asks this catalog.
 *
 * <p>The snapshot is immutable once taken and is replaced as a whole.
 */
@Singleton
final class WorkflowCodeDefinitionCatalog {
    private volatile Map<VersionKey, CodeDefinition> entries = Map.of();

    @Inject
    WorkflowCodeDefinitionCatalog() {}

    /**
     * Takes the snapshot of the code definitions currently registered and replaces the previous one. A registry other
     * than the framework's does not retain its definitions, so its entries carry none and an empty name binding.
     *
     * @param registry the registry that holds the definitions the deployed code registered
     * @return the snapshot, ordered by id and version
     */
    List<CodeDefinition> capture(WorkflowRegistry registry) {
        List<CodeDefinition> code = registry instanceof DefaultWorkflowRegistry framework
                ? framework.codeRegistrations().stream()
                        .map(registration -> codeDefinition(
                                registration.runtime(), registration.definition(), registration.nameBinding()))
                        .toList()
                : registry.codeDefinitions().stream()
                        .filter(runtime -> WorkflowScopes.DEFAULT.equals(runtime.definitionScopeKey()))
                        .map(runtime -> codeDefinition(runtime, null, Map.of()))
                        .toList();
        Map<VersionKey, CodeDefinition> snapshot = code.stream()
                .collect(Collectors.toUnmodifiableMap(
                        definition -> new VersionKey(definition.definitionId(), definition.version()),
                        definition -> definition));
        entries = snapshot;
        return ordered(snapshot);
    }

    private static CodeDefinition codeDefinition(
            RuntimeWorkflow runtime, @Nullable WorkflowDefinition<?, ?> definition, Map<String, String> nameBinding) {
        return new CodeDefinition(
                runtime.plan().definitionId(),
                runtime.plan().definitionVersion(),
                runtime.contractName(),
                runtime.plan().stateTypeName(),
                runtime.plan().planHash(),
                definition,
                nameBinding);
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
