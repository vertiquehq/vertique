// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

import dev.vertique.workflow.subject.WorkflowSubjectRef;

/**
 * Optional payload-side interface that provides the subject reference for a {@link WorkflowStart}
 * operation.
 *
 * <p>If the start payload implements this interface, the proxy will call {@link #subjectRef()} to
 * populate the {@code StartCommand.subjectRef} field. A parameter-level {@link SubjectRef}
 * annotation takes precedence if both are present. The subject ref is optional and may return
 * null.
 */
public interface SubjectReferenced {

    /**
     * Returns the subject reference for this payload, or {@code null} if no subject ref should be
     * set.
     *
     * @return the subject reference, or null
     */
    WorkflowSubjectRef subjectRef();
}
