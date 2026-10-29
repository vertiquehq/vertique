// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * A registry change the lifecycle refused or failed to commit.
 *
 * @param attempted the change that was attempted
 * @param reason    the typed refusal, or {@code null} for any other failure
 * @param errorType the simple class name of the failure, never its message
 */
public record DefinitionLifecycleFailure(
        DefinitionLifecycleChange attempted,
        @Nullable WorkflowDefinitionLifecycleException.Reason reason,
        String errorType) {

    /** Validates the required components. */
    public DefinitionLifecycleFailure {
        Objects.requireNonNull(attempted, "attempted");
        Objects.requireNonNull(errorType, "errorType");
    }
}
