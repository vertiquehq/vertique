// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

/**
 * The generated-path twin of {@link HiddenContractResource}, listed by {@link HiddenGeneratedApi}
 * and described by its hand-written companion {@link
 * GeneratedHiddenContractResource_JaxRsDescriptor}: implements {@link GeneratedHiddenContract}, the
 * interface that carries every annotation, including {@code @Hidden}.
 */
public class GeneratedHiddenContractResource implements GeneratedHiddenContract {

    /** Creates the resource. */
    public GeneratedHiddenContractResource() {}

    @Override
    public void read() {
        // Nothing to do: a request only proves the route still answers.
    }
}
