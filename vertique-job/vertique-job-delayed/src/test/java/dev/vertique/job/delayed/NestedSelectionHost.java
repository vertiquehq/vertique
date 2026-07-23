// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

/** Host for a nested {@code @DelayedJobContract} fixture exercising flattened companion-name selection. */
interface NestedSelectionHost {

    /** Nested contract whose generated companion flattens to {@code NestedSelectionHost_NestedJob_DelayedJobProxy}. */
    @DelayedJobContract(name = "nested-selection")
    interface NestedJob extends DelayedJobClient<String> {}
}
