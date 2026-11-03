// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

/** An administrative operation on a dead-lettered outbox entry. */
public enum OutboxAdminAction {

    /** Return the entry to the pending state so the relay delivers it again. */
    RETRY,

    /** Delete the entry permanently. */
    DISCARD
}
