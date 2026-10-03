// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan;

/** The resource {@code /c}: implements {@link HiddenInterface}, which carries every annotation. */
public class HiddenInterfaceResource implements HiddenInterface {

    /** Creates the resource. */
    public HiddenInterfaceResource() {}

    @Override
    public void read() {
        // Nothing to do: a request only proves the route still answers.
    }
}
