// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan;

/**
 * The resource {@code /d}: implements {@link PartlyHiddenInterface}, which carries every annotation.
 */
public class PartlyHiddenInterfaceResource implements PartlyHiddenInterface {

    /** Creates the resource. */
    public PartlyHiddenInterfaceResource() {}

    @Override
    public void hiddenRead() {
        // Nothing to do: a request only proves the route still answers.
    }

    @Override
    public void visibleRead() {
        // Nothing to do: the operation only has to be published.
    }
}
