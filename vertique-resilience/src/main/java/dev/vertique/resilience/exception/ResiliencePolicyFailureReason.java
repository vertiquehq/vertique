// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.exception;

/** Fixed reasons for policy construction or resolution failure. */
public enum ResiliencePolicyFailureReason {
    /** The supplied policy configuration is invalid. */
    INVALID_CONFIGURATION,
    /** The supplied policy configuration is incomplete. */
    INCOMPLETE_CONFIGURATION
}
