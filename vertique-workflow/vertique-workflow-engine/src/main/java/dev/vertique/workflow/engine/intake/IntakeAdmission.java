// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.intake;

/**
 * Decides whether this node accepts a new start or a new signal from outside.
 *
 * <p>The decision reads node-local state only: it issues no SQL, takes no lock and does not use the caller's
 * transaction, and it may be called from any thread or context.
 */
public interface IntakeAdmission {

    /**
     * Whether the node accepts new starts and signals now.
     *
     * @return {@code true} to accept, {@code false} to refuse with
     *     {@link dev.vertique.workflow.exception.WorkflowIntakeRefusedException}
     */
    boolean admitting();

    /**
     * An admission that always accepts, which is what an engine without a sampler uses.
     *
     * @return the always-admitting admission
     */
    static IntakeAdmission alwaysAdmitting() {
        return AlwaysAdmitting.INSTANCE;
    }

    /** The admission of an engine that runs no sampler. */
    enum AlwaysAdmitting implements IntakeAdmission {
        /** The only instance. */
        INSTANCE;

        @Override
        public boolean admitting() {
            return true;
        }
    }
}
