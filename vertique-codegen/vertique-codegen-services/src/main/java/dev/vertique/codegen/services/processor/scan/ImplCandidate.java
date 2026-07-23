// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.scan;

import javax.lang.model.element.TypeElement;

/**
 * A concrete implementation type that has been classified as a {@link ServiceContract} candidate
 * by {@link ImplCandidateScanner}.
 *
 * <p>Records hold two elements:
 * <ul>
 *   <li>{@code kind} — how the impl relates to the contract ({@link ImplKind#DIRECT},
 *       {@link ImplKind#HANDLER}, or {@link ImplKind#DOUBLE_PATTERN}).</li>
 *   <li>{@code implType} — the concrete implementation class being compiled.</li>
 *   <li>{@code contractType} — the {@code @ServiceContract}-annotated interface the impl
 *       is bound to.</li>
 * </ul>
 *
 * @param kind         the classification of this candidate
 * @param implType     the concrete implementation type element; never {@code null}
 * @param contractType the {@code @ServiceContract}-annotated interface; never {@code null}
 */
public record ImplCandidate(ImplKind kind, TypeElement implType, TypeElement contractType) {

    /**
     * Classifies how a concrete implementation relates to its service contract.
     */
    public enum ImplKind {
        /** The impl directly implements the {@code @ServiceContract} interface. */
        DIRECT,

        /**
         * The impl implements {@code ServiceHandler<C>} where {@code C} carries
         * {@code @ServiceContract}.
         */
        HANDLER,

        /**
         * The impl satisfies BOTH patterns simultaneously — it implements
         * {@code ServiceHandler<C>} AND directly implements {@code C}. This is a structural
         * error and must be rejected by validation.
         */
        DOUBLE_PATTERN
    }
}
