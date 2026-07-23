// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

/**
 * Top-level fixture interface used by {@link GeneratedCompanionsTest}.
 *
 * <p>A companion class {@code GeneratedCompanionFixture_TestProxy} exists on the test classpath so
 * that {@link GeneratedCompanions#instantiate} can resolve and instantiate it during the
 * "present companion" test case.
 *
 * @see GeneratedCompanionFixture_TestProxy
 * @see GeneratedCompanionsBrokenFixture
 */
public interface GeneratedCompanionFixture {

    /**
     * Returns a marker string identifying this fixture instance.
     *
     * @return the marker string
     */
    String marker();
}
