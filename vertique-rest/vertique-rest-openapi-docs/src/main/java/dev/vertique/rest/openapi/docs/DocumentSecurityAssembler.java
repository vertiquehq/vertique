// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecuritySchemeDescription;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Publishes what a document states about its operations' security: each operation's {@code
 * security}, and the Security Scheme Objects of {@code components.securitySchemes}.
 *
 * <p>An operation's {@code security} holds one Security Requirement Object per requirement set the
 * operation declares, in declared order; each requirement is one member, keyed by the scheme name,
 * whose value lists the requirement's scopes in declared order (empty when it has none). Roles,
 * actions, and application checks are not expressible there and are never published.
 *
 * <p>A scheme is referenced when a requirement of a published operation names it; hidden operations
 * are not published and reference nothing. Each referenced scheme is published, in scheme-name
 * order, from the description its registered {@link SecuritySchemeHandler} returns from {@link
 * SecuritySchemeHandler#openApiDescription()}, which is asked once per referenced scheme and
 * assembly; a registered handler whose scheme no published operation references is not asked. A
 * referenced scheme with no registered handler, with a handler that describes nothing, or with a
 * description of a kind this module cannot render fails the document, naming the first operation in
 * document order that references the scheme. The failure echoes no description content,
 * configuration value, or credential.
 */
final class DocumentSecurityAssembler {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private DocumentSecurityAssembler() {}

    /**
     * Resolves and renders the schemes the operations reference.
     *
     * @param prefix the start of every failure message: the document's configuration path and the
     *     assembly subject
     * @param operations the published operations of the document, in document order
     * @param handlers the registered security scheme handlers
     * @return the Security Scheme Objects of the referenced schemes, keyed and sorted by scheme name;
     *     empty when no operation references a scheme
     * @throws RestConfigurationException when a referenced scheme has no handler, its handler
     *     describes nothing, or its description cannot be rendered
     */
    static SortedMap<String, ObjectNode> schemes(
            String prefix, List<LocatedOperation> operations, Set<SecuritySchemeHandler> handlers) {
        SortedMap<String, LocatedOperation> firstReferences = new TreeMap<>();
        for (LocatedOperation operation : operations) {
            for (SecurityRequirementSet set : operation.publication().securityRequirementSets()) {
                for (SecurityRequirement requirement : set.schemes()) {
                    firstReferences.putIfAbsent(requirement.schemeName(), operation);
                }
            }
        }
        SortedMap<String, ObjectNode> schemes = new TreeMap<>();
        for (Map.Entry<String, LocatedOperation> reference : firstReferences.entrySet()) {
            String schemeName = reference.getKey();
            String requires =
                    prefix + ": " + reference.getValue().label() + " requires security scheme '" + schemeName + "'";
            SecuritySchemeHandler handler = handlers.stream()
                    .filter(candidate -> schemeName.equals(candidate.schemeName()))
                    .findFirst()
                    .orElseThrow(() -> new RestConfigurationException(
                            requires + ", but no SecuritySchemeHandler is registered for it"));
            Optional<SecuritySchemeDescription> description = handler.openApiDescription();
            if (description == null || description.isEmpty()) {
                throw new RestConfigurationException(requires
                        + ", whose SecuritySchemeHandler provides no OpenAPI description; return one from"
                        + " openApiDescription() or disable the document");
            }
            try {
                schemes.put(schemeName, SecuritySchemeRenderer.render(description.get()));
            } catch (IllegalArgumentException unknownKind) {
                throw new RestConfigurationException(requires
                        + ", whose SecuritySchemeHandler describes it as " + unknownKind.getMessage()
                        + ", a kind of security scheme this documentation module cannot publish");
            }
        }
        return schemes;
    }

    /**
     * Renders an operation's {@code security}.
     *
     * @param operation the published operation
     * @return one Security Requirement Object per requirement set, in declared order, or {@code null}
     *     when the operation declares no requirement set
     */
    static ArrayNode security(OperationPublication operation) {
        List<SecurityRequirementSet> sets = operation.securityRequirementSets();
        if (sets.isEmpty()) {
            return null;
        }
        ArrayNode security = NODES.arrayNode();
        for (SecurityRequirementSet set : sets) {
            ObjectNode requirementObject = security.addObject();
            for (SecurityRequirement requirement : set.schemes()) {
                ArrayNode scopes = requirementObject.putArray(requirement.schemeName());
                requirement.scopes().forEach(scopes::add);
            }
        }
        return security;
    }

    /**
     * One published operation with the path key the document lists it under.
     *
     * @param path the published path key
     * @param publication the operation's publication
     */
    record LocatedOperation(String path, OperationPublication publication) {

        LocatedOperation {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(publication, "publication");
        }

        /**
         * Returns the operation's uppercase HTTP method.
         *
         * @return the method, for example {@code GET}
         */
        String method() {
            return publication.httpMethod().toUpperCase(Locale.ROOT);
        }

        /**
         * Returns how a failure message names the operation.
         *
         * @return {@code operation '<id>' (<METHOD> <path>)}
         */
        String label() {
            return "operation '" + publication.operationId() + "' (" + method() + " " + path + ")";
        }
    }
}
