// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

/**
 * Top-level fixture interface used by {@link GeneratedCompanionsTest} to exercise the
 * present-but-broken companion path.
 *
 * <p>A companion class {@code GeneratedCompanionsBrokenFixture_TestProxy} exists on the test
 * classpath and always throws in its constructor. {@link GeneratedCompanions#instantiate} must
 * invoke {@code onBroken} rather than silently swallowing the error or falling back.
 *
 * @see GeneratedCompanionsBrokenFixture_TestProxy
 */
public interface GeneratedCompanionsBrokenFixture {

    /**
     * Returns a marker string identifying this fixture instance.
     *
     * @return the marker string
     */
    String marker();
}
