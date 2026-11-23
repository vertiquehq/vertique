// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql.maintenance;

import dev.vertique.inboxoutbox.exception.InboxOutboxTechnicalException;

/**
 * INTERNAL framework seam — the failure the maintenance service reports to the cron tracking
 * record; not an application contract and outside the maturity promise.
 *
 * <p>Composite failure raised by {@link OutboxMaintenanceService#cleanup()} when one or more of the
 * four cleanup operations (published, dead-letter, discarded, inbox) failed.
 *
 * <p>The first underlying failure is wired as the {@link Throwable#getCause() cause}, so log
 * formatters that follow standard cause-chain printing surface at least one repository error
 * by default. Every underlying failure is also attached via {@link Throwable#addSuppressed}.
 */
public final class OutboxMaintenanceException extends InboxOutboxTechnicalException {

    /**
     * Creates a new composite maintenance failure carrying the first underlying cause.
     *
     * @param message human-readable summary including the failed sub-task labels
     * @param cause   the first underlying failure (typically the failed repository call)
     */
    public OutboxMaintenanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
