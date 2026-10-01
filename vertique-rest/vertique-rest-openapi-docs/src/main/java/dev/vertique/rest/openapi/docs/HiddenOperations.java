// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.jaxrs.publication.OperationPublication;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Removes hidden operations from a document before anything of it is rendered, verified, or checked.
 *
 * <p>An operation is hidden when its resolved method annotations or class annotations (resolved
 * through interfaces, interface methods, and superclasses) hold a {@link Hidden} instance, or when
 * any {@link Operation} instance among its resolved method annotations has {@link Operation#hidden()
 * hidden} set. Only direct instances count: an annotation that is itself annotated with {@code
 * @Hidden} does not hide an operation. An operation the documentation sink took no facts for is
 * never hidden. Removal affects the document only: the operation's route still answers.
 */
final class HiddenOperations {

    private HiddenOperations() {}

    /**
     * Returns the operations of a publication that the document lists.
     *
     * @param operations the publication's operations, in publication order
     * @param facts the per-operation descriptor facts, keyed by operation id
     * @return the operations that are not hidden, in their original order
     */
    static List<OperationPublication> visible(
            List<OperationPublication> operations, Map<String, OperationFacts> facts) {
        List<OperationPublication> visible = new ArrayList<>(operations.size());
        for (OperationPublication operation : operations) {
            if (!hidden(facts.get(operation.operationId()))) {
                visible.add(operation);
            }
        }
        return visible;
    }

    /**
     * Tells whether an operation is hidden from the document.
     *
     * @param facts the operation's descriptor facts, or {@code null} when none were taken
     * @return {@code true} when the facts carry a hiding annotation
     */
    static boolean hidden(OperationFacts facts) {
        if (facts == null) {
            return false;
        }
        for (Annotation annotation : facts.methodAnnotations()) {
            if (annotation instanceof Hidden) {
                return true;
            }
            if (annotation instanceof Operation operation && operation.hidden()) {
                return true;
            }
        }
        for (Annotation annotation : facts.classAnnotations()) {
            if (annotation instanceof Hidden) {
                return true;
            }
        }
        return false;
    }
}
