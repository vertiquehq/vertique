// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

/**
 * The resource {@code /d} of {@link HiddenOperationsApi}: implements {@link PartlyHiddenContract},
 * the interface that carries every annotation.
 */
public class PartlyHiddenContractResource implements PartlyHiddenContract {

    /** Creates the resource. */
    public PartlyHiddenContractResource() {}

    @Override
    public void hiddenRead() {
        // Nothing to do: a request only proves the route still answers.
    }

    @Override
    public void visibleRead() {
        // Nothing to do: the operation only has to be published.
    }
}
