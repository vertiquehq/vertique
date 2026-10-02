// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.metadata;

import static dev.vertique.rest.openapi.docs.metadata.AnnotationValues.isSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tells which attributes of a declared {@link ApiResponse} a document publishes, and renders the
 * documentation an implemented {@link Schema} publishes beside the reference to its component.
 *
 * <p>Published are {@link ApiResponse}'s {@code description}, {@code responseCode}, {@code
 * headers}, {@code content}, {@code useReturnTypeSchema}, and its extensions whose names start with
 * {@code x-}; {@link Header}'s {@code name}, {@code description}, {@code required}, {@code
 * deprecated}, and {@code schema}; {@link Content}'s {@code mediaType}, {@code schema}, {@code
 * array}, and {@code examples}; an {@link ArraySchema}'s {@code schema}; every member of an {@link
 * ExampleObject} but its extensions; and of a {@link Schema} with an {@code implementation}, that
 * implementation and its documentation members {@code description}, {@code title}, {@code example},
 * {@code deprecated}, and {@code externalDocs} (with a non-blank {@code url}).
 *
 * <p>Every other member that differs from its default is not published and is named as {@code
 * @<Annotation>.<member>}: among others {@code @ApiResponse.ref}, {@code @ApiResponse.links}, {@code
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 *
 * @Content.encoding}, every member of a {@link Schema} without an {@code implementation}, every
 * member of an {@link ArraySchema} but {@code schema}, and the whole array of a content whose {@code
 * schema} is implemented. An extension whose name does not start with {@code x-} is named {@code
 * @ApiResponse.extensions}, without its name, and a header with a blank name {@code @Header.name}.
 * Members are compared with their defaults reflectively and visited in name order, so the names are
 * deterministic; no value is ever quoted.
 */
public final class ResponseAttributes {

    /** The warning kind of an operation's omitted response attributes, followed by its operation id. */
    public static final String WARNING_KIND = "response-attributes:";

    private static final Set<String> RESPONSE_HONORED =
            Set.of("description", "responseCode", "headers", "content", "extensions", "useReturnTypeSchema");

    private static final Set<String> HEADER_HONORED = Set.of("name", "description", "required", "deprecated", "schema");

    private static final Set<String> CONTENT_HONORED = Set.of("mediaType", "schema", "array", "examples");

    private static final Set<String> ARRAY_HONORED = Set.of("schema");

    private static final Set<String> SCHEMA_HONORED =
            Set.of("implementation", "description", "title", "example", "deprecated", "externalDocs");

    private static final Method SCHEMA_EXTERNAL_DOCS = member(Schema.class, "externalDocs");

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ResponseAttributes() {}

    /**
     * Names the attributes of one declared response the document does not publish: the response's
     * own members first, then each header's, then each content's, each name once, in that order.
     *
     * @param response the declared response
     * @return the attribute names, for example {@code @ApiResponse.links}; empty when every set
     *     attribute is published
     */
    public static Set<String> omitted(ApiResponse response) {
        Set<String> omitted = new LinkedHashSet<>();
        unpublishedMembers(response, "ApiResponse", RESPONSE_HONORED, omitted);
        for (Extension extension : response.extensions()) {
            if (!extension.name().startsWith("x-")) {
                omitted.add("@ApiResponse.extensions");
            }
        }
        for (Header header : response.headers()) {
            if (!isSet(header.name())) {
                omitted.add("@Header.name");
                continue;
            }
            unpublishedMembers(header, "Header", HEADER_HONORED, omitted);
            schema(header.schema(), omitted);
        }
        for (Content content : response.content()) {
            unpublishedMembers(content, "Content", CONTENT_HONORED, omitted);
            schema(content.schema(), omitted);
            boolean arrayPublished = !implemented(content.schema().implementation())
                    && implemented(content.array().schema().implementation());
            array(content.array(), arrayPublished, omitted);
            for (ExampleObject example : content.examples()) {
                if (example.extensions().length > 0) {
                    omitted.add("@ExampleObject.extensions");
                }
            }
        }
        return omitted;
    }

    /**
     * Builds the warning naming every omitted response attribute of one operation.
     *
     * @param documentName the document's application name
     * @param operationId the runtime id of the operation
     * @param mountPath the mount path of the application
     * @param omitted the omitted attribute names of each status, in published status order
     * @return the warning message, starting with the document's configuration path; {@code null} when
     *     no attribute is omitted
     */
    @Nullable
    public static String warning(
            String documentName, String operationId, String mountPath, Map<String, Set<String>> omitted) {
        StringBuilder listed = new StringBuilder();
        for (Map.Entry<String, Set<String>> status : omitted.entrySet()) {
            for (String attribute : status.getValue()) {
                if (!listed.isEmpty()) {
                    listed.append(", ");
                }
                listed.append(attribute).append(" on status ").append(status.getKey());
            }
        }
        if (listed.isEmpty()) {
            return null;
        }
        return "apidocs.documents." + documentName + ": operation '" + operationId + "' at mount '" + mountPath
                + "' declares response attributes the document does not publish: " + listed;
    }

    /**
     * Writes a reference to a component together with the documentation members of the {@link Schema}
     * that names the component's implementation, in canonical key order.
     *
     * @param reference the reference to the component
     * @param schema the annotation whose implementation the component describes
     * @return a new Schema Object holding the reference and the set documentation members: {@code
     *     description}, {@code title}, {@code example} (as JSON when it parses, else as a string),
     *     {@code deprecated} when true, and {@code externalDocs} when its {@code url} is set
     */
    public static ObjectNode documented(JsonNode reference, Schema schema) {
        Map<String, JsonNode> members = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = reference.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            members.put(field.getKey(), field.getValue());
        }
        if (implemented(schema.implementation())) {
            if (isSet(schema.description())) {
                members.put("description", NODES.textNode(schema.description()));
            }
            if (isSet(schema.title())) {
                members.put("title", NODES.textNode(schema.title()));
            }
            if (isSet(schema.example())) {
                members.put("example", Examples.value(schema.example()));
            }
            if (schema.deprecated()) {
                members.put("deprecated", NODES.booleanNode(true));
            }
            ObjectNode externalDocs = OperationMetadata.externalDocs(schema.externalDocs());
            if (externalDocs != null) {
                members.put("externalDocs", externalDocs);
            }
        }
        ObjectNode node = NODES.objectNode();
        members.forEach(node::set);
        return node;
    }

    /**
     * Whether a {@code @Schema.implementation} member is set; its default is {@code Void}.
     *
     * @param implementation the member value
     * @return {@code true} when it names a type other than {@code Void}
     */
    public static boolean implemented(Class<?> implementation) {
        return implementation != null && implementation != Void.class;
    }

    /** Names the unpublished members of a schema: all set ones without an implementation. */
    private static void schema(Schema schema, Set<String> omitted) {
        if (!implemented(schema.implementation())) {
            unpublishedMembers(schema, "Schema", Set.of(), omitted);
            return;
        }
        unpublishedMembers(schema, "Schema", SCHEMA_HONORED, omitted);
        blank(schema.description(), "description", omitted);
        blank(schema.title(), "title", omitted);
        blank(schema.example(), "example", omitted);
        if (OperationMetadata.externalDocs(schema.externalDocs()) == null && !isDefault(schema, SCHEMA_EXTERNAL_DOCS)) {
            omitted.add("@Schema.externalDocs");
        }
    }

    /** Names a documentation member that is set to blank text, which publishes nothing. */
    private static void blank(String value, String member, Set<String> omitted) {
        if (!value.isEmpty() && !isSet(value)) {
            omitted.add("@Schema." + member);
        }
    }

    /** Names the unpublished members of an array schema; an unpublished array publishes none. */
    private static void array(ArraySchema array, boolean published, Set<String> omitted) {
        if (!published) {
            unpublishedMembers(array, "ArraySchema", Set.of(), omitted);
            return;
        }
        unpublishedMembers(array, "ArraySchema", ARRAY_HONORED, omitted);
        schema(array.schema(), omitted);
    }

    /** Adds each member, in name order, that is not honored and differs from its default. */
    private static void unpublishedMembers(
            Annotation annotation, String label, Set<String> honored, Set<String> omitted) {
        Method[] members = annotation.annotationType().getDeclaredMethods();
        Arrays.sort(members, Comparator.comparing(Method::getName));
        for (Method member : members) {
            if (honored.contains(member.getName())) {
                continue;
            }
            if (!isDefault(annotation, member)) {
                omitted.add("@" + label + "." + member.getName());
            }
        }
    }

    /** Whether a member equals its default; a member without a default never does. */
    private static boolean isDefault(Annotation annotation, Method member) {
        return member.getDefaultValue() != null && InputDocumentation.isDefault(annotation, member);
    }

    /** Looks up an annotation member by name. */
    private static Method member(Class<? extends Annotation> type, String name) {
        try {
            return type.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Annotation " + type.getName() + " has no member " + name, e);
        }
    }
}
