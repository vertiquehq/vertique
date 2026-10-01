// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.rest.core.security.SecuritySchemeDescription;
import java.util.Objects;

/** Renders a security scheme description as an OpenAPI 3.1.1 Security Scheme Object. */
final class SecuritySchemeRenderer {

    private SecuritySchemeRenderer() {}

    /**
     * Renders the description as a Security Scheme Object.
     *
     * @param description the scheme description
     * @return the Security Scheme Object
     */
    static ObjectNode render(SecuritySchemeDescription description) {
        Objects.requireNonNull(description, "description");
        return JsonNodeFactory.instance.objectNode();
    }
}
