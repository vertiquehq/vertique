// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

/** Reason that a finite execution budget cannot be calculated. */
public enum ExecutionBudgetUnknownReason {
    /** An arbitrary backoff callback has no declared upper bound. */
    CUSTOM_BACKOFF
}
