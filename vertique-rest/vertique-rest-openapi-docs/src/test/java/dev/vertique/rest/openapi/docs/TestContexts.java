// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import java.util.Optional;
import java.util.Set;

/** Builds {@link AssemblyContext} values for tests, backed by a real profile registry. */
final class TestContexts {

    private TestContexts() {}

    /** Returns a context with no bound schema source. */
    static AssemblyContext noSource() {
        return new AssemblyContext(Optional.empty(), new DefaultJsonMapperProfileRegistry(Set.of()));
    }

    /** Returns a context with the given bound schema source. */
    static AssemblyContext withSource(OperationSchemaSource source) {
        return new AssemblyContext(Optional.of(source), new DefaultJsonMapperProfileRegistry(Set.of()));
    }
}
