// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import java.util.function.Supplier;

/**
 * Package-private seam that binds the durable context of one instance-owned drive and runs it. A caller of the engine's
 * timer and task callbacks binds the ambient context with the instance's own; an executor segment binds the context its
 * timer recorded, without an identity.
 */
@FunctionalInterface
interface DriveBinding {

    /**
     * Binds the context and runs the drive inside it.
     *
     * @param instance the instance the drive belongs to
     * @param drive    the drive
     * @param <T>      the drive's result type
     * @return the future of the drive
     */
    <T> Future<T> run(WorkflowInstance instance, Supplier<Future<T>> drive);
}
