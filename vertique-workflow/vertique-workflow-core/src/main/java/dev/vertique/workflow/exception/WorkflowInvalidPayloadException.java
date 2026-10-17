// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.core.validation.ViolationCarrier;
import dev.vertique.core.validation.ViolationDetail;
import java.util.List;

/**
 * Thrown when a start or decision payload does not satisfy the schema declared for it.
 *
 * <p>This is a client-class (400) failure in the {@link WorkflowException} hierarchy. It is a
 * {@link ViolationCarrier}: a transport that recognizes carriers renders each {@link
 * ViolationDetail} individually. Every detail is value-free: the {@code path} is an RFC 6901 JSON
 * Pointer, and the {@code type} names the failed keyword, the bean validator's constraint type, or
 * one of {@code decision}, {@code payload} or {@code binding}. No submitted value appears in a
 * detail.
 */
public class WorkflowInvalidPayloadException extends WorkflowException implements ViolationCarrier {

    private final List<ViolationDetail> violations;

    /**
     * Creates an exception carrying the given violations.
     *
     * @param message human-readable description of the failure
     * @param violations the structured violations; copied defensively, must not be {@code null}
     */
    public WorkflowInvalidPayloadException(String message, List<ViolationDetail> violations) {
        super(message);
        this.violations = List.copyOf(violations);
    }

    /**
     * Creates an exception carrying the given violations and an underlying cause.
     *
     * @param message human-readable description of the failure
     * @param violations the structured violations; copied defensively, must not be {@code null}
     * @param cause the underlying exception, or {@code null} when there is none
     */
    public WorkflowInvalidPayloadException(String message, List<ViolationDetail> violations, Throwable cause) {
        super(message, cause);
        this.violations = List.copyOf(violations);
    }

    /**
     * Returns the structured violations carried by this exception.
     *
     * @return an unmodifiable list of violation details; never {@code null}
     */
    @Override
    public List<ViolationDetail> violations() {
        return violations;
    }
}
