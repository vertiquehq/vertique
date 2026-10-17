// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

/**
 * INTERNAL framework seam — the framework implementation behind a contract this module documents;
 * not an application contract and outside the maturity promise.
 *
 * <p>Inspects a workflow definition during registration, after plan validation and before the
 * publish phase. The same hook runs for bootstrap and runtime registrations alike. The definition
 * is a candidate, not yet registered: if any validator throws, the definition is not published and
 * its {@code (definitionId, version)} key stays unresolvable.
 *
 * <p>Validators run synchronously on the thread calling {@code register} or {@code activate}; the
 * set of contributed validators is unordered. Exceptions thrown by this callback propagate and are
 * fatal to the enclosing operation; processing does not continue.
 */
public interface WorkflowDefinitionValidator {

    /**
     * Validates a candidate definition.
     *
     * @param definition the read-only view of the candidate definition; never {@code null}
     */
    void validate(RegisteredWorkflowDefinition definition);
}
