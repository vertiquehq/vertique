// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.ops;

/**
 * The operator command a report of {@link OutboxOperationListener} is about. The set of values grows additively: a
 * consumer must have a default branch.
 */
public enum OutboxOperationType {

    /** {@link OutboxOperations#retry}: an entry was brought back for delivery. */
    RETRY,

    /** {@link OutboxOperations#discard}: an entry was dropped so the relay never delivers it. */
    DISCARD
}
