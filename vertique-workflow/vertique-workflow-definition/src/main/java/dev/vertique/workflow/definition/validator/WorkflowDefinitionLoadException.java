// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.validator;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Objects;

/**
 * Thrown when a {@link dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument}
 * contains one or more validation violations detected by
 * {@link WorkflowDefinitionDocumentValidator}.
 *
 * <p>The exception message is the human-readable formatted output of the accumulated
 * {@link WorkflowDefinitionViolations} so that a single structured error surfaces all problems in
 * one round-trip. Callers can access the structured violations via {@link #violations()} for
 * programmatic inspection.
 *
 * <p>The exception is unchecked because definition loading happens at startup or at an explicit
 * management surface; callers are expected to catch it and abort, not recover inline.
 */
public class WorkflowDefinitionLoadException extends WorkflowDefinitionException {

    /** Structured violations that triggered this exception. */
    private final WorkflowDefinitionViolations violations;

    /**
     * Constructs a load exception carrying all accumulated violations.
     *
     * <p>The exception message is {@code violations.formatted()}.
     *
     * @param violations the non-null, non-empty set of violations; must not be null
     * @throws NullPointerException if {@code violations} is null
     */
    public WorkflowDefinitionLoadException(WorkflowDefinitionViolations violations) {
        super(Objects.requireNonNull(violations, "violations").formatted());
        this.violations = violations;
    }

    /**
     * Returns the structured violations that triggered this exception.
     *
     * @return the violations aggregate; never null
     */
    public WorkflowDefinitionViolations violations() {
        return violations;
    }
}
