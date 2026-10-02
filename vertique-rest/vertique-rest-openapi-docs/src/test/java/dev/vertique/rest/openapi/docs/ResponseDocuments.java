// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.publication.ResponseShape;
import dev.vertique.rest.openapi.docs.DisclosureDocuments.Rendering;
import dev.vertique.rest.openapi.docs.MetadataDocuments.Outcome;
import dev.vertique.rest.openapi.docs.MetadataDocuments.WarningCapture;
import dev.vertique.rest.openapi.docs.assembly.AssemblyContext;
import dev.vertique.rest.openapi.docs.diagnostics.DocumentWarnings;
import dev.vertique.rest.openapi.docs.fixture.input.Publications;
import dev.vertique.rest.openapi.docs.fixture.input.UnitDocumentedApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.unit.MetadataPublications;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Builds response facts as the runtime captures them, assembles synthetic publications whose
 * operations carry those facts through the real documentation path, and reads the {@code
 * responses} and components of the assembled documents.
 *
 * <p>It lives in the documentation package because the assembly context, the warning guard, the
 * assembler, and the sink's descriptor-fact and detach steps are package-private.
 *
 * <p><b>Response facts.</b> {@link #shape(Class, String, List, String)} builds a {@link
 * ResponseShape} exactly as the runtime inventory does for a resource method registered on a
 * resource class: the method's generic return type, unresolved; the resource class the method is
 * registered on (which may be a subclass of the method's declaring class); {@code returnsFuture}
 * when the raw return type is an {@code io.vertx.core.Future}; {@code returnsVoid} when the raw
 * return type is {@code void} or {@code Void}, or the return type is a parameterized {@code Future}
 * whose type argument is {@code Void}; the given produces list, copied; and the given output
 * profile id. A method is found by name as {@link MetadataPublications#annotate} finds it: among
 * the class's declared methods (bridge and synthetic methods excluded), else among its public
 * methods (an inherited public method included); the name must identify exactly one method. Type
 * arguments are never cast or resolved here, so a return type holding a type variable or wildcard
 * is captured unchanged.
 *
 * <p><b>Synthetic publications.</b> {@link #assemble(String, List, JsonMapperProfileRegistry, Set,
 * DocumentWarnings)} builds a mount {@code /api/<document>/*} of application {@code <document>},
 * declared by {@link UnitDocumentedApi} (a public document), with one operation per {@link
 * ResponseOperation}, in list order. Every operation carries a stub descriptor holding the real
 * method and class annotations of its fixture method and resource class ({@link
 * MetadataPublications#annotate}), and its detail carries the {@link ResponseShape} built from that
 * method and class with the operation's produces list and profile id (which is also the
 * operation's request profile id). Fixture methods take no parameter: the operations bind no
 * input. The publication is then assembled as {@link MetadataDocuments#assemble(MountPublication,
 * ApiDocs.Access, AssemblyContext)} does: descriptor facts through the sink's own {@code
 * operationFacts}, the publication detached through the sink's own {@code detach}, then the
 * document assembler, with an assembly context that binds no schema source and carries the given
 * profile registry, warning guard, and response producer bindings. The produces list reaches the
 * assembler only through the response facts: the stub descriptor itself produces nothing.
 *
 * <p><b>Reading documents.</b> The readers take the Jackson tree of a rendering ({@link
 * Rendering#jsonTree()}), whose members keep their written order, and fail with an {@link
 * AssertionError} naming what is missing.
 *
 * <p><b>Warnings.</b> {@link #warningCapture()} returns a capture of the documentation module's
 * warning logger; attach it in {@code @BeforeEach} and detach it in {@code @AfterEach}.
 */
public final class ResponseDocuments {

    /** The built-in profile id every operation uses unless it names another. */
    public static final String DEFAULT_PROFILE = "vertique";

    /** The built-in profile id that declares a JSON Schema type override for {@code BigDecimal}. */
    public static final String STRICT_PROFILE = "vertique-strict";

    /** The reference prefix of a component schema. */
    public static final String SCHEMA_REF_PREFIX = "#/components/schemas/";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ResponseDocuments() {}

    // ---------------------------------------------------------------------------------------------
    // Response facts
    // ---------------------------------------------------------------------------------------------

    /**
     * Finds a fixture method by name: among the class's declared methods (bridge and synthetic
     * methods excluded), else among its public methods.
     *
     * @param resourceClass the resource class the method is registered on
     * @param methodName the method's name
     * @return the method
     * @throws IllegalStateException if the name does not identify exactly one method
     */
    public static Method method(Class<?> resourceClass, String methodName) {
        Objects.requireNonNull(resourceClass, "resourceClass");
        Objects.requireNonNull(methodName, "methodName");
        List<Method> declared = Arrays.stream(resourceClass.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName) && !method.isBridge() && !method.isSynthetic())
                .toList();
        List<Method> candidates = declared.isEmpty()
                ? Arrays.stream(resourceClass.getMethods())
                        .filter(method -> method.getName().equals(methodName) && !method.isBridge())
                        .toList()
                : declared;
        if (candidates.size() != 1) {
            throw new IllegalStateException(resourceClass.getName() + " has " + candidates.size() + " methods named '"
                    + methodName + "'; a fixture method name must be unique");
        }
        return candidates.get(0);
    }

    /**
     * Builds the response facts of a fixture method under the {@value #DEFAULT_PROFILE} profile.
     *
     * @param resourceClass the resource class the method is registered on
     * @param methodName the method's name, found as {@link #method} finds it
     * @param produces the media types the method produces, in declaration order; empty for none
     * @return the response facts
     */
    public static ResponseShape shape(Class<?> resourceClass, String methodName, List<String> produces) {
        return shape(resourceClass, methodName, produces, DEFAULT_PROFILE);
    }

    /**
     * Builds the response facts of a fixture method, as the runtime inventory captures them.
     *
     * @param resourceClass the resource class the method is registered on
     * @param methodName the method's name, found as {@link #method} finds it
     * @param produces the media types the method produces, in declaration order; empty for none
     * @param outputProfileId the resolved output profile id
     * @return the response facts
     */
    public static ResponseShape shape(
            Class<?> resourceClass, String methodName, List<String> produces, String outputProfileId) {
        return shape(resourceClass, method(resourceClass, methodName), produces, outputProfileId);
    }

    /**
     * Builds the response facts of a method registered on a resource class, as the runtime inventory
     * captures them.
     *
     * @param resourceClass the resource class the method is registered on
     * @param method the method, declared by the resource class or one of its supertypes
     * @param produces the media types the method produces, in declaration order; empty for none
     * @param outputProfileId the resolved output profile id
     * @return the response facts
     * @throws IllegalArgumentException if the method's declaring class is not a supertype of the
     *     resource class
     */
    public static ResponseShape shape(
            Class<?> resourceClass, Method method, List<String> produces, String outputProfileId) {
        Objects.requireNonNull(resourceClass, "resourceClass");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(produces, "produces");
        Objects.requireNonNull(outputProfileId, "outputProfileId");
        if (!method.getDeclaringClass().isAssignableFrom(resourceClass)) {
            throw new IllegalArgumentException(method + " is not a method of " + resourceClass.getName());
        }
        Class<?> raw = method.getReturnType();
        Type generic = method.getGenericReturnType();
        boolean returnsFuture = Future.class.isAssignableFrom(raw);
        boolean returnsVoid = raw == void.class || raw == Void.class || isFutureOfVoid(generic);
        return new ResponseShape(generic, resourceClass, returnsFuture, returnsVoid, produces, outputProfileId);
    }

    /** Whether a return type is a parameterized {@code Future} whose type argument is {@code Void}. */
    private static boolean isFutureOfVoid(Type type) {
        return type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw
                && Future.class.isAssignableFrom(raw)
                && parameterized.getActualTypeArguments()[0] == Void.class;
    }

    // ---------------------------------------------------------------------------------------------
    // Profiles and generated schemas
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns a new registry holding the built-in profiles ({@value #DEFAULT_PROFILE} and {@value
     * #STRICT_PROFILE} among them) and the given application profiles.
     *
     * @param applicationProfiles the application profiles, for example a snake-case test profile
     * @return the registry
     */
    public static JsonMapperProfileRegistry registry(JsonMapperProfile... applicationProfiles) {
        return new DefaultJsonMapperProfileRegistry(Set.of(applicationProfiles));
    }

    /**
     * Generates the output schema of a type as a fresh output-direction generator of a profile
     * does, for comparison with a published component.
     *
     * @param profiles the registry the profile is resolved through
     * @param profileId the profile id
     * @param type the resolved output type
     * @return the parsed canonical schema
     */
    public static JsonNode generatedOutputSchema(JsonMapperProfileRegistry profiles, String profileId, Type type) {
        JsonMapperProfile profile = profiles.profile(JsonProfileId.of(profileId));
        return parse(AnnotationJsonSchemaGenerator.forOutputProfile(profile).generateCanonical(type));
    }

    // ---------------------------------------------------------------------------------------------
    // Synthetic publications
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the mount path of a synthetic publication.
     *
     * @param documentName the document's name, which is also the application's name
     * @return {@code /api/<documentName>/*}
     */
    public static String mountPath(String documentName) {
        return "/api/" + Objects.requireNonNull(documentName, "documentName") + "/*";
    }

    /**
     * Builds the publication the documentation sink receives for a synthetic mount, every operation
     * carrying its stub descriptor and its response facts.
     *
     * @param documentName the document's name, which is also the application's name
     * @param operations the operations, in publication order; their ids must be distinct
     * @return the publication with descriptors attached
     * @throws IllegalArgumentException if two operations share an id
     * @throws IllegalStateException if a fixture method is not found or takes a parameter
     */
    static MountPublication publication(String documentName, List<ResponseOperation> operations) {
        Publications mount =
                Publications.mount(mountPath(documentName)).application(documentName, UnitDocumentedApi.class);
        Map<String, ResponseShape> shapes = new LinkedHashMap<>();
        for (ResponseOperation operation : operations) {
            ResponseShape shape = shape(
                    operation.resourceClass(), operation.methodName(), operation.produces(), operation.profileId());
            if (shapes.putIfAbsent(operation.operationId(), shape) != null) {
                throw new IllegalArgumentException("two operations share the id '" + operation.operationId() + "'");
            }
            mount.operation(operation.httpMethod(), operation.template(), operation.operationId())
                    .profileId(operation.profileId());
        }
        MetadataPublications annotated = MetadataPublications.from(mount.build());
        for (ResponseOperation operation : operations) {
            annotated.annotate(operation.operationId(), operation.resourceClass(), operation.methodName());
        }
        MountPublication attached = annotated.build();
        List<OperationPublication> rewritten = new ArrayList<>();
        for (OperationPublication operation : attached.operations()) {
            rewritten.add(withResponse(operation, shapes.get(operation.operationId())));
        }
        return new MountPublication(
                attached.mountPath(),
                attached.mountId(),
                attached.applicationName(),
                attached.declaringType(),
                attached.strategyId(),
                rewritten);
    }

    private static OperationPublication withResponse(OperationPublication operation, ResponseShape shape) {
        OperationDetail detail = Objects.requireNonNull(operation.detail(), "detail");
        OperationDetail withShape = new OperationDetail(
                detail.descriptor(),
                detail.profileId(),
                detail.schemas(),
                detail.gateInstalled(),
                detail.inputs(),
                Objects.requireNonNull(shape, "shape"));
        return new OperationPublication(
                operation.operationId(),
                operation.httpMethod(),
                operation.jaxRsPathTemplate(),
                operation.vertxRouteValue(),
                operation.vertxRouteIsRegex(),
                operation.effectivePolicy(),
                operation.securityRequirementSets(),
                operation.requiresAction(),
                withShape);
    }

    /**
     * Assembles the public document of a synthetic mount through the documentation sink's steps and
     * the document assembler.
     *
     * @param documentName the document's name, which is also the application's name
     * @param operations the operations, in publication order
     * @param profiles the profile registry of the assembly context
     * @param producerBindings the registered response producer bindings of the assembly context
     * @param warnings the warning guard of the assembly context, standing for one component
     * @return the rendering, or the publication failure
     */
    public static Outcome assemble(
            String documentName,
            List<ResponseOperation> operations,
            JsonMapperProfileRegistry profiles,
            Set<ResponseProducerBinding<?>> producerBindings,
            DocumentWarnings warnings) {
        MountPublication attached = publication(documentName, operations);
        AssemblyContext context = new AssemblyContext(
                Optional.empty(),
                Objects.requireNonNull(profiles, "profiles"),
                Objects.requireNonNull(warnings, "warnings"),
                Objects.requireNonNull(producerBindings, "producerBindings"),
                Set.of());
        return MetadataDocuments.assemble(attached, ApiDocs.Access.PUBLIC, context);
    }

    /**
     * Assembles the public document of a synthetic mount with the given registry, no response
     * producer binding, and a fresh warning guard.
     *
     * @param documentName the document's name, which is also the application's name
     * @param profiles the profile registry of the assembly context
     * @param operations the operations, in publication order
     * @return the rendering, or the publication failure
     */
    public static Outcome assemble(
            String documentName, JsonMapperProfileRegistry profiles, ResponseOperation... operations) {
        return assemble(documentName, List.of(operations), profiles, Set.of(), new DocumentWarnings());
    }

    /**
     * Assembles the public document of a synthetic mount with the built-in profiles, no response
     * producer binding, and a fresh warning guard.
     *
     * @param documentName the document's name, which is also the application's name
     * @param operations the operations, in publication order
     * @return the rendering, or the publication failure
     */
    public static Outcome assemble(String documentName, ResponseOperation... operations) {
        return assemble(documentName, registry(), operations);
    }

    /**
     * Returns a new capture of the documentation module's warning logger, not yet attached.
     *
     * @return the capture
     */
    public static WarningCapture warningCapture() {
        return new WarningCapture();
    }

    // ---------------------------------------------------------------------------------------------
    // Reading documents
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the Operation Object at a path and method.
     *
     * @param document the document's tree
     * @param path the path key, for example {@code "/reports/dynamic"}
     * @param method the HTTP method in either case, for example {@code "get"}
     * @return the operation
     */
    static JsonNode operation(JsonNode document, String path, String method) {
        JsonNode operation = document.path("paths").path(path).get(method.toLowerCase(Locale.ROOT));
        assertNotNull(operation, () -> "the document has no operation " + method + " " + path + ": " + document);
        return operation;
    }

    /**
     * Returns the one Operation Object whose {@code operationId} is the given id, whatever its path.
     *
     * @param document the document's tree
     * @param operationId the operation id
     * @return the operation
     */
    static JsonNode operation(JsonNode document, String operationId) {
        List<JsonNode> found = new ArrayList<>();
        Iterator<JsonNode> pathItems = document.path("paths").elements();
        while (pathItems.hasNext()) {
            Iterator<JsonNode> operations = pathItems.next().elements();
            while (operations.hasNext()) {
                JsonNode operation = operations.next();
                if (operation.isObject()
                        && operationId.equals(operation.path("operationId").asText(null))) {
                    found.add(operation);
                }
            }
        }
        assertTrue(
                found.size() == 1,
                () -> "expected one operation '" + operationId + "', found " + found.size() + ": " + document);
        return found.get(0);
    }

    /**
     * Returns the Responses Object of the operation at a path and method.
     *
     * @param document the document's tree
     * @param path the path key
     * @param method the HTTP method in either case
     * @return the Responses Object
     */
    public static JsonNode responses(JsonNode document, String path, String method) {
        return responsesOf(operation(document, path, method), method + " " + path);
    }

    /**
     * Returns the Responses Object of the operation with the given id.
     *
     * @param document the document's tree
     * @param operationId the operation id
     * @return the Responses Object
     */
    public static JsonNode responses(JsonNode document, String operationId) {
        return responsesOf(operation(document, operationId), operationId);
    }

    private static JsonNode responsesOf(JsonNode operation, String subject) {
        JsonNode responses = operation.get("responses");
        assertNotNull(responses, () -> "operation " + subject + " has no responses: " + operation);
        return responses;
    }

    /**
     * Returns the Response Object of one status of the operation with the given id.
     *
     * @param document the document's tree
     * @param operationId the operation id
     * @param status the status key, for example {@code "200"}, {@code "2XX"}, or {@code "default"}
     * @return the Response Object
     */
    public static JsonNode response(JsonNode document, String operationId, String status) {
        JsonNode responses = responses(document, operationId);
        JsonNode response = responses.get(status);
        assertNotNull(response, () -> "operation '" + operationId + "' has no response " + status + ": " + responses);
        return response;
    }

    /**
     * Returns the keys of a Responses Object, in written order.
     *
     * @param responses the Responses Object
     * @return the status keys
     */
    public static List<String> responseKeys(JsonNode responses) {
        List<String> keys = new ArrayList<>();
        responses.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /**
     * Returns, per media type of a Response Object's {@code content} in written order, the name of
     * the component its {@code schema.$ref} references, or {@code null} when its schema has no direct
     * {@code $ref} (no schema, or a schema such as an array whose reference sits under {@code items};
     * see {@link #itemsRef}). An absent {@code content} gives an empty map.
     *
     * @param response the Response Object
     * @return the media types and component names; a value is {@code null} as stated above
     */
    public static Map<String, String> contentRefs(JsonNode response) {
        Map<String, String> refs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : response.path("content").properties()) {
            JsonNode ref = entry.getValue().path("schema").get("$ref");
            refs.put(entry.getKey(), ref == null ? null : componentName(ref.asText()));
        }
        return refs;
    }

    /**
     * Returns the name of the component an array schema's {@code items.$ref} references.
     *
     * @param schema the schema, for example a media type's {@code schema}
     * @return the component name
     */
    static String itemsRef(JsonNode schema) {
        JsonNode ref = schema.path("items").get("$ref");
        assertNotNull(ref, () -> "the schema has no items reference: " + schema);
        return componentName(ref.asText());
    }

    /**
     * Returns the name of the component a schema reference names.
     *
     * @param ref the reference, for example {@code "#/components/schemas/create.response"}
     * @return the component name, for example {@code "create.response"}
     */
    static String componentName(String ref) {
        assertTrue(ref.startsWith(SCHEMA_REF_PREFIX), () -> "not a component schema reference: " + ref);
        return ref.substring(SCHEMA_REF_PREFIX.length());
    }

    /**
     * Returns a component schema.
     *
     * @param document the document's tree
     * @param name the component name
     * @return the schema
     */
    public static JsonNode component(JsonNode document, String name) {
        JsonNode schema = document.path("components").path("schemas").get(name);
        assertNotNull(schema, () -> "the document has no component '" + name + "'; it has " + componentKeys(document));
        return schema;
    }

    /**
     * Reports whether a component schema exists.
     *
     * @param document the document's tree
     * @param name the component name
     * @return {@code true} when {@code components.schemas} has the name
     */
    public static boolean hasComponent(JsonNode document, String name) {
        return document.path("components").path("schemas").has(name);
    }

    /**
     * Returns the names of every component schema, in written order.
     *
     * @param document the document's tree
     * @return the names; empty when the document has no component schema
     */
    public static Set<String> componentKeys(JsonNode document) {
        Set<String> keys = new LinkedHashSet<>();
        document.path("components").path("schemas").fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /**
     * Returns the property names of an object schema, in written order.
     *
     * @param schema the schema
     * @return the names of its {@code properties}; empty when it has none
     */
    public static Set<String> propertyNames(JsonNode schema) {
        Set<String> names = new LinkedHashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * Asserts that a rendered document is a valid OpenAPI 3.1 document.
     *
     * @param rendering the rendering
     */
    public static void assertValidates(Rendering rendering) {
        OpenApi31Toolchain.assertValid(rendering.document());
    }

    /**
     * Asserts that a document tree is a valid OpenAPI 3.1 document.
     *
     * @param document the document's tree
     */
    public static void assertValidates(JsonNode document) {
        try {
            OpenApi31Toolchain.assertValid(new JsonObject(JSON.writeValueAsString(document)));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Operations
    // ---------------------------------------------------------------------------------------------

    /**
     * One operation of a synthetic mount: its id, route, fixture method, produces list, and profile.
     *
     * @param operationId the operation id, independent of the fixture method's name
     * @param httpMethod the HTTP method, for example {@code "GET"}
     * @param template the path template, relative to the mount
     * @param resourceClass the resource class the fixture method is registered on; its resolved
     *     annotations become the operation's class annotations
     * @param methodName the fixture method's name, found as {@link #method} finds it; the method
     *     takes no parameter
     * @param produces the media types the operation produces, in declaration order; empty for none
     * @param profileId the operation's profile id, for both its request and its response
     */
    public record ResponseOperation(
            String operationId,
            String httpMethod,
            String template,
            Class<?> resourceClass,
            String methodName,
            List<String> produces,
            String profileId) {

        /** Copies the produces list; {@code null} components are rejected. */
        public ResponseOperation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(httpMethod, "httpMethod");
            Objects.requireNonNull(template, "template");
            Objects.requireNonNull(resourceClass, "resourceClass");
            Objects.requireNonNull(methodName, "methodName");
            produces = List.copyOf(produces);
            Objects.requireNonNull(profileId, "profileId");
        }

        /**
         * Returns a {@code GET} operation with no produces type under the {@value
         * ResponseDocuments#DEFAULT_PROFILE} profile, at the template {@code /} followed by the
         * operation id with every character outside {@code [A-Za-z0-9_-]} replaced by {@code -} (so
         * {@code get:report} is at {@code /get-report}).
         *
         * @param operationId the operation id
         * @param resourceClass the resource class the fixture method is registered on
         * @param methodName the fixture method's name
         * @return the operation
         */
        public static ResponseOperation of(String operationId, Class<?> resourceClass, String methodName) {
            return new ResponseOperation(
                    operationId,
                    "GET",
                    "/" + operationId.replaceAll("[^A-Za-z0-9_-]", "-"),
                    resourceClass,
                    methodName,
                    List.of(),
                    DEFAULT_PROFILE);
        }

        /**
         * Returns a copy producing the given media types instead.
         *
         * @param mediaTypes the media types, in declaration order
         * @return the copy
         */
        public ResponseOperation withProduces(String... mediaTypes) {
            return new ResponseOperation(
                    operationId, httpMethod, template, resourceClass, methodName, List.of(mediaTypes), profileId);
        }

        /**
         * Returns a copy under the given profile instead.
         *
         * @param id the profile id, for example {@value ResponseDocuments#STRICT_PROFILE}
         * @return the copy
         */
        public ResponseOperation withProfile(String id) {
            return new ResponseOperation(operationId, httpMethod, template, resourceClass, methodName, produces, id);
        }

        /**
         * Returns a copy at the given method and template instead.
         *
         * @param method the HTTP method
         * @param path the path template, relative to the mount
         * @return the copy
         */
        ResponseOperation withRoute(String method, String path) {
            return new ResponseOperation(operationId, method, path, resourceClass, methodName, produces, profileId);
        }
    }
}
