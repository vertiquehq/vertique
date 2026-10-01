// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

/**
 * The resource {@code /c} of {@link HiddenOperationsApi}: implements {@link HiddenContract}, the
 * interface that carries every annotation, including {@code @Hidden}. Its generated-path twin is
 * {@link GeneratedHiddenContractResource}.
 */
public class HiddenContractResource implements HiddenContract {

    /** Creates the resource. */
    public HiddenContractResource() {}

    @Override
    public void read() {
        // Nothing to do: a request only proves the route still answers.
    }
}
