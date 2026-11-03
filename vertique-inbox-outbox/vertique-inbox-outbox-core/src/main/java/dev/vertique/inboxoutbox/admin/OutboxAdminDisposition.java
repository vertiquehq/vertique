// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

/** The outcome an administrative operation had on the entry. */
public enum OutboxAdminDisposition {

    /** The entry was returned to the pending state; produced by {@link OutboxAdminAction#RETRY}. */
    REQUEUED,

    /** The entry was deleted; produced by {@link OutboxAdminAction#DISCARD}. */
    DISCARDED
}
