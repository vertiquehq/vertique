// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

/** Identifies the synchronous policy callback that produced a failure. */
public enum PolicyCallbackKind {
    /** A callback deciding whether another attempt is eligible. */
    RETRY_ELIGIBILITY,
    /** A callback computing or validating a retry backoff. */
    BACKOFF,
    /** A callback recording the outcome of a failed attempt. */
    FAILURE_RECORDING
}
