// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

/**
 * Hand-written stand-in for a generated companion of {@link GeneratedCompanionFixture}, used to
 * verify that {@link GeneratedCompanions#instantiate} locates and instantiates a present companion.
 *
 * <p>The class name ({@code GeneratedCompanionFixture_TestProxy}) matches what
 * {@link GeneratedNames#companionFqn(Class, String)} produces for
 * {@code (GeneratedCompanionFixture.class, "_TestProxy")}.
 *
 * @see GeneratedCompanionFixture
 * @see GeneratedCompanionsTest
 */
public final class GeneratedCompanionFixture_TestProxy implements GeneratedCompanionFixture {

    /** Sentinel value returned by {@link #marker()} to confirm this stand-in was instantiated. */
    public static final String SENTINEL = "generated-companion-test-proxy";

    /**
     * Creates a new stand-in instance.
     *
     * @param ignored a placeholder argument to exercise the single-arg constructor path
     */
    public GeneratedCompanionFixture_TestProxy(String ignored) {
        // No-op stand-in.
    }

    /**
     * Returns {@link #SENTINEL} to confirm this stand-in was instantiated.
     *
     * @return the sentinel string
     */
    @Override
    public String marker() {
        return SENTINEL;
    }
}
