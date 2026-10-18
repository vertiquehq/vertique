// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema.fixtures;

import dev.vertique.workflow.contract.WorkflowContract;

/** Contract of the document-defined review workflow. */
@WorkflowContract(definitionId = "review-note", definitionVersion = 1)
public interface ReviewNoteWorkflowV1 {}
