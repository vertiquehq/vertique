// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import dev.vertique.workflow.exception.WorkflowIntakeRefusedException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Tells a refusal by the intake limit from every other failure of a start.
 *
 * <p>The driver counts the two apart: a refusal is the limit working as designed and its key is replayed later, any
 * other failure is a result of the system under measurement. Getting the split wrong would turn one into the other
 * in the numbers, so it lives in one small class with its own tests.
 */
public final class Refusals {

    private static final int MAX_DEPTH = 16;

    private Refusals() {}

    /**
     * Returns whether a failure is, or was caused by, a refusal from the intake limit.
     *
     * @param failure the failure of a start; may be {@code null}
     * @return {@code true} when the failure or one of its causes is a {@link WorkflowIntakeRefusedException}
     */
    public static boolean isIntakeRefusal(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_DEPTH && seen.add(current); depth++) {
            if (current instanceof WorkflowIntakeRefusedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
