// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

/**
 * Generated-proxy stand-in whose constructor always throws, used to verify that
 * {@link GeneratedCompanions#instantiate} invokes {@code onBroken} rather than silently swallowing
 * the error or falling back to an alternative path.
 *
 * <p>The class name ({@code GeneratedCompanionsBrokenFixture_TestProxy}) matches what
 * {@link GeneratedNames#companionFqn(Class, String)} produces for
 * {@code (GeneratedCompanionsBrokenFixture.class, "_TestProxy")}.
 *
 * @see GeneratedCompanionsBrokenFixture
 * @see GeneratedCompanionsTest
 */
public final class GeneratedCompanionsBrokenFixture_TestProxy implements GeneratedCompanionsBrokenFixture {

    /**
     * Always throws to simulate a broken generated class.
     *
     * @param ignored a placeholder argument (constructor throws before any field is set)
     * @throws IllegalStateException unconditionally, to trigger the loud-fail path in
     *     {@link GeneratedCompanions#instantiate}
     */
    public GeneratedCompanionsBrokenFixture_TestProxy(String ignored) {
        throw new IllegalStateException("intentionally broken generated companion");
    }

    /**
     * Never reached — the constructor always throws.
     *
     * @return unreachable
     */
    @Override
    public String marker() {
        throw new UnsupportedOperationException("unreachable");
    }
}
