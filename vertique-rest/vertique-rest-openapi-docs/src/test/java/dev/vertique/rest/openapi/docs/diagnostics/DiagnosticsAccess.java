// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.diagnostics;

/**
 * Test access to package-private parts of the diagnostics package for tests that live outside it.
 * Each method delegates to the production member; nothing is reimplemented here.
 */
public final class DiagnosticsAccess {

    private DiagnosticsAccess() {}

    /**
     * Creates a fresh warning guard, as the component's binding does.
     *
     * @return a guard that has logged nothing yet
     */
    public static DocumentWarnings documentWarnings() {
        return new DocumentWarnings();
    }
}
