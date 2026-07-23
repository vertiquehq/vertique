// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

/**
 * Test fixture contributor with {@link #priority()} {@code 10}.
 *
 * <p>Always records {@code "beta:contribute"} on contribution and {@code "beta:shutdown"} on
 * shutdown (inherited unconditionally from {@link RecordingContributor}). Returns the builder
 * unchanged.
 *
 * <p>The public no-arg constructor is required for {@link java.util.ServiceLoader} discovery.
 */
public final class OrderedBetaContributor extends RecordingContributor {

    /** Required by {@link java.util.ServiceLoader}. */
    public OrderedBetaContributor() {
        super("beta", 10);
    }
}
