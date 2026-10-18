// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import java.util.List;

/** Internal format-specific compilation and cache synchronization seam. */
public interface PersistedDefinitionLoader {
    boolean supports(String documentFormat);

    List<DocumentOrigin> documentOrigins();

    WorkflowDefinition<?, ?> load(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document);

    String planHash(WorkflowDefinition<?, ?> definition);

    void onRestored(WorkflowDefinitionRecord record, WorkflowDefinition<?, ?> definition);

    void onMetadata(WorkflowDefinitionRecord record);
}
