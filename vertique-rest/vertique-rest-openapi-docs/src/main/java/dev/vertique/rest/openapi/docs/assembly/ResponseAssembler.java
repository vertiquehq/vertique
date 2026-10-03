// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.assembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.publication.ResponseShape;
import dev.vertique.rest.openapi.docs.config.EnabledDocuments;
import dev.vertique.rest.openapi.docs.diagnostics.PendingWarnings;
import dev.vertique.rest.openapi.docs.metadata.AnnotatedInfo;
import dev.vertique.rest.openapi.docs.metadata.AnnotationValues;
import dev.vertique.rest.openapi.docs.metadata.DeclaredResponses;
import dev.vertique.rest.openapi.docs.metadata.Examples;
import dev.vertique.rest.openapi.docs.metadata.OperationFacts;
import dev.vertique.rest.openapi.docs.metadata.ResponseAttributes;
import dev.vertique.rest.openapi.docs.metadata.ResponseInference;
import dev.vertique.rest.openapi.docs.metadata.ResponseStatuses;
import dev.vertique.rest.openapi.docs.schema.OutputSchemaGeneratorCache;
import dev.vertique.rest.openapi.docs.schema.OutputSchemas;
import dev.vertique.rest.openapi.docs.schema.SchemaEmbedder;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;

/**
 * Plans the {@code responses} of one operation's Operation Object and checks every schema it will
 * publish, before the document is written.
 *
 * <p>The operation's return type is classified by {@link ResponseInference}. Without a declared
 * {@link ApiResponse} (see {@link DeclaredResponses}), the operation publishes the one inferred
 * response: {@code 204} for no content, {@code 200} for a JSON entity (each media type referencing
 * the component {@code <operationId>.response}) or for raw text (each media type without a schema),
 * and else {@code default}. A non-empty declared set replaces the inferred response entirely: each
 * declared status publishes its description, headers, and content, except that a success status
 * declared without content ({@code 200} to {@code 299} other than {@code 204} and {@code 205}, or
 * {@code 2XX}) receives the inferred content when the return type is inferable. A status declaring
 * {@code useReturnTypeSchema} publishes the inferred content unless it declares content itself, and
 * fails publication when the return type is not inferable.
 *
 * <p>Response keys are published numeric codes ascending, then range keys, then {@code default}. A
 * blank description publishes the status's reason phrase ({@link ResponseStatuses}). A Response
 * Object holds {@code description}, {@code headers}, and {@code content}, in that order. A {@link
 * Content} with a blank media type applies to each of the operation's produced media types ({@code
 * application/json} when none is declared); one media type declared twice in one status fails
 * publication. Its {@code schema} implementation is published as a reference to the component
 * {@code <operationId>.response.<status>}, its {@code array} element implementation as an array of
 * such references, and a content with neither publishes its media type without a schema. When one
 * status declares several different implementations, each component key gains the suffix {@code
 * .<n>}, numbered from 1 in declaration order; one implementation used by several media types of a
 * status is one component. A {@link Header} with a blank name is left out, and one header name
 * declared twice in one status, compared ignoring ASCII letter case, fails publication; a header's
 * schema implementation is published as a reference to the component {@code
 * <operationId>.response.<status>.header.<name>}, and a header without one publishes the empty
 * schema.
 *
 * <p>The documentation members of an implemented {@code @Schema} are published beside the reference
 * they document (the content or header schema's reference, or an array's {@code items} reference),
 * never in the component. A Media Type Object holds {@code schema}, then the content's named {@code
 * examples}, checked and rendered as an input's examples are ({@link Examples}); a Response Object's
 * {@code x-} extensions follow its {@code content}. Every other declared attribute is not published
 * and does not fail ({@link ResponseAttributes}): one warning per operation names each, with its
 * status, in published status order, and is logged only once the document is written.
 *
 * <p>Every published output type is generated by the output generator of the operation's profile
 * ({@link OutputSchemas}) during the check: the inferred type first, and only when some status
 * publishes it, then per status in published order each content implementation in declaration order,
 * then each header implementation. A failure names the status of the content or header; the
 * inferred type is always named as status {@code 200}. The declared statuses, {@code
 * useReturnTypeSchema}, and the media types of every status are checked before any type is
 * generated. Every refusal raised here or by {@link OutputSchemas} starts with the document's
 * configuration path; a refused schema construct or a component-key collision is reported by the
 * shared schema checks and starts with the application subject.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 */
public final class ResponseAssembler {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ResponseAssembler() {}

    /**
     * Plans the responses of one operation.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param document the enabled document
     * @param publication the detached publication of the mount
     * @param operation the operation
     * @param facts the operation's descriptor facts, or {@code null} when none were taken
     * @param context the component's assembly inputs
     * @param generators the output generators of the document's assembly
     * @param warnings the document's pending warnings
     * @return the plan, to publish once every operation of the document is checked
     * @throws RestConfigurationException when a declared status is invalid or declared twice at one
     *     level, {@code useReturnTypeSchema} is declared on a return type that is not inferable, one
     *     status declares a media type twice, or a published output type cannot be generated or holds
     *     a refused construct
     */
    static ResponsePlan check(
            String subject,
            EnabledDocuments.EnabledDocument document,
            MountPublication publication,
            OperationPublication operation,
            @Nullable OperationFacts facts,
            AssemblyContext context,
            OutputSchemaGeneratorCache generators,
            PendingWarnings warnings) {
        OperationDetail detail = operation.detail();
        ResponseShape shape = detail == null ? null : detail.response();
        if (shape == null) {
            return ResponsePlan.NONE;
        }
        String operationId = operation.operationId();
        String prefix = "apidocs.documents." + document.name() + ": " + subject;
        ResponseInference.Inference inference = ResponseInference.classify(shape, context.producerBindings());
        SortedMap<String, ApiResponse> declared = DeclaredResponses.read(prefix, operationId, facts);
        List<String> produces = ResponseInference.producedMediaTypes(shape);
        String profileId = shape.outputProfileId();

        Slot inferred = inference.outputType() == null
                ? null
                : new Slot(new OutputSchemas.Target(
                        operationId, "200", null, ".response", inference.outputType(), profileId));
        List<PlannedResponse> responses = new ArrayList<>();
        Map<String, Set<String>> omitted = new LinkedHashMap<>();
        if (declared.isEmpty()) {
            String status = inference.status();
            responses.add(new PlannedResponse(
                    status,
                    ResponseStatuses.reasonPhrase(status),
                    List.of(),
                    inference.inferable() ? inferredContent(inference, inferred) : null,
                    List.of(),
                    Map.of()));
        } else {
            for (Map.Entry<String, ApiResponse> entry : declared.entrySet()) {
                responses.add(declaredResponse(
                        prefix,
                        operationId,
                        entry.getKey(),
                        entry.getValue(),
                        inference,
                        inferred,
                        produces,
                        profileId));
                Set<String> attributes = ResponseAttributes.omitted(entry.getValue());
                if (!attributes.isEmpty()) {
                    omitted.put(entry.getKey(), attributes);
                }
            }
        }

        if (inferred != null && responses.stream().anyMatch(response -> response.uses(inferred))) {
            inferred.check(prefix, subject, generators);
        }
        for (PlannedResponse response : responses) {
            for (Slot slot : response.slots()) {
                slot.check(prefix, subject, generators);
            }
            for (PlannedHeader header : response.headers()) {
                if (header.schema() != null) {
                    header.schema().check(prefix, subject, generators);
                }
            }
        }
        String warning = ResponseAttributes.warning(document.name(), operationId, publication.mountPath(), omitted);
        if (warning != null) {
            warnings.add(ResponseAttributes.WARNING_KIND + operationId, warning);
        }
        return new ResponsePlan(responses);
    }

    /** Plans one declared status. */
    private static PlannedResponse declaredResponse(
            String prefix,
            String operationId,
            String status,
            ApiResponse response,
            ResponseInference.Inference inference,
            @Nullable Slot inferred,
            List<String> produces,
            String profileId) {
        if (response.useReturnTypeSchema() && !inference.inferable()) {
            throw new RestConfigurationException(prefix + ": operation '" + operationId
                    + "' declares @ApiResponse.useReturnTypeSchema on status " + status
                    + ", but its return type publishes no inferable response content; remove the attribute or"
                    + " declare the content");
        }
        String description = AnnotationValues.isSet(response.description())
                ? response.description()
                : ResponseStatuses.reasonPhrase(status);
        List<Slot> slots = new ArrayList<>();
        List<PlannedMediaType> content;
        if (response.content().length > 0) {
            content = declaredContent(prefix, operationId, status, response.content(), produces, profileId, slots);
        } else if (inference.inferable()
                && (response.useReturnTypeSchema() || ResponseStatuses.keepsInferredContent(status))) {
            content = inferredContent(inference, inferred);
        } else {
            content = null;
        }
        List<PlannedHeader> headers = new ArrayList<>();
        Set<String> headerNames = new HashSet<>();
        for (Header header : response.headers()) {
            if (!AnnotationValues.isSet(header.name())) {
                continue;
            }
            if (!headerNames.add(asciiLowerCase(header.name()))) {
                throw new RestConfigurationException(prefix + ": operation '" + operationId
                        + "' declares one header name more than once in status " + status
                        + " (@Header.name); declare each header once per status");
            }
            Slot schema = ResponseAttributes.implemented(header.schema().implementation())
                    ? new Slot(new OutputSchemas.Target(
                            operationId,
                            status,
                            header.name(),
                            ".response." + status + ".header." + header.name(),
                            header.schema().implementation(),
                            profileId))
                    : null;
            headers.add(new PlannedHeader(header.name(), header, schema));
        }
        Map<String, JsonNode> extensions = AnnotatedInfo.readExtensions(response.extensions(), new TreeSet<>());
        return new PlannedResponse(status, description, headers, content, slots, extensions);
    }

    /** Lower-cases ASCII letters only, so non-ASCII characters never fold into ASCII ones. */
    public static String asciiLowerCase(String name) {
        StringBuilder folded = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            folded.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return folded.toString();
    }

    /**
     * Plans the declared content of one status, collecting one slot per distinct implementation in
     * declaration order.
     */
    private static List<PlannedMediaType> declaredContent(
            String prefix,
            String operationId,
            String status,
            Content[] declared,
            List<String> produces,
            String profileId,
            List<Slot> slots) {
        List<Class<?>> implementations = new ArrayList<>();
        for (Content content : declared) {
            Class<?> implementation = implementation(content);
            if (implementation != null && !implementations.contains(implementation)) {
                implementations.add(implementation);
            }
        }
        Map<Class<?>, Slot> byImplementation = new LinkedHashMap<>();
        for (int i = 0; i < implementations.size(); i++) {
            Class<?> implementation = implementations.get(i);
            String suffix = ".response." + status + (implementations.size() > 1 ? "." + (i + 1) : "");
            Slot slot =
                    new Slot(new OutputSchemas.Target(operationId, status, null, suffix, implementation, profileId));
            byImplementation.put(implementation, slot);
            slots.add(slot);
        }

        Map<String, PlannedMediaType> mediaTypes = new LinkedHashMap<>();
        for (Content content : declared) {
            Class<?> implementation = implementation(content);
            Slot slot = implementation == null ? null : byImplementation.get(implementation);
            SchemaForm form;
            if (slot == null) {
                form = SchemaForm.NONE;
            } else if (ResponseAttributes.implemented(content.schema().implementation())) {
                form = SchemaForm.REFERENCE;
            } else {
                form = SchemaForm.ARRAY;
            }
            ObjectNode examples =
                    Examples.render(prefix, operationId, "@Content.examples on status " + status, content.examples());
            List<String> applied =
                    AnnotationValues.isSet(content.mediaType()) ? List.of(content.mediaType()) : produces;
            for (String mediaType : applied) {
                if (mediaTypes.containsKey(mediaType)) {
                    throw new RestConfigurationException(prefix + ": operation '" + operationId
                            + "' declares one media type more than once in the content of status " + status
                            + " (@Content.mediaType); declare each media type once per status");
                }
                mediaTypes.put(mediaType, new PlannedMediaType(mediaType, form, slot, content, examples));
            }
        }
        return List.copyOf(mediaTypes.values());
    }

    /** Plans the inferred content: one media type per inferred media type. */
    private static List<PlannedMediaType> inferredContent(
            ResponseInference.Inference inference, @Nullable Slot inferred) {
        boolean json = inference.kind() == ResponseInference.ResponseKind.JSON_ENTITY;
        List<PlannedMediaType> content = new ArrayList<>();
        for (String mediaType : inference.mediaTypes()) {
            content.add(
                    json
                            ? new PlannedMediaType(mediaType, SchemaForm.REFERENCE, inferred, null, null)
                            : new PlannedMediaType(mediaType, SchemaForm.NONE, null, null, null));
        }
        return content;
    }

    /**
     * Returns the implementation a content publishes: its schema's implementation, else its array
     * element's, else {@code null}.
     */
    private static @Nullable Class<?> implementation(Content content) {
        if (ResponseAttributes.implemented(content.schema().implementation())) {
            return content.schema().implementation();
        }
        if (ResponseAttributes.implemented(content.array().schema().implementation())) {
            return content.array().schema().implementation();
        }
        return null;
    }

    /** The checked responses of one operation. */
    static final class ResponsePlan {

        /** The plan of an operation without response facts, which publishes no {@code responses}. */
        static final ResponsePlan NONE = new ResponsePlan(null);

        @Nullable
        private final List<PlannedResponse> responses;

        private ResponsePlan(@Nullable List<PlannedResponse> responses) {
            this.responses = responses == null ? null : List.copyOf(responses);
        }

        /**
         * Writes the {@code responses} object, publishing each checked schema once.
         *
         * @param embedder the schema embedder that checked the schemas
         * @return the Responses Object, or {@code null} when the operation publishes none
         * @throws RestConfigurationException when a component key is already taken in the document
         */
        @Nullable
        ObjectNode publish(SchemaEmbedder embedder) {
            if (responses == null) {
                return null;
            }
            ObjectNode node = NODES.objectNode();
            for (PlannedResponse response : responses) {
                node.set(response.status(), response.publish(embedder));
            }
            return node;
        }
    }

    /** How a media type's schema is published. */
    private enum SchemaForm {
        /** The media type publishes no schema. */
        NONE,
        /** The schema is a reference to the slot's component. */
        REFERENCE,
        /** The schema is an array whose items reference the slot's component. */
        ARRAY
    }

    /**
     * One output type published as a component, generated and checked once and published once; every
     * place that references it receives its own copy of the reference.
     */
    private static final class Slot {

        private final OutputSchemas.Target target;

        @Nullable
        private SchemaEmbedder.CheckedSchema checked;

        @Nullable
        private JsonNode reference;

        Slot(OutputSchemas.Target target) {
            this.target = target;
        }

        /** Generates and checks the slot's schema, once. */
        void check(String prefix, String subject, OutputSchemaGeneratorCache generators) {
            if (checked == null) {
                checked = OutputSchemas.check(prefix, subject, generators, target);
            }
        }

        /** Publishes the component on first use and returns a new copy of the reference to it. */
        JsonNode reference(SchemaEmbedder embedder) {
            if (reference == null) {
                if (checked == null) {
                    throw new IllegalStateException("A response schema is published before it is checked");
                }
                reference = embedder.publish(checked);
            }
            return reference.deepCopy();
        }
    }

    /**
     * One planned Response Object.
     *
     * @param status the status key
     * @param description the published description
     * @param headers the published headers, in declaration order
     * @param content the published media types in order, or {@code null} when it publishes no content
     * @param slots the components of its declared content, in declaration order
     * @param extensions its published {@code x-} extensions, in declaration order
     */
    private record PlannedResponse(
            String status,
            String description,
            List<PlannedHeader> headers,
            @Nullable List<PlannedMediaType> content,
            List<Slot> slots,
            Map<String, JsonNode> extensions) {

        /** Whether some media type of the response references the slot. */
        boolean uses(Slot slot) {
            return content != null && content.stream().anyMatch(mediaType -> mediaType.slot() == slot);
        }

        /**
         * Writes the Response Object: {@code description}, {@code headers}, {@code content}, then each
         * published extension.
         */
        ObjectNode publish(SchemaEmbedder embedder) {
            ObjectNode node = NODES.objectNode();
            node.put("description", description);
            if (!headers.isEmpty()) {
                ObjectNode published = node.putObject("headers");
                for (PlannedHeader header : headers) {
                    published.set(header.name(), header.publish(embedder));
                }
            }
            if (content != null) {
                ObjectNode published = node.putObject("content");
                for (PlannedMediaType mediaType : content) {
                    published.set(mediaType.mediaType(), mediaType.publish(embedder));
                }
            }
            extensions.forEach((name, value) -> node.set(name, value.deepCopy()));
            return node;
        }
    }

    /**
     * One planned Header Object.
     *
     * @param name the header name
     * @param declaration the declaring annotation
     * @param schema the component of its schema implementation, or {@code null} for the empty schema
     */
    private record PlannedHeader(
            String name, Header declaration, @Nullable Slot schema) {

        /**
         * Writes the Header Object: {@code description} when set, {@code required} and {@code
         * deprecated} when true, then {@code schema}, which carries the documentation members of the
         * header's implemented schema beside its reference.
         */
        ObjectNode publish(SchemaEmbedder embedder) {
            ObjectNode node = NODES.objectNode();
            if (AnnotationValues.isSet(declaration.description())) {
                node.put("description", declaration.description());
            }
            if (declaration.required()) {
                node.put("required", true);
            }
            if (declaration.deprecated()) {
                node.put("deprecated", true);
            }
            node.set(
                    "schema",
                    schema == null
                            ? NODES.objectNode()
                            : ResponseAttributes.documented(schema.reference(embedder), declaration.schema()));
            return node;
        }
    }

    /**
     * One planned Media Type Object.
     *
     * @param mediaType the media type
     * @param form how its schema is published
     * @param slot the component its schema references, or {@code null} when it publishes no schema
     * @param declaration the declaring annotation, or {@code null} for inferred content
     * @param examples the rendered named examples, or {@code null} when there are none
     */
    private record PlannedMediaType(
            String mediaType,
            SchemaForm form,
            @Nullable Slot slot,
            @Nullable Content declaration,
            @Nullable ObjectNode examples) {

        /**
         * Writes the Media Type Object: {@code schema} unless it publishes none, then {@code examples}
         * when there are any. A declared schema carries the documentation members of its implemented
         * {@code @Schema} beside the reference; an array carries them beside its {@code items}
         * reference.
         */
        ObjectNode publish(SchemaEmbedder embedder) {
            ObjectNode node = NODES.objectNode();
            switch (form) {
                case NONE -> {}
                case REFERENCE -> {
                    JsonNode reference = slot.reference(embedder);
                    node.set(
                            "schema",
                            declaration == null
                                    ? reference
                                    : ResponseAttributes.documented(reference, declaration.schema()));
                }
                case ARRAY -> {
                    ObjectNode array = node.putObject("schema");
                    array.set(
                            "items",
                            ResponseAttributes.documented(
                                    slot.reference(embedder),
                                    declaration.array().schema()));
                    array.put("type", "array");
                }
            }
            if (examples != null) {
                node.set("examples", examples.deepCopy());
            }
            return node;
        }
    }
}
