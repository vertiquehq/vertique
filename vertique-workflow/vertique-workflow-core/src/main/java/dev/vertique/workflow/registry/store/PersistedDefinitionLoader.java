// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import java.util.List;
import java.util.Map;

/** Internal format-specific compilation and cache synchronization seam. */
public interface PersistedDefinitionLoader {
    boolean supports(String documentFormat);

    List<DocumentOrigin> documentOrigins();

    WorkflowDefinition<?, ?> load(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document);

    String planHash(WorkflowDefinition<?, ?> definition);

    /**
     * Computes the plan hash of the definition under the type names a stored row was published with.
     *
     * <p>The default implementation ignores the binding and returns {@link #planHash(WorkflowDefinition)}.
     *
     * @param definition the compiled definition
     * @param nameBinding the binary name of a state or payload class mapped to the name its hash used
     * @return the plan hash
     */
    default String planHash(WorkflowDefinition<?, ?> definition, Map<String, String> nameBinding) {
        return planHash(definition);
    }

    void onRestored(WorkflowDefinitionRecord record, WorkflowDefinition<?, ?> definition);

    void onMetadata(WorkflowDefinitionRecord record);
}
