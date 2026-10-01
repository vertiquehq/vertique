// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.rest.core.security.ApiKey;
import dev.vertique.rest.core.security.Http;
import dev.vertique.rest.core.security.MutualTls;
import dev.vertique.rest.core.security.OAuth2;
import dev.vertique.rest.core.security.OAuthFlow;
import dev.vertique.rest.core.security.OAuthFlows;
import dev.vertique.rest.core.security.OpenIdConnect;
import dev.vertique.rest.core.security.SecuritySchemeDescription;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Renders a security scheme description as an OpenAPI 3.1.1 Security Scheme Object.
 *
 * <p>Members are written in the Security Scheme Object's field order: {@code type}, {@code
 * description}, {@code name}, {@code in}, {@code scheme}, {@code bearerFormat}, {@code flows}, and
 * {@code openIdConnectUrl}; an absent optional value omits its member. An OAuth2 description lists
 * its present flows in the order {@code implicit}, {@code password}, {@code clientCredentials},
 * {@code authorizationCode}, each with its URLs and its {@code scopes}, the scopes sorted by name.
 *
 * <p>The description kinds are tested one by one rather than switched over exhaustively, because
 * later releases may add kinds: a kind this renderer does not know is refused.
 */
final class SecuritySchemeRenderer {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private SecuritySchemeRenderer() {}

    /**
     * Renders the description as a Security Scheme Object.
     *
     * @param description the scheme description
     * @return the Security Scheme Object
     * @throws IllegalArgumentException when the description is of a kind this renderer does not know;
     *     the message names only the kind's class
     */
    static ObjectNode render(SecuritySchemeDescription description) {
        Objects.requireNonNull(description, "description");
        ObjectNode node = NODES.objectNode();
        if (description instanceof Http http) {
            start(node, "http", description);
            node.put("scheme", http.scheme());
            http.bearerFormat().ifPresent(format -> node.put("bearerFormat", format));
        } else if (description instanceof ApiKey apiKey) {
            start(node, "apiKey", description);
            node.put("name", apiKey.name());
            node.put("in", apiKey.in().name().toLowerCase(Locale.ROOT));
        } else if (description instanceof OAuth2 oauth2) {
            start(node, "oauth2", description);
            node.set("flows", flows(oauth2.flows()));
        } else if (description instanceof OpenIdConnect openIdConnect) {
            start(node, "openIdConnect", description);
            node.put("openIdConnectUrl", openIdConnect.openIdConnectUrl().toString());
        } else if (description instanceof MutualTls) {
            start(node, "mutualTLS", description);
        } else {
            throw new IllegalArgumentException(description.getClass().getName());
        }
        return node;
    }

    /** Writes the leading {@code type} and, when present, {@code description}. */
    private static void start(ObjectNode node, String type, SecuritySchemeDescription description) {
        node.put("type", type);
        description.description().ifPresent(text -> node.put("description", text));
    }

    /** Renders the OAuth Flows Object: the present flows, in the OpenAPI field order. */
    private static ObjectNode flows(OAuthFlows flows) {
        ObjectNode node = NODES.objectNode();
        flow(node, "implicit", flows.implicit());
        flow(node, "password", flows.password());
        flow(node, "clientCredentials", flows.clientCredentials());
        flow(node, "authorizationCode", flows.authorizationCode());
        return node;
    }

    /**
     * Writes one OAuth Flow Object when the flow is present: its URLs when present, then its scopes,
     * always present and sorted by scope name.
     */
    private static void flow(ObjectNode flows, String name, Optional<OAuthFlow> flow) {
        if (flow.isEmpty()) {
            return;
        }
        OAuthFlow present = flow.get();
        ObjectNode node = flows.putObject(name);
        present.authorizationUrl().ifPresent(url -> node.put("authorizationUrl", url.toString()));
        present.tokenUrl().ifPresent(url -> node.put("tokenUrl", url.toString()));
        present.refreshUrl().ifPresent(url -> node.put("refreshUrl", url.toString()));
        ObjectNode scopes = node.putObject("scopes");
        for (Map.Entry<String, String> scope : new TreeMap<>(present.scopes()).entrySet()) {
            scopes.put(scope.getKey(), scope.getValue());
        }
    }
}
