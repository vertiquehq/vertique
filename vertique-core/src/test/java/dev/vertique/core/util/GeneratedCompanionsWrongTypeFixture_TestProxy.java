// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

/**
 * Hand-written companion for {@link GeneratedCompanionsWrongTypeFixture} that is present on the
 * classpath with a matching single-arg constructor but <em>deliberately does not implement</em> the
 * fixture interface. {@link GeneratedCompanions#instantiate} loads and constructs it successfully,
 * then fails at {@code origin.cast(...)} with a {@link ClassCastException} — exercising the
 * present-but-wrong-type branch of the broken-companion policy.
 *
 * <p>The class name matches what {@link GeneratedNames#companionFqn(Class, String)} produces for
 * {@code (GeneratedCompanionsWrongTypeFixture.class, "_TestProxy")}.
 *
 * @see GeneratedCompanionsWrongTypeFixture
 * @see GeneratedCompanionsTest
 */
public final class GeneratedCompanionsWrongTypeFixture_TestProxy {

    /**
     * Creates a new stand-in instance. Intentionally not implementing the fixture interface.
     *
     * @param ignored a placeholder argument to match the single-arg constructor path
     */
    public GeneratedCompanionsWrongTypeFixture_TestProxy(String ignored) {
        // No-op stand-in; the cast to the origin interface fails before any method is called.
    }
}
