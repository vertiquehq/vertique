// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static dev.vertique.rest.jaxrs.publication.InputBinding.Origin.COMPOSITE_FIELD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.core.validation.BeanValidator;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputBinding.Origin;
import dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness;
import dev.vertique.rest.jaxrs.publication.InputKey;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.publication.ResponseShape;
import dev.vertique.rest.jaxrs.publication.fixture.CountingSchemaSource;
import dev.vertique.rest.jaxrs.publication.fixture.RecordingSink;
import dev.vertique.rest.jaxrs.publication.fixture.RecordingValidationStrategy;
import dev.vertique.rest.jaxrs.publication.inventory.Conversions;
import dev.vertique.rest.jaxrs.publication.inventory.Filters;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedConversions;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedFilters;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedHiddenComponentParams;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedHiddenFieldBean;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedHiddenInputsResource;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedHiddenTypeBean;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedHiddenTypeParams;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedKindsResource;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedOrdersResource;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedPaging;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedPropagatedBean;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedPropagatedParams;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedSearchParams;
import dev.vertique.rest.jaxrs.publication.inventory.GeneratedSequencedResource;
import dev.vertique.rest.jaxrs.publication.inventory.HiddenComponentParams;
import dev.vertique.rest.jaxrs.publication.inventory.HiddenFieldBean;
import dev.vertique.rest.jaxrs.publication.inventory.HiddenInputsResource;
import dev.vertique.rest.jaxrs.publication.inventory.HiddenTypeBean;
import dev.vertique.rest.jaxrs.publication.inventory.HiddenTypeParams;
import dev.vertique.rest.jaxrs.publication.inventory.InventoryApi;
import dev.vertique.rest.jaxrs.publication.inventory.KindsResource;
import dev.vertique.rest.jaxrs.publication.inventory.NoViolationsBeanValidator;
import dev.vertique.rest.jaxrs.publication.inventory.OrdersResource;
import dev.vertique.rest.jaxrs.publication.inventory.Paging;
import dev.vertique.rest.jaxrs.publication.inventory.PropagatedBean;
import dev.vertique.rest.jaxrs.publication.inventory.PropagatedParams;
import dev.vertique.rest.jaxrs.publication.inventory.SearchParams;
import dev.vertique.rest.jaxrs.publication.inventory.SequencedResource;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamRegistry;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistry;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit proofs that the operation inventory is the same on the generated and the reflection
 * descriptor paths: hidden inputs are flagged identically on both, every binding fact agrees
 * between a reflection resource and its generated-shape twin, and an application mount carries the
 * same inventory as a plain mount.
 *
 * <p>Each generated-shape twin is a distinct class with hand-written {@code _JaxRsDescriptor} and
 * {@code _BeanParamModel} companions, so its composites are distinct classes too: a composite type
 * is compared through the fixed {@link #GENERATED_TO_REFLECTION_COMPOSITES} map, and each path is
 * also checked against its own fixed composite classes. Every proof builds routers through a bare
 * {@link TestFactories}-built factory with a recording sink that wants detail for every mount, and
 * reads the recorded publication; no server is started and no request is sent.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OperationInventoryParityTest {

    private static final int BUILD_TIMEOUT_SECONDS = 10;

    /** The generated-shape twin composite → its reflection-path counterpart. */
    private static final Map<Class<?>, Class<?>> GENERATED_TO_REFLECTION_COMPOSITES = Map.of(
            GeneratedFilters.class, Filters.class,
            GeneratedSearchParams.class, SearchParams.class,
            GeneratedConversions.class, Conversions.class,
            GeneratedPaging.class, Paging.class);

    /** Operation id → the composite classes its reflection-path inventory names, in binding order. */
    private static final Map<String, List<Class<?>>> REFLECTION_COMPOSITES = Map.of(
            "createOrder", List.of(Filters.class, SearchParams.class),
            "addNote", List.of(),
            "updateAudit", List.of(),
            "listConversions", List.of(Conversions.class),
            "listSequenced", List.of(),
            "listKinds", List.of(Paging.class));

    /** Operation id → the composite classes its generated-path inventory names, in binding order. */
    private static final Map<String, List<Class<?>>> GENERATED_COMPOSITES = Map.of(
            "createOrder", List.of(GeneratedFilters.class, GeneratedSearchParams.class),
            "addNote", List.of(),
            "updateAudit", List.of(),
            "listConversions", List.of(GeneratedConversions.class),
            "listSequenced", List.of(),
            "listKinds", List.of(GeneratedPaging.class));

    /**
     * The collection-typed query inputs of {@code listKinds}, whose {@code type} legitimately differs
     * by path: generated descriptors carry no generic type for a non-body parameter, so the generated
     * path reports the raw {@code List}, while the reflection path reports the declared
     * {@code List<String>}. These rows compare by raw class, and each path is checked against its own
     * fixed expected type; every other row compares {@code type} exactly.
     */
    private static final Set<String> RAW_TYPE_ROWS = Set.of("tags", "ids");

    /** Operation id → the number of bindings its inventory lists, on either path. */
    private static final Map<String, Integer> BINDING_COUNTS = Map.of(
            "createOrder", 12,
            "addNote", 1,
            "updateAudit", 3,
            "listConversions", 2,
            "listSequenced", 1,
            "listKinds", 12);

    /** Operation id → its resource class on the reflection path. */
    private static final Map<String, Class<?>> REFLECTION_RESOURCE_CLASSES = Map.of(
            "createOrder", OrdersResource.class,
            "addNote", OrdersResource.class,
            "updateAudit", OrdersResource.class,
            "listConversions", OrdersResource.class,
            "listSequenced", SequencedResource.class,
            "listKinds", KindsResource.class);

    /** Operation id → its resource class on the generated path. */
    private static final Map<String, Class<?>> GENERATED_RESOURCE_CLASSES = Map.of(
            "createOrder", GeneratedOrdersResource.class,
            "addNote", GeneratedOrdersResource.class,
            "updateAudit", GeneratedOrdersResource.class,
            "listConversions", GeneratedOrdersResource.class,
            "listSequenced", GeneratedSequencedResource.class,
            "listKinds", GeneratedKindsResource.class);

    // ---------------------------------------------------------------------------------------
    // Hidden inputs
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("Hidden inputs are flagged on both descriptor paths, and are still bound and validated")
    void hiddenInputsAreFlaggedOnBothDescriptorPaths(Vertx vertx) throws Exception {
        // Given: one resource per path, annotated identically, and a schema for query debug only
        OperationSchemaSource debugSchema = new CountingSchemaSource((op, call) -> OperationSchemas.builder()
                .parameterSchema(ParamLocation.QUERY, "debug", new JsonObject().put("type", "string"))
                .build());

        // Given: the reflection twin has no companion, the generated twin and its composites do
        assertTrue(
                GeneratedJaxRsDescriptorRegistry.shared()
                        .lookup(HiddenInputsResource.class)
                        .isEmpty(),
                "no descriptor companion for the reflection twin");
        assertTrue(
                GeneratedJaxRsDescriptorRegistry.shared()
                        .lookup(GeneratedHiddenInputsResource.class)
                        .isPresent(),
                "a descriptor companion for the generated twin");
        for (Class<?> composite : List.of(
                HiddenFieldBean.class,
                HiddenComponentParams.class,
                PropagatedBean.class,
                PropagatedParams.class,
                HiddenTypeBean.class,
                HiddenTypeParams.class)) {
            assertTrue(
                    GeneratedJaxRsBeanParamRegistry.shared().lookup(composite).isEmpty(),
                    "no bean-param model for " + composite.getSimpleName());
        }
        for (Class<?> composite : List.of(
                GeneratedHiddenFieldBean.class,
                GeneratedHiddenComponentParams.class,
                GeneratedPropagatedBean.class,
                GeneratedPropagatedParams.class,
                GeneratedHiddenTypeBean.class,
                GeneratedHiddenTypeParams.class)) {
            assertTrue(
                    GeneratedJaxRsBeanParamRegistry.shared().lookup(composite).isPresent(),
                    "a bean-param model for " + composite.getSimpleName());
        }

        // When: both mounts are built with a sink wanting detail and a gate installed
        MountPublication reflection =
                buildMount(vertx, gatedFactory(debugSchema, Optional.empty()), Set.of(new HiddenInputsResource()));
        MountPublication generated = buildMount(
                vertx, gatedFactory(debugSchema, Optional.empty()), Set.of(new GeneratedHiddenInputsResource()));

        // Then: one expectation, shared by both paths
        Map<String, Boolean> expectedHidden = Map.ofEntries(
                Map.entry("debug", true), // @Parameter(hidden = true) on the parameter
                Map.entry("verbose", false), // @Parameter(hidden = false) on the parameter
                Map.entry("X-Internal", true), // @Schema(hidden = true) on the parameter
                Map.entry("internal", true), // @Hidden on the bean field
                Map.entry("visible", false), // unmarked bean field
                Map.entry("override", true), // @Schema(hidden = true) on the record component
                Map.entry("mode", false), // @Schema(hidden = false) on the record component
                Map.entry("secret", true), // @Schema(hidden = true) on a component with an explicit accessor
                Map.entry("h1", true), // bean parameter marked @Parameter(hidden = true)
                Map.entry("X-H2", true), // bean parameter marked @Parameter(hidden = true)
                Map.entry("h3", true), // record parameter marked @Schema(hidden = true)
                Map.entry("h4", true), // record parameter marked @Schema(hidden = true)
                Map.entry("h5", true), // bean type marked @Hidden
                Map.entry("h6", true)); // record type marked @Hidden

        for (Map.Entry<String, MountPublication> path :
                List.of(Map.entry("reflection", reflection), Map.entry("generated", generated))) {
            String variant = path.getKey();
            OperationDetail detail = detailOf(path.getValue(), "listHidden", variant);
            assertEquals(
                    new TreeMap<>(expectedHidden),
                    new TreeMap<>(hiddenByName(detail.inputs(), variant)),
                    variant + ": name → hidden");

            // Then: the hidden input is still bound and validated: its descriptor parameter and
            // its captured schema are unchanged
            assertTrue(
                    detail.descriptor().parameters().stream()
                            .map(ParamDescriptor::name)
                            .anyMatch("debug"::equals),
                    variant + ": descriptor still binds debug");
            assertTrue(
                    detail.schemas().parameters().containsKey(new InputKey(ParamLocation.QUERY, "debug")),
                    variant + ": captured schema for debug");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Generated and reflection parity
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("Generated and reflection inventories agree, and an application mount carries the same inventory")
    void generatedAndReflectionInventoriesAgree(Vertx vertx) throws Exception {
        // Given: one schema source and one bound validator for every mount
        OperationSchemaSource schemas = paritySchemas();
        BeanValidator validator = new NoViolationsBeanValidator();

        // Given: the reflection twins have no companions, the generated twins and composites do
        for (Class<?> resource : List.of(OrdersResource.class, SequencedResource.class, KindsResource.class)) {
            assertTrue(
                    GeneratedJaxRsDescriptorRegistry.shared().lookup(resource).isEmpty(),
                    "no descriptor companion for " + resource.getSimpleName());
        }
        for (Class<?> resource : List.of(
                GeneratedOrdersResource.class, GeneratedSequencedResource.class, GeneratedKindsResource.class)) {
            assertTrue(
                    GeneratedJaxRsDescriptorRegistry.shared().lookup(resource).isPresent(),
                    "a descriptor companion for " + resource.getSimpleName());
        }
        for (Map.Entry<Class<?>, Class<?>> twins : GENERATED_TO_REFLECTION_COMPOSITES.entrySet()) {
            assertTrue(
                    GeneratedJaxRsBeanParamRegistry.shared()
                            .lookup(twins.getKey())
                            .isPresent(),
                    "a bean-param model for " + twins.getKey().getSimpleName());
            assertTrue(
                    GeneratedJaxRsBeanParamRegistry.shared()
                            .lookup(twins.getValue())
                            .isEmpty(),
                    "no bean-param model for " + twins.getValue().getSimpleName());
        }

        // When: the reflection twins, the generated twins, and the reflection twins again on an
        // application mount are built with a sink wanting detail
        MountPublication reflection = buildMount(
                vertx,
                gatedFactory(schemas, Optional.of(validator)),
                Set.of(new OrdersResource(), new SequencedResource(), new KindsResource()));
        MountPublication generated = buildMount(
                vertx,
                gatedFactory(schemas, Optional.of(validator)),
                Set.of(new GeneratedOrdersResource(), new GeneratedSequencedResource(), new GeneratedKindsResource()));
        MountPublication application = buildApplicationMount(
                vertx,
                gatedFactory(schemas, Optional.of(validator)),
                Set.of(new OrdersResource(), new SequencedResource(), new KindsResource()));

        // Then: both paths publish the same operations
        assertEquals(BINDING_COUNTS.keySet(), operationsById(reflection).keySet(), "reflection operation ids");
        assertEquals(BINDING_COUNTS.keySet(), operationsById(generated).keySet(), "generated operation ids");

        for (String operationId : BINDING_COUNTS.keySet()) {
            OperationDetail reflected = detailOf(reflection, operationId, "reflection");
            OperationDetail described = detailOf(generated, operationId, "generated");

            // Then: neither inventory is empty or short, and each names its own composites
            assertEquals(
                    BINDING_COUNTS.get(operationId),
                    reflected.inputs().size(),
                    "reflection binding count of " + operationId);
            assertEquals(
                    REFLECTION_COMPOSITES.get(operationId),
                    compositeTypes(reflected.inputs()),
                    "reflection composite classes of " + operationId);
            assertEquals(
                    GENERATED_COMPOSITES.get(operationId),
                    compositeTypes(described.inputs()),
                    "generated composite classes of " + operationId);

            // Then: each path reports its own fixed type for the collection-typed query inputs
            if (operationId.equals("listKinds")) {
                for (String name : RAW_TYPE_ROWS) {
                    assertListOfString(typeOf(reflected.inputs(), name), "reflection type of " + name);
                    assertSame(List.class, typeOf(described.inputs(), name), "generated type of " + name);
                }
            }

            // Then: the inventories agree binding by binding, composite classes mapped by twin and
            // the collection-typed rows compared by raw class
            assertEquals(
                    views(reflected.inputs(), false, RAW_TYPE_ROWS),
                    views(described.inputs(), true, RAW_TYPE_ROWS),
                    "generated vs reflection inputs of " + operationId);

            // Then: the responses agree except for the resource class, which is each twin's own
            ResponseShape reflectedResponse = reflected.response();
            ResponseShape describedResponse = described.response();
            assertEquals(
                    ResponseView.withoutResourceClass(reflectedResponse),
                    ResponseView.withoutResourceClass(describedResponse),
                    "generated vs reflection response of " + operationId);
            assertSame(
                    REFLECTION_RESOURCE_CLASSES.get(operationId),
                    reflectedResponse.resourceClass(),
                    "reflection resource class of " + operationId);
            assertSame(
                    GENERATED_RESOURCE_CLASSES.get(operationId),
                    describedResponse.resourceClass(),
                    "generated resource class of " + operationId);
        }

        // Then: no binding on either path claims enforcement for a composite field
        for (MountPublication publication : List.of(reflection, generated, application)) {
            for (OperationPublication operation : publication.operations()) {
                for (InputBinding binding : detailOf(publication, operation.operationId(), publication.mountPath())
                        .inputs()) {
                    if (binding.origin() == COMPOSITE_FIELD) {
                        assertFalse(
                                binding.schemaEnforced(),
                                publication.mountPath() + ": composite field " + binding.name() + " of "
                                        + operation.operationId() + " claims enforcement");
                    }
                }
            }
        }

        // Then: the application mount names its application and carries the reflection twins'
        // inventory and response in every component, the resource class included
        assertEquals("inventory", application.applicationName(), "application name");
        assertSame(InventoryApi.class, application.declaringType(), "declaring type");
        assertEquals(BINDING_COUNTS.keySet(), operationsById(application).keySet(), "application operation ids");
        for (String operationId : BINDING_COUNTS.keySet()) {
            OperationDetail plain = detailOf(reflection, operationId, "reflection");
            OperationDetail declared = detailOf(application, operationId, "application");
            assertEquals(
                    views(plain.inputs(), false, Set.of()),
                    views(declared.inputs(), false, Set.of()),
                    "application vs plain inputs of " + operationId);
            assertEquals(
                    ResponseView.of(plain.response()),
                    ResponseView.of(declared.response()),
                    "application vs plain response of " + operationId);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Mount building and reading
    // ---------------------------------------------------------------------------------------

    /**
     * A factory with the recording strategy (a gate is always installed), the given schema source,
     * and the given validator.
     */
    private static TestFactories.Builder gatedFactory(OperationSchemaSource source, Optional<BeanValidator> validator) {
        return TestFactories.builder()
                .validationStrategies(Set.of(new RecordingValidationStrategy()))
                .jaxRsConfig(JaxRsConfig.builder()
                        .validationStrategy(RecordingValidationStrategy.ID)
                        .build())
                .operationSchemaSource(Optional.of(source))
                .beanValidator(validator);
    }

    /**
     * Builds one plain mount at {@code /*} with its own sink wanting detail for every mount, and
     * returns the single publication it recorded.
     */
    private static MountPublication buildMount(Vertx vertx, TestFactories.Builder builder, Set<Object> resources)
            throws Exception {
        RecordingSink sink = new RecordingSink(applicationName -> true);
        JaxRsRouterMount.Factory factory =
                builder.publicationSinks(new LinkedHashSet<>(List.of(sink))).build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", resources);
        awaitRouter(vertx, mount);
        return sink.onlyReceived();
    }

    /**
     * Builds the {@code inventory} application's mount through the package-private
     * application-mount factory method, marks it validated as the composition validator would, and
     * returns the single publication its own sink recorded.
     */
    private static MountPublication buildApplicationMount(
            Vertx vertx, TestFactories.Builder builder, Set<Object> resources) throws Exception {
        RecordingSink sink = new RecordingSink(applicationName -> true);
        JaxRsRouterMount.Factory factory =
                builder.publicationSinks(new LinkedHashSet<>(List.of(sink))).build();
        JaxRsRouterMount mount = factory.createApplicationMount(
                "/api/inventory/*", "openapi.json", resources, "inventory", InventoryApi.class);
        mount.markValidated();
        awaitRouter(vertx, mount);
        return sink.onlyReceived();
    }

    private static void awaitRouter(Vertx vertx, JaxRsRouterMount mount) throws Exception {
        mount.createRouter(vertx)
                .toCompletionStage()
                .toCompletableFuture()
                .get(BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static Map<String, OperationPublication> operationsById(MountPublication publication) {
        Map<String, OperationPublication> byId = new LinkedHashMap<>();
        for (OperationPublication operation : publication.operations()) {
            byId.put(operation.operationId(), operation);
        }
        return byId;
    }

    /**
     * Returns one operation's detail, failing by assertion unless it carries a non-empty inventory
     * and a response shape.
     */
    private static OperationDetail detailOf(MountPublication publication, String operationId, String variant) {
        OperationPublication operation = operationsById(publication).get(operationId);
        assertNotNull(operation, variant + ": operation " + operationId);
        OperationDetail detail = operation.detail();
        assertNotNull(detail, variant + ": detail of " + operationId);
        assertNotNull(detail.inputs(), variant + ": inputs of " + operationId);
        assertFalse(detail.inputs().isEmpty(), variant + ": inputs of " + operationId + " must not be empty");
        assertNotNull(detail.response(), variant + ": response of " + operationId);
        return detail;
    }

    /** Maps each binding's bound name to its hidden flag, failing on a nameless or repeated name. */
    private static Map<String, Boolean> hiddenByName(List<InputBinding> inputs, String variant) {
        Map<String, Boolean> hidden = new LinkedHashMap<>();
        for (InputBinding binding : inputs) {
            assertNotNull(binding.name(), variant + ": a " + binding.origin() + " binding without a name");
            if (hidden.put(binding.name(), binding.hidden()) != null) {
                fail(variant + ": repeated binding name " + binding.name());
            }
        }
        return hidden;
    }

    /** The composite classes the inventory names, each once, in binding order. */
    private static List<Class<?>> compositeTypes(List<InputBinding> inputs) {
        List<Class<?>> composites = new ArrayList<>();
        for (InputBinding binding : inputs) {
            if (binding.origin() == COMPOSITE_FIELD) {
                assertNotNull(binding.compositeType(), "composite type of " + binding.name());
                if (!composites.contains(binding.compositeType())) {
                    composites.add(binding.compositeType());
                }
            } else {
                assertNull(binding.compositeType(), "composite type of a " + binding.origin() + " binding");
            }
        }
        return composites;
    }

    // ---------------------------------------------------------------------------------------
    // Comparable views
    // ---------------------------------------------------------------------------------------

    /** The {@code type} of the single binding with the given bound name. */
    private static Type typeOf(List<InputBinding> inputs, String name) {
        List<InputBinding> matches =
                inputs.stream().filter(b -> name.equals(b.name())).toList();
        assertEquals(1, matches.size(), "bindings named " + name);
        return matches.get(0).type();
    }

    /** Asserts that {@code type} is {@code List<String>}. */
    private static void assertListOfString(Type type, String message) {
        if (!(type instanceof ParameterizedType parameterized)) {
            fail(message + ": expected List<String> but was " + type);
            return;
        }
        assertSame(List.class, parameterized.getRawType(), message + ": raw type");
        assertEquals(
                List.of(String.class), List.of(parameterized.getActualTypeArguments()), message + ": type argument");
    }

    /** The raw class of a type, for the rows whose type is compared by raw class. */
    private static Type rawClass(Type type) {
        return type instanceof ParameterizedType parameterized ? parameterized.getRawType() : type;
    }

    /**
     * Projects each binding to a comparable view; on the generated path, a composite type is first
     * mapped to its reflection-path twin through {@link #GENERATED_TO_REFLECTION_COMPOSITES}, and the
     * type of every binding named in {@code rawTypeRows} is reduced to its raw class.
     */
    private static List<BindingView> views(List<InputBinding> inputs, boolean generatedPath, Set<String> rawTypeRows) {
        List<BindingView> views = new ArrayList<>(inputs.size());
        for (InputBinding binding : inputs) {
            Class<?> composite = binding.compositeType();
            if (generatedPath && composite != null) {
                composite = GENERATED_TO_REFLECTION_COMPOSITES.getOrDefault(composite, composite);
            }
            // The body binding has no name, and Set.contains rejects null
            boolean rawRow = binding.name() != null && rawTypeRows.contains(binding.name());
            Type type = rawRow ? rawClass(binding.type()) : binding.type();
            views.add(new BindingView(
                    binding.origin(),
                    binding.location(),
                    binding.name(),
                    type,
                    binding.defaultValue(),
                    binding.requiredness(),
                    binding.hidden(),
                    binding.schemaEnforced(),
                    List.copyOf(binding.annotations()),
                    binding.methodParameterIndex(),
                    composite));
        }
        return views;
    }

    /** Every {@link InputBinding} component; annotations compare by {@code Annotation.equals}. */
    private record BindingView(
            Origin origin,
            @Nullable ParamLocation location,
            @Nullable String name,
            Type type,
            @Nullable String defaultValue,
            Requiredness requiredness,
            boolean hidden,
            boolean schemaEnforced,
            List<Annotation> annotations,
            @Nullable Integer methodParameterIndex,
            @Nullable Class<?> compositeType) {}

    /** Every {@link ResponseShape} component; {@code resourceClass} {@code null} when excluded. */
    private record ResponseView(
            Type genericReturnType,
            @Nullable Class<?> resourceClass,
            boolean returnsFuture,
            boolean returnsVoid,
            List<String> produces,
            String outputProfileId) {

        static ResponseView of(ResponseShape shape) {
            return new ResponseView(
                    shape.genericReturnType(),
                    shape.resourceClass(),
                    shape.returnsFuture(),
                    shape.returnsVoid(),
                    List.copyOf(shape.produces()),
                    shape.outputProfileId());
        }

        static ResponseView withoutResourceClass(ResponseShape shape) {
            return new ResponseView(
                    shape.genericReturnType(),
                    null,
                    shape.returnsFuture(),
                    shape.returnsVoid(),
                    List.copyOf(shape.produces()),
                    shape.outputProfileId());
        }
    }

    /**
     * A source returning schemas for the body, path {@code id}, and queries {@code q},
     * {@code region}, and {@code tags}, so enforcement differs across the compared inputs.
     */
    private static OperationSchemaSource paritySchemas() {
        return new CountingSchemaSource((op, call) -> OperationSchemas.builder()
                .bodySchema(new JsonObject().put("type", "object"))
                .parameterSchema(ParamLocation.PATH, "id", new JsonObject().put("type", "string"))
                .parameterSchema(ParamLocation.QUERY, "q", new JsonObject().put("type", "integer"))
                .parameterSchema(ParamLocation.QUERY, "region", new JsonObject().put("type", "string"))
                .parameterSchema(ParamLocation.QUERY, "tags", new JsonObject().put("type", "array"))
                .build());
    }
}
