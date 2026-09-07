// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

/**
 * Thrown by {@link InvocationPolicyResolver} when one {@link InvocationPolicySource} declares both
 * an additive chain and a skip flag on the same {@link PolicyAxis}.
 *
 * <p>An override may replace an inherited policy but never remove one: declaring both the additive
 * and the skip annotation on the same hierarchy-merged element is therefore always a configuration
 * error, never a resolvable precedence — one of the two annotations must be deleted.
 */
public final class InvocationPolicyConflictException extends IllegalStateException {

    private final PolicyAxis axis;
    private final String elementDescription;

    /**
     * Constructs the exception, building its message from the conflicting axis and both declaration
     * sites.
     *
     * @param axis               the axis on which the conflict occurred
     * @param elementDescription the human-readable description of the conflicting element
     * @param additiveDeclaredAt the additive annotation's declaration site
     * @param skipDeclaredAt     the skip annotation's declaration site
     */
    public InvocationPolicyConflictException(
            PolicyAxis axis, String elementDescription, String additiveDeclaredAt, String skipDeclaredAt) {
        super(("Conflicting %s (declared on %s) and %s (declared on %s) for %s — an override cannot remove "
                        + "an inherited policy; remove one of the annotations.")
                .formatted(
                        axis.additiveAnnotation(),
                        additiveDeclaredAt,
                        axis.skipAnnotation(),
                        skipDeclaredAt,
                        elementDescription));
        this.axis = axis;
        this.elementDescription = elementDescription;
    }

    /**
     * The axis on which the conflict occurred.
     *
     * @return the conflicting axis
     */
    public PolicyAxis axis() {
        return axis;
    }

    /**
     * The human-readable description of the conflicting element.
     *
     * @return the element description
     */
    public String elementDescription() {
        return elementDescription;
    }
}
