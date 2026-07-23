// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

/**
 * Top-level fixture interface whose companion is present on the classpath but does <em>not</em>
 * implement it, so {@code origin.cast(...)} in {@link GeneratedCompanions#instantiate} raises a
 * {@link ClassCastException}. Used to verify that a present-but-wrong-type companion is routed to
 * {@code onBroken} (present-but-broken) rather than escaping raw.
 *
 * @see GeneratedCompanionsWrongTypeFixture_TestProxy
 */
public interface GeneratedCompanionsWrongTypeFixture {

    /**
     * Returns a marker string; never actually invoked because instantiation fails at the cast step.
     *
     * @return the marker string
     */
    String marker();
}
