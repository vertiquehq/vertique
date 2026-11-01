// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.workflow.exception.WorkflowIntakeRefusedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the one decision that splits refusals from failures in the driver's counts. */
class RefusalsTest {

    @Test
    @DisplayName("a refusal is recognised directly and through its causes")
    void recognisesRefusals() {
        assertTrue(Refusals.isIntakeRefusal(new WorkflowIntakeRefusedException()));
        assertTrue(Refusals.isIntakeRefusal(
                new RuntimeException(new IllegalStateException("wrapped", new WorkflowIntakeRefusedException()))));
    }

    @Test
    @DisplayName("any other failure is not a refusal")
    void otherFailuresAreNotRefusals() {
        assertFalse(Refusals.isIntakeRefusal(new IllegalStateException("database stall")));
        assertFalse(Refusals.isIntakeRefusal(new RuntimeException(new java.io.IOException("reset"))));
        assertFalse(Refusals.isIntakeRefusal(null));
    }

    @Test
    @DisplayName("a cause cycle ends the walk")
    void causeCycleEndsTheWalk() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertFalse(Refusals.isIntakeRefusal(a));
    }

    @Test
    @DisplayName("a refusal buried deeper than the walk limit is not found")
    void depthIsBounded() {
        Throwable chain = new WorkflowIntakeRefusedException();
        for (int i = 0; i < 40; i++) {
            chain = new RuntimeException("level " + i, chain);
        }

        assertFalse(Refusals.isIntakeRefusal(chain));
    }
}
