// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static dev.vertique.rest.openapi.docs.ContractReferences.child;
import static dev.vertique.rest.openapi.docs.ContractReferences.display;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.rest.jaxrs.publication.InputKey;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses a served contract that describes an input the routed operation hides.
 *
 * <p>An Operation Object is checked against the hidden inputs of the routed operation its {@code
 * operationId} names. Every Parameter Object of the operation and of the Path Items that hold or
 * reference it, local references followed, is refused when its {@code in} and {@code name} match a
 * hidden input at that location: {@code path} and {@code query} names exactly, {@code header} and
 * {@code cookie} names ignoring ASCII case, as the runtime binds them. A referenced parameter is
 * named at its own location.
 *
 * <p>For each {@code application/x-www-form-urlencoded} or {@code multipart/form-data} media type
 * of its request body (the media type compared without parameters, ignoring ASCII case), the schema,
 * local references followed, must be a plain object schema: no node of the reference chain carries
 * an applicator or property-expansion keyword, and the last one has a {@code properties} object. A
 * property named like a hidden form input is refused, in any node of the chain.
 *
 * <p>A Link Object is checked against the hidden inputs of the routed operation it names: each key
 * of its {@code parameters}, read with an optional {@code path.}, {@code query.}, {@code header.},
 * or {@code cookie.} qualifier, is refused when it names a hidden input at the qualified location,
 * or at any parameter location when unqualified; the whole key is also read as an unqualified name.
 * When the operation has a hidden form input, any {@code requestBody} of the Link is refused.
 *
 * <p>Every refusal names the operation id and the JSON Pointer of the parameter, property, schema,
 * or Link member, never its content.
 */
final class ServedContractInputs {

    /** The keywords that make a form schema something other than a plain object schema. */
    private static final List<String> NOT_PLAIN_KEYWORDS = List.of(
            "allOf",
            "anyOf",
            "oneOf",
            "not",
            "if",
            "then",
            "else",
            "dependentSchemas",
            "patternProperties",
            "propertyNames",
            "additionalProperties",
            "unevaluatedProperties");

    /** The parameter locations a Parameter Object's {@code in} or a Link parameter qualifier names. */
    private static final Map<String, ParamLocation> LOCATIONS = Map.of(
            "path", ParamLocation.PATH,
            "query", ParamLocation.QUERY,
            "header", ParamLocation.HEADER,
            "cookie", ParamLocation.COOKIE);

    private final ContractReferences references;
    private final Set<String> violations;

    /**
     * Creates the hidden-input checks of one contract.
     *
     * @param references the contract's reference reader
     * @param violations the sink every refusal is recorded in
     */
    ServedContractInputs(ContractReferences references, Set<String> violations) {
        this.references = references;
        this.violations = violations;
    }

    /**
     * Checks an Operation Object against the hidden inputs of its routed operation.
     *
     * @param operationPointer the Operation Object's JSON Pointer
     * @param operation the Operation Object
     * @param pathItems the Path Items whose {@code parameters} apply to it, each with its JSON Pointer
     * @param twin the routed operation its {@code operationId} names
     */
    void checkOperation(
            String operationPointer, JsonNode operation, List<ContractReferences.Hop> pathItems, RoutedOperation twin) {
        checkParameters(child(operationPointer, "parameters"), operation.get("parameters"), twin);
        for (ContractReferences.Hop pathItem : pathItems) {
            checkParameters(
                    child(pathItem.pointer(), "parameters"), pathItem.node().get("parameters"), twin);
        }
        JsonNode requestBody = operation.get("requestBody");
        if (requestBody != null) {
            checkRequestBody(child(operationPointer, "requestBody"), requestBody, twin);
        }
    }

    /**
     * Checks a Link Object against the hidden inputs of the routed operation it names.
     *
     * @param linkPointer the Link Object's JSON Pointer
     * @param link the Link Object
     * @param twin the routed operation its {@code operationId} names
     */
    void checkLink(String linkPointer, JsonNode link, RoutedOperation twin) {
        String id = display(twin.operationId());
        JsonNode parameters = link.get("parameters");
        if (parameters != null && parameters.isObject()) {
            for (Map.Entry<String, JsonNode> entry : parameters.properties()) {
                String key = entry.getKey();
                if (namesHiddenInput(key, twin)) {
                    violations.add("link parameter " + display(child(child(linkPointer, "parameters"), key))
                            + " naming operation '" + id + "' describes a hidden input");
                }
            }
        }
        if (link.has("requestBody") && hasHiddenForm(twin)) {
            violations.add("member " + display(child(linkPointer, "requestBody"))
                    + " is a link request body naming operation '" + id + "', which has a hidden form input");
        }
    }

    /** Checks each Parameter Object of a {@code parameters} array. */
    private void checkParameters(String arrayPointer, JsonNode parameters, RoutedOperation twin) {
        if (parameters == null || !parameters.isArray()) {
            return;
        }
        for (int i = 0; i < parameters.size(); i++) {
            ContractReferences.Chain chain =
                    references.follow(child(arrayPointer, Integer.toString(i)), parameters.get(i));
            if (!chain.complete()) {
                continue;
            }
            ContractReferences.Hop parameter = chain.last();
            JsonNode in = parameter.node().get("in");
            JsonNode name = parameter.node().get("name");
            if (in == null || !in.isTextual() || name == null || !name.isTextual()) {
                continue;
            }
            ParamLocation location = LOCATIONS.get(ResponseAssembler.asciiLowerCase(in.textValue()));
            if (location != null && matchesHidden(location, name.textValue(), twin)) {
                violations.add("parameter " + display(parameter.pointer()) + " of operation '"
                        + display(twin.operationId()) + "' describes a hidden input");
            }
        }
    }

    /** Checks the form media types of a request body. */
    private void checkRequestBody(String pointer, JsonNode requestBody, RoutedOperation twin) {
        ContractReferences.Chain chain = references.follow(pointer, requestBody);
        if (!chain.complete()) {
            return;
        }
        ContractReferences.Hop body = chain.last();
        JsonNode content = body.node().get("content");
        if (content == null || !content.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> media : content.properties()) {
            JsonNode schema = media.getValue().get("schema");
            if (isForm(media.getKey()) && schema != null) {
                String mediaPointer = child(child(body.pointer(), "content"), media.getKey());
                checkFormSchema(child(mediaPointer, "schema"), schema, twin);
            }
        }
    }

    /** Checks that a form schema is a plain object schema without a hidden form input property. */
    private void checkFormSchema(String pointer, JsonNode schema, RoutedOperation twin) {
        String id = display(twin.operationId());
        ContractReferences.Chain chain = references.follow(pointer, schema);
        for (ContractReferences.Hop hop : chain.hops()) {
            JsonNode node = hop.node();
            boolean plain = node.isObject();
            if (plain) {
                plain = NOT_PLAIN_KEYWORDS.stream().noneMatch(node::has);
                JsonNode properties = node.get("properties");
                if (properties != null && properties.isObject()) {
                    for (Map.Entry<String, JsonNode> property : properties.properties()) {
                        if (matchesHidden(ParamLocation.FORM, property.getKey(), twin)) {
                            violations.add("property "
                                    + display(child(child(hop.pointer(), "properties"), property.getKey()))
                                    + " of operation '" + id + "' describes a hidden form input");
                        }
                    }
                } else if (properties != null || (hop == chain.last() && chain.complete())) {
                    // A non-object properties member, or a resolved chain whose last schema has none.
                    plain = false;
                }
            }
            if (!plain) {
                violations.add("form request-body schema " + display(hop.pointer()) + " of operation '" + id
                        + "' is not a plain object schema");
            }
        }
    }

    /** Tells whether a Link parameter key names a hidden input of the operation. */
    private static boolean namesHiddenInput(String key, RoutedOperation twin) {
        int dot = key.indexOf('.');
        if (dot > 0) {
            ParamLocation qualified = LOCATIONS.get(key.substring(0, dot));
            if (qualified != null && matchesHidden(qualified, key.substring(dot + 1), twin)) {
                return true;
            }
        }
        for (ParamLocation location : LOCATIONS.values()) {
            if (matchesHidden(location, key, twin)) {
                return true;
            }
        }
        return false;
    }

    /** Tells whether a name at a location matches a hidden input of the operation. */
    private static boolean matchesHidden(ParamLocation location, String name, RoutedOperation twin) {
        for (InputKey hidden : twin.hiddenInputs()) {
            if (hidden.location() != location) {
                continue;
            }
            boolean caseInsensitive = location == ParamLocation.HEADER || location == ParamLocation.COOKIE;
            if (caseInsensitive
                    ? asciiEqualsIgnoreCase(hidden.name(), name)
                    : hidden.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Tells whether the operation has a hidden form input. */
    private static boolean hasHiddenForm(RoutedOperation twin) {
        for (InputKey hidden : twin.hiddenInputs()) {
            if (hidden.location() == ParamLocation.FORM) {
                return true;
            }
        }
        return false;
    }

    /** Tells whether a media type, without its parameters and ignoring ASCII case, is a form type. */
    private static boolean isForm(String mediaType) {
        int semicolon = mediaType.indexOf(';');
        String type = ResponseAssembler.asciiLowerCase(
                (semicolon < 0 ? mediaType : mediaType.substring(0, semicolon)).strip());
        return type.equals("application/x-www-form-urlencoded") || type.equals("multipart/form-data");
    }

    /** Compares two strings ignoring the case of ASCII letters only. */
    private static boolean asciiEqualsIgnoreCase(String a, String b) {
        return a.length() == b.length()
                && ResponseAssembler.asciiLowerCase(a).equals(ResponseAssembler.asciiLowerCase(b));
    }
}
