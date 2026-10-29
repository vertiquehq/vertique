// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

/**
 * Observes changes to the definition registry: publishing, deprecating, reinstating and retiring a version, and
 * activating a default.
 *
 * <p>Contribute an implementation with {@code @Provides @IntoSet}. Every contributed listener receives every event,
 * synchronously and in no particular order relative to other listeners. A listener must not block. Events carry no
 * definition source or payload.
 *
 * <p>Callbacks run on the thread that completes the operation, so a callback must not block that thread.
 *
 * <p>Delivery is at most once: if the process stops between the commit and the notification, the event is not
 * delivered.
 */
public interface WorkflowDefinitionLifecycleListener {

    /**
     * Receives a registry change after its durable commit. A repeated request that changes nothing is still reported,
     * because the registry result cannot tell a change from a no-op.
     * An {@link Exception} thrown by this callback is caught, logged, and swallowed; it does not affect the
     * enclosing operation or the other listeners. An {@link Error} is not caught.
     *
     * @param change the committed change
     */
    void onDefinitionLifecycleChange(DefinitionLifecycleChange change);

    /**
     * Receives a registry change the lifecycle refused or failed to commit.
     * An {@link Exception} thrown by this callback is caught, logged, and swallowed; it does not affect the
     * enclosing operation or the other listeners. An {@link Error} is not caught.
     *
     * @param failure the attempted change and why it did not happen
     */
    default void onDefinitionLifecycleFailed(DefinitionLifecycleFailure failure) {}
}
