// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import dev.vertique.json.schema.CanonicalSchema;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.publication.CapturedSchemas;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputKey;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.publication.ResponseShape;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.CreateItemRequest;
import dev.vertique.rest.openapi.docs.fixture.MgmtApi;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit proof that the snapshot renderer digests every published fact of a documented mount and
 * nothing else. A base publication for the {@code public} application's mount is built by hand from
 * the public publication records, then varied in exactly one fact per row: a changed operation fact
 * changes that operation's digest and leaves the mount's part equal; a changed mount fact changes the
 * mount's part and leaves the operation's digest equal; and the base rebuilt from fresh objects, with
 * the parameter map and every {@link JsonObject} populated in reverse insertion order, renders a
 * snapshot equal to the base's.
 *
 * <p>No expectation is derived from the renderer. Every build creates new objects — a second {@code
 * describe} call and so a new redaction manifest, new bindings, and a new identity-only descriptor
 * double — so a rendering that reads an object's identity cannot equal the base.
 */
class SnapshotRendererTest {

    /** The operation id of the base publication's only operation. */
    private static final String OPERATION_ID = CatalogResource.CREATE_ITEM;

    /** Exactly 64 lowercase hexadecimal characters: a SHA-256 digest. */
    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

    /** The id of the input-direction profile the body schema is generated under. */
    private static final String PROFILE_ID = "vertique";

    /** The query parameter of {@link CatalogResource#createItem}. */
    private static final String DRY_RUN = "dryRun";

    /** What a variant's rendering is expected to change, compared with the base's. */
    enum Expectation {
        /** The operation's digest differs; the mount's part is equal. */
        OPERATION_DIGEST_DIFFERS,
        /** The mount's part differs; the operation's digest is equal. */
        MOUNT_PART_DIFFERS,
        /** The whole snapshot is equal. */
        SNAPSHOT_EQUAL
    }

    private static Stream<Arguments> variants() {
        return Stream.of(
                operationRow("HTTP method", spec -> spec.httpMethod = "PUT"),
                operationRow("JAX-RS template", spec -> spec.jaxRsPathTemplate = "/items/{kind}"),
                operationRow("route value", spec -> spec.vertxRouteValue = "/items/new"),
                operationRow("regex flag", spec -> spec.vertxRouteIsRegex = true),
                operationRow("effective policy", spec -> spec.effectivePolicy = new SecurityPolicy.PermitAll()),
                operationRow(
                        "requirement sets",
                        spec -> spec.requirementSets = List.of(
                                new SecurityRequirementSet(List.of(new SecurityRequirement("bearerAuth", List.of()))))),
                operationRow("requiresAction", spec -> spec.requiresAction = true),
                operationRow("profileId", spec -> spec.profileId = "strict"),
                operationRow("gateInstalled", spec -> spec.gateInstalled = false),
                operationRow(
                        "one body-schema value",
                        spec -> spec.bodySchemaChange = schema -> schema.put(
                                "properties",
                                schema.getJsonObject("properties")
                                        .put("quantity", new JsonObject().put("type", "number")))),
                operationRow(
                        "one parameter-schema value",
                        spec -> spec.parameterSchemaChange = schema -> schema.put("type", "string")),
                operationRow("provenance absent", spec -> spec.withProvenance = false),
                operationRow(
                        "an input's requiredness",
                        spec -> spec.dryRunRequiredness = InputBinding.Requiredness.REQUIRED),
                operationRow("an input's hidden", spec -> spec.dryRunHidden = true),
                operationRow("an input's schemaEnforced", spec -> spec.dryRunSchemaEnforced = false),
                operationRow("an input's default", spec -> spec.dryRunDefault = "false"),
                operationRow("ResponseShape.produces", spec -> spec.produces = List.of("text/plain")),
                operationRow("output profile id", spec -> spec.outputProfileId = "strict"),
                mountRow("mount path", spec -> spec.mountPath = "/api/other/*"),
                mountRow("strategy id", spec -> spec.strategyId = "header"),
                mountRow("application name", spec -> spec.applicationName = "other"),
                mountRow("declaring type", spec -> spec.declaringType = MgmtApi.class),
                Arguments.of(
                        "rebuilt in reverse insertion order", Expectation.SNAPSHOT_EQUAL, (Consumer<PublicationSpec>)
                                spec -> spec.reverseInsertionOrder = true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("variants")
    @DisplayName("Every published fact changes the snapshot; nothing else does")
    void everyPublishedFactChangesTheSnapshot(String label, Expectation expectation, Consumer<PublicationSpec> change) {
        // Given: the base publication, and a variant differing from it in one named way; each is
        // built from fresh objects.
        MountPublication basePublication = new PublicationSpec().build();
        PublicationSpec variantSpec = new PublicationSpec();
        change.accept(variantSpec);
        MountPublication variantPublication = variantSpec.build();

        // When: the renderer renders each.
        Snapshot base = SnapshotRenderer.render(basePublication);
        Snapshot variant = SnapshotRenderer.render(variantPublication);

        // Then: each renders one digest, for the operation, before any digest is compared.
        assertEquals(List.of(OPERATION_ID), List.copyOf(base.operationDigests().keySet()));
        assertEquals(
                List.of(OPERATION_ID), List.copyOf(variant.operationDigests().keySet()));
        List<String> digests = Stream.concat(
                        base.operationDigests().values().stream(), variant.operationDigests().values().stream())
                .toList();
        assertFalse(digests.isEmpty(), "no digest was rendered");
        for (String digest : digests) {
            assertTrue(SHA_256_HEX.matcher(digest).matches(), "not 64 lowercase hex characters: " + digest);
        }

        // Then: exactly the expected part of the snapshot differs from the base's.
        String baseDigest = base.operationDigests().get(OPERATION_ID);
        String variantDigest = variant.operationDigests().get(OPERATION_ID);
        switch (expectation) {
            case OPERATION_DIGEST_DIFFERS -> {
                assertNotEquals(baseDigest, variantDigest, label + " left the operation digest unchanged");
                assertEquals(base.mountPart(), variant.mountPart(), label + " changed the mount's part");
            }
            case MOUNT_PART_DIFFERS -> {
                assertNotEquals(base.mountPart(), variant.mountPart(), label + " left the mount's part unchanged");
                assertEquals(baseDigest, variantDigest, label + " changed the operation digest");
            }
            case SNAPSHOT_EQUAL -> assertEquals(base, variant, label + " changed the snapshot");
        }
    }

    private static Arguments operationRow(String label, Consumer<PublicationSpec> change) {
        return Arguments.of(label, Expectation.OPERATION_DIGEST_DIFFERS, change);
    }

    private static Arguments mountRow(String label, Consumer<PublicationSpec> change) {
        return Arguments.of(label, Expectation.MOUNT_PART_DIFFERS, change);
    }

    /**
     * Every value one publication is built from, holding the base's values until a row changes one.
     * {@link #build()} turns them into new publication records on every call.
     */
    static final class PublicationSpec {
        String mountPath = PublicApi.MOUNT_PATH;
        String strategyId = "none";
        String applicationName = PublicApi.NAME;
        Class<?> declaringType = PublicApi.class;
        String httpMethod = "POST";
        String jaxRsPathTemplate = "/items";
        String vertxRouteValue = "/items";
        boolean vertxRouteIsRegex = false;
        SecurityPolicy effectivePolicy = new SecurityPolicy.None();
        List<SecurityRequirementSet> requirementSets = List.of();
        boolean requiresAction = false;
        String profileId = PROFILE_ID;
        boolean gateInstalled = true;
        UnaryOperator<JsonObject> bodySchemaChange = UnaryOperator.identity();
        UnaryOperator<JsonObject> parameterSchemaChange = UnaryOperator.identity();
        boolean withProvenance = true;
        InputBinding.Requiredness dryRunRequiredness = InputBinding.Requiredness.NOT_REQUIRED;
        boolean dryRunHidden = false;
        boolean dryRunSchemaEnforced = true;
        String dryRunDefault = null;
        List<String> produces = List.of("application/json");
        String outputProfileId = PROFILE_ID;
        boolean reverseInsertionOrder = false;

        /**
         * Builds a new publication: a new generator and {@code describe} call, new schema objects, new
         * bindings, a new response shape, and a new descriptor double.
         */
        MountPublication build() {
            CanonicalSchema described = AnnotationJsonSchemaGenerator.forInputProfile(inputProfile())
                    .describe(CreateItemRequest.class);
            JsonObject bodySchema = bodySchemaChange.apply(new JsonObject(described.json()));
            JsonObject parameterSchema = parameterSchemaChange.apply(
                    new JsonObject().put("type", "boolean").put("description", "Whether the item is only validated"));
            if (reverseInsertionOrder) {
                bodySchema = reversed(bodySchema);
                parameterSchema = reversed(parameterSchema);
            }
            Map<InputKey, JsonObject> parameters = new LinkedHashMap<>();
            parameters.put(new InputKey(ParamLocation.QUERY, DRY_RUN), parameterSchema);
            CapturedSchemas schemas =
                    new CapturedSchemas(bodySchema, withProvenance ? described.redactionManifest() : null, parameters);
            InputBinding dryRun = new InputBinding(
                    InputBinding.Origin.PARAMETER,
                    ParamLocation.QUERY,
                    DRY_RUN,
                    boolean.class,
                    dryRunDefault,
                    dryRunRequiredness,
                    dryRunHidden,
                    dryRunSchemaEnforced,
                    createItemParameterAnnotations(0),
                    0,
                    null);
            InputBinding body = new InputBinding(
                    InputBinding.Origin.BODY,
                    null,
                    null,
                    CreateItemRequest.class,
                    null,
                    InputBinding.Requiredness.REQUIRED,
                    false,
                    true,
                    createItemParameterAnnotations(1),
                    1,
                    null);
            ResponseShape response =
                    new ResponseShape(void.class, CatalogResource.class, false, true, produces, outputProfileId);
            OperationDetail detail = new OperationDetail(
                    newDescriptor(), profileId, schemas, gateInstalled, List.of(dryRun, body), response);
            OperationPublication operation = new OperationPublication(
                    OPERATION_ID,
                    httpMethod,
                    jaxRsPathTemplate,
                    vertxRouteValue,
                    vertxRouteIsRegex,
                    effectivePolicy,
                    requirementSets,
                    requiresAction,
                    detail);
            return new MountPublication(
                    mountPath, "public-mount", applicationName, declaringType, strategyId, List.of(operation));
        }
    }

    /** A new input-direction profile instance with the given id and a new plain mapper. */
    private static JsonMapperProfile inputProfile() {
        ObjectMapper mapper = new ObjectMapper();
        JsonProfileId id = JsonProfileId.of(PROFILE_ID);
        return new JsonMapperProfile() {
            @Override
            public JsonProfileId id() {
                return id;
            }

            @Override
            public ObjectMapper mapper() {
                return mapper;
            }
        };
    }

    /** The annotations {@link CatalogResource#createItem} declares on the parameter at {@code index}. */
    private static List<Annotation> createItemParameterAnnotations(int index) {
        try {
            return List.of(CatalogResource.class.getMethod("createItem", boolean.class, CreateItemRequest.class)
                    .getParameterAnnotations()[index]);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A new identity-only operation descriptor: it equals only itself, its hash and text are its
     * identity, and every other member fails, since the snapshot reads nothing from the descriptor.
     */
    private static JaxRsOperationDescriptor newDescriptor() {
        return (JaxRsOperationDescriptor) Proxy.newProxyInstance(
                SnapshotRendererTest.class.getClassLoader(),
                new Class<?>[] {JaxRsOperationDescriptor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "descriptor@" + Integer.toHexString(System.identityHashCode(proxy));
                    default ->
                        throw new UnsupportedOperationException(
                                "the snapshot reads no descriptor member: " + method.getName());
                });
    }

    /** A deep copy of {@code object} with the keys of every nested object in reverse insertion order. */
    private static JsonObject reversed(JsonObject object) {
        List<String> names = new ArrayList<>(object.fieldNames());
        Collections.reverse(names);
        JsonObject copy = new JsonObject();
        for (String name : names) {
            copy.put(name, reversedValue(object.getValue(name)));
        }
        return copy;
    }

    private static Object reversedValue(Object value) {
        if (value instanceof JsonObject nested) {
            return reversed(nested);
        }
        if (value instanceof JsonArray array) {
            JsonArray copy = new JsonArray();
            array.forEach(element -> copy.add(reversedValue(element)));
            return copy;
        }
        return value;
    }
}
