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
 * ViolationDetail} individually. A detail the engine builds for a located failure holds an RFC 6901
 * JSON Pointer into the payload the engine validated, named with the engine's own mapper, so it is
 * relative to that payload and can differ from a pointer into the request body that carried it; a
 * detail that concerns the payload as a whole has no path. The {@code type}
 * names the failed keyword, the bean validator's constraint type, or one of {@code decision},
 * {@code payload} or {@code binding}. Details built by the engine are value-free: no payload value,
 * decision name, class name, decode message or map key. A constraint message produced by an
 * application's constraints passes through unchanged, so applications must not interpolate the
 * validated value into a message.
 *
 * <p>A binding failure keeps the decode exception as the cause. It can carry submitted text, so
 * applications must not log the cause of this exception at INFO or above.
 */
public class WorkflowInvalidPayloadException extends WorkflowException implements ViolationCarrier {

    private final List<ViolationDetail> violations;

    /**
     * Creates an exception carrying the given violations.
     *
     * <p>An application-built instance states each detail's path form itself; a detail built with
     * {@link ViolationDetail#atPointer} must hold a pointer that is {@code ""} or starts with {@code /}
     * and contains no key the client chose.
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
     * <p>An application-built instance states each detail's path form itself; a detail built with
     * {@link ViolationDetail#atPointer} must hold a pointer that is {@code ""} or starts with {@code /}
     * and contains no key the client chose.
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
