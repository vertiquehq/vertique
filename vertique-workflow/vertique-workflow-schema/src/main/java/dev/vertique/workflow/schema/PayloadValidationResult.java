// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.core.validation.ViolationDetail;
import java.util.List;

/**
 * The outcome of validating one decision or start payload against its registered schema.
 *
 * <p>Each violation names an RFC 6901 pointer into the submitted payload (the empty string for the root), the
 * violated keyword or one of the fixed types {@code definition}, {@code decision}, {@code payload},
 * {@code patternInputLength} and {@code patternInputTotalLength}, and a message that never carries a submitted value
 * or the name of an undeclared property.
 *
 * @param violations the violations, empty when the payload is accepted; copied on construction
 */
public record PayloadValidationResult(List<ViolationDetail> violations) {

    /** Copies the violations so the record cannot be changed through the caller's list. */
    public PayloadValidationResult {
        violations = List.copyOf(violations);
    }

    /**
     * Tells whether the payload was accepted.
     *
     * @return {@code true} when there is no violation
     */
    public boolean valid() {
        return violations.isEmpty();
    }
}
