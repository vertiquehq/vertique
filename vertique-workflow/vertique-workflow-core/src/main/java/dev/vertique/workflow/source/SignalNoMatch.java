// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.source;

/** What a source does with a message whose key matches no waiting instance. */
public enum SignalNoMatch {
    /** The message is finished: acknowledge it without recording a signal. */
    IGNORE,
    /** The message may match later: redeliver it. */
    RETRY
}
