// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.adapter;

/** Framework-adapter predicate selecting which final failures count against a breaker. */
@FunctionalInterface
public interface CircuitFailureClassifier {

    /**
     * Returns whether the final logical failure represents a dependency failure.
     *
     * @param finalFailure final logical adapter failure
     * @return {@code true} when the breaker should count the failure
     */
    boolean countsAsFailure(Throwable finalFailure);
}
