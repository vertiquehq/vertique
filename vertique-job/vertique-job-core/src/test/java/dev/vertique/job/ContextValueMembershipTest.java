// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link JobContext} and {@link JobDispatchContext} are members of the
 * {@link ContextValue} marker hierarchy, satisfying the compile-time safety and
 * runtime-validation contract described in {@link ContextValue}.
 */
class ContextValueMembershipTest {

    @Test
    @DisplayName("JobContext extends ContextValue")
    void jobContextIsContextValue() {
        assertTrue(ContextValue.class.isAssignableFrom(JobContext.class));
    }

    @Test
    @DisplayName("JobDispatchContext implements ContextValue")
    void jobDispatchContextIsContextValue() {
        assertTrue(ContextValue.class.isAssignableFrom(JobDispatchContext.class));
    }
}
