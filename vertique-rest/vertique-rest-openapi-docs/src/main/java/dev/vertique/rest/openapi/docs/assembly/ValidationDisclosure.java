// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.assembly;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.openapi.docs.ApiDocs;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides what a document discloses about how its request inputs are validated, and writes the
 * {@code x-vertique-validation} members that say so.
 *
 * <p>Every document's root {@code x-vertique-validation} names the {@code java.util.regex} pattern
 * dialect first. A public document discloses nothing else. A protected document then adds, in this
 * order:
 *
 * <ul>
 *   <li>{@code strategy}: the id of the mount's request-validation strategy;
 *   <li>{@code inputSchemaSource}: {@code absent} when no operation schema source is bound, {@code
 *       generated} when the bound source is exactly the framework's annotation-driven source (compared
 *       by its runtime class name, so a subclass or wrapper does not count), else {@code custom};
 *   <li>{@code enforcement}: {@code active} for the {@code web-validation} strategy, {@code
 *       disabled} for the {@code none} strategy, else {@code unknown};
 *   <li>{@code reservedNamesRefused: true} only when a reserved name was removed from a published
 *       schema, else absent;
 *   <li>{@code hiddenInputs: true} only when an input of a published operation was left out as
 *       hidden, else absent.
 * </ul>
 *
 * <p>Only a protected document whose strategy is {@code web-validation} marks the inputs no schema
 * guards: such a Parameter Object or request body carries {@code x-vertique-validation:
 * {"enforcedSchema": false}} as its last member. Under any other strategy no input is marked.
 */
final class ValidationDisclosure {

    /** The name of the member that carries the validation disclosure. */
    static final String MEMBER = "x-vertique-validation";

    /** The id of the strategy that validates every input with its captured schema. */
    static final String WEB_VALIDATION = "web-validation";

    /** The runtime class name of the framework's annotation-driven operation schema source. */
    static final String ANNOTATION_SCHEMA_SOURCE = "dev.vertique.rest.validation.AnnotationSchemaSource";

    /** The regular-expression dialect of every pattern a document publishes. */
    static final String PATTERN_DIALECT = "java.util.regex";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final boolean disclosed;
    private final String strategyId;

    /**
     * Creates the disclosure of one document.
     *
     * @param access the access policy of the document
     * @param strategyId the id of the mount's request-validation strategy
     */
    ValidationDisclosure(ApiDocs.Access access, String strategyId) {
        this.disclosed = access == ApiDocs.Access.PROTECTED;
        this.strategyId = Objects.requireNonNull(strategyId, "strategyId");
    }

    /**
     * Returns whether the document marks the inputs no schema guards.
     *
     * @return {@code true} exactly for a protected document under the {@code web-validation}
     *     strategy
     */
    boolean marksInputs() {
        return disclosed && WEB_VALIDATION.equals(strategyId);
    }

    /**
     * Builds the root {@code x-vertique-validation} object of the document.
     *
     * @param context the component's assembly inputs, naming the bound schema source
     * @param tally the disclosure tally of the document's completed assembly
     * @return the root object, its members in the documented order
     */
    ObjectNode root(AssemblyContext context, DisclosureTally tally) {
        ObjectNode root = NODES.objectNode();
        root.put("patternDialect", PATTERN_DIALECT);
        if (!disclosed) {
            return root;
        }
        root.put("strategy", strategyId);
        root.put("inputSchemaSource", inputSchemaSource(context.schemaSource()));
        root.put("enforcement", enforcement(strategyId));
        if (tally.reservedNamesRefused()) {
            root.put("reservedNamesRefused", true);
        }
        if (tally.hiddenInputs()) {
            root.put("hiddenInputs", true);
        }
        return root;
    }

    /**
     * Appends the marker of an input no schema guards as the last member of a Parameter Object or
     * request body.
     *
     * @param node the Parameter Object or request body
     */
    static void markUnenforced(ObjectNode node) {
        node.putObject(MEMBER).put("enforcedSchema", false);
    }

    /** Names the kind of the bound schema source. */
    private static String inputSchemaSource(Optional<OperationSchemaSource> source) {
        if (source.isEmpty()) {
            return "absent";
        }
        return ANNOTATION_SCHEMA_SOURCE.equals(source.get().getClass().getName()) ? "generated" : "custom";
    }

    /** Names the enforcement state of a strategy. */
    private static String enforcement(String strategyId) {
        if (WEB_VALIDATION.equals(strategyId)) {
            return "active";
        }
        return NoneValidationStrategy.ID.equals(strategyId) ? "disabled" : "unknown";
    }
}
