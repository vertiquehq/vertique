// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static dev.vertique.rest.jaxrs.publication.InputBinding.Origin.BODY;
import static dev.vertique.rest.jaxrs.publication.InputBinding.Origin.COMPOSITE_FIELD;
import static dev.vertique.rest.jaxrs.publication.InputBinding.Origin.PARAMETER;
import static dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness.NOT_REQUIRED;
import static dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness.REQUIRED;
import static dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness.UNKNOWN;
import static dev.vertique.rest.jaxrs.routing.ParamLocation.COOKIE;
import static dev.vertique.rest.jaxrs.routing.ParamLocation.FORM;
import static dev.vertique.rest.jaxrs.routing.ParamLocation.HEADER;
import static dev.vertique.rest.jaxrs.routing.ParamLocation.PATH;
import static dev.vertique.rest.jaxrs.routing.ParamLocation.QUERY;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputBinding.Origin;
import dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.publication.ResponseShape;
import dev.vertique.rest.jaxrs.publication.fixture.CountingSchemaSource;
import dev.vertique.rest.jaxrs.publication.fixture.RecordingSink;
import dev.vertique.rest.jaxrs.publication.fixture.RecordingValidationStrategy;
import dev.vertique.rest.jaxrs.publication.inventory.BlankHiddenInputResource;
import dev.vertique.rest.jaxrs.publication.inventory.BodyOnlyResource;
import dev.vertique.rest.jaxrs.publication.inventory.Conversions;
import dev.vertique.rest.jaxrs.publication.inventory.CrudBase;
import dev.vertique.rest.jaxrs.publication.inventory.Filters;
import dev.vertique.rest.jaxrs.publication.inventory.Item;
import dev.vertique.rest.jaxrs.publication.inventory.ItemCrudResource;
import dev.vertique.rest.jaxrs.publication.inventory.KindsResource;
import dev.vertique.rest.jaxrs.publication.inventory.LocationHiddenInputsResource;
import dev.vertique.rest.jaxrs.publication.inventory.MethodHiddenBean;
import dev.vertique.rest.jaxrs.publication.inventory.MethodHiddenInputsResource;
import dev.vertique.rest.jaxrs.publication.inventory.MismatchedHiddenInputResource;
import dev.vertique.rest.jaxrs.publication.inventory.NoViolationsBeanValidator;
import dev.vertique.rest.jaxrs.publication.inventory.Order;
import dev.vertique.rest.jaxrs.publication.inventory.OrdersResource;
import dev.vertique.rest.jaxrs.publication.inventory.Paging;
import dev.vertique.rest.jaxrs.publication.inventory.PrimitiveKindsResource;
import dev.vertique.rest.jaxrs.publication.inventory.PrimitiveLimits;
import dev.vertique.rest.jaxrs.publication.inventory.ResponsesResource;
import dev.vertique.rest.jaxrs.publication.inventory.SearchParams;
import dev.vertique.rest.jaxrs.publication.inventory.SequencedResource;
import dev.vertique.rest.jaxrs.publication.inventory.UnloadableGroup;
import dev.vertique.rest.jaxrs.publication.inventory.UnmatchedHiddenInputResource;
import dev.vertique.rest.jaxrs.publication.inventory.UnprofiledResponsesResource;
import dev.vertique.rest.jaxrs.publication.inventory.UnreadableGroupsResource;
import dev.vertique.rest.jaxrs.publication.inventory.UnsafeNameHiddenInputResource;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamRegistry;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistry;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Unit proofs for the operation inventory and response shape a mount's {@code OperationDetail}
 * carries: every bound input with its binding facts and three-valued requiredness, the request
 * body's location-less {@code UNKNOWN} binding, and the response's return type, resource class,
 * flags, produces types, and output profile.
 *
 * <p>Every proof builds routers through a bare {@link TestFactories}-built factory with a recording
 * sink that wants detail for every mount, and reads the recorded publication; no server is started
 * and no request is sent. Expectations are fixed literals, or read by reflection from the fixture
 * classes themselves, never from the scanner, the descriptor adapter, or the registrar.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OperationInventoryTest {

    private static final int BUILD_TIMEOUT_SECONDS = 10;

    // ---------------------------------------------------------------------------------------
    // Bound inputs and their binding facts
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("The inventory lists every bound input with its binding facts, in declaration order")
    void inventoryListsBoundInputsWithBindingFacts(Vertx vertx) throws Exception {
        // Given: a schema source with schemas for path id, query q, and the body only
        // Given: the recording strategy (a gate is always installed) and a bound validator
        MountPublication validated = buildMount(
                vertx,
                TestFactories.builder()
                        .validationStrategies(Set.of(new RecordingValidationStrategy()))
                        .jaxRsConfig(JaxRsConfig.builder()
                                .validationStrategy(RecordingValidationStrategy.ID)
                                .build())
                        .operationSchemaSource(Optional.of(idQueryAndBodySchemas()))
                        .beanValidator(Optional.of(new NoViolationsBeanValidator())),
                Set.of(new OrdersResource(), new SequencedResource()));
        // Given: the same resources with the recording strategy and no validator bound
        MountPublication unvalidated = buildMount(
                vertx,
                TestFactories.builder()
                        .validationStrategies(Set.of(new RecordingValidationStrategy()))
                        .jaxRsConfig(JaxRsConfig.builder()
                                .validationStrategy(RecordingValidationStrategy.ID)
                                .build())
                        .operationSchemaSource(Optional.of(idQueryAndBodySchemas())),
                Set.of(new OrdersResource(), new SequencedResource()));
        // Given: the same resources under the none strategy, validator bound, same source
        MountPublication ungated = buildMount(
                vertx,
                TestFactories.builder()
                        .operationSchemaSource(Optional.of(idQueryAndBodySchemas()))
                        .beanValidator(Optional.of(new NoViolationsBeanValidator())),
                Set.of(new OrdersResource(), new SequencedResource()));

        // Guard: both resources run on the reflection path; neither composite has a companion.
        assertTrue(GeneratedJaxRsDescriptorRegistry.shared()
                .lookup(OrdersResource.class)
                .isEmpty());
        assertTrue(GeneratedJaxRsDescriptorRegistry.shared()
                .lookup(SequencedResource.class)
                .isEmpty());
        assertTrue(
                GeneratedJaxRsBeanParamRegistry.shared().lookup(Filters.class).isEmpty());
        assertTrue(GeneratedJaxRsBeanParamRegistry.shared()
                .lookup(SearchParams.class)
                .isEmpty());

        // Then: with a validator bound and a gate installed
        Map<String, List<Row>> expected = new LinkedHashMap<>();
        Expect create = expect(OrdersResource.class, "createOrder");
        expected.put(
                "createOrder",
                List.of(
                        create.param(0, PATH, "id", String.class).is(REQUIRED).enforced(),
                        create.param(1, QUERY, "q", int.class)
                                .defaults("10")
                                .is(NOT_REQUIRED)
                                .enforced(),
                        create.param(2, HEADER, "X-Trace", String.class).is(NOT_REQUIRED),
                        create.param(3, COOKIE, "session", String.class).is(NOT_REQUIRED),
                        // index 4 is the @Context RoutingContext: no binding
                        create.field(5, QUERY, "limit", Integer.class, Filters.class, "limit")
                                .defaults("20")
                                .is(NOT_REQUIRED),
                        create.field(5, HEADER, "X-Tenant", String.class, Filters.class, "tenant")
                                .is(REQUIRED),
                        create.body(6, Order.class).is(UNKNOWN).enforced(),
                        create.field(7, QUERY, "sort", String.class, SearchParams.class, "sort")
                                .is(NOT_REQUIRED),
                        create.field(7, QUERY, "page", int.class, SearchParams.class, "page")
                                .defaults("1")
                                .is(NOT_REQUIRED),
                        create.field(7, QUERY, "owner", String.class, SearchParams.class, "owner")
                                .is(UNKNOWN),
                        create.param(8, QUERY, "region", String.class).is(REQUIRED),
                        create.param(9, QUERY, "audit", String.class).is(UNKNOWN)));
        Expect note = expect(OrdersResource.class, "addNote");
        expected.put(
                "addNote", List.of(note.param(0, FORM, "note", String.class).is(NOT_REQUIRED)));
        Expect audit = expect(OrdersResource.class, "updateAudit");
        expected.put(
                "updateAudit",
                List.of(
                        audit.param(0, PATH, "id", String.class).is(REQUIRED).enforced(),
                        audit.param(1, QUERY, "audit", String.class).is(REQUIRED),
                        audit.param(2, QUERY, "region", String.class).is(UNKNOWN)));
        Expect conversions = expect(OrdersResource.class, "listConversions");
        expected.put(
                "listConversions",
                List.of(
                        conversions
                                .field(0, QUERY, "mode", String.class, Conversions.class, "mode")
                                .is(UNKNOWN),
                        conversions.param(1, QUERY, "channel", String.class).is(REQUIRED)));
        Expect sequenced = expect(SequencedResource.class, "listSequenced");
        expected.put(
                "listSequenced",
                List.of(sequenced.param(0, QUERY, "region", String.class).is(UNKNOWN)));

        Map<String, Class<?>> expectedResourceClasses = Map.of(
                "createOrder", OrdersResource.class,
                "addNote", OrdersResource.class,
                "updateAudit", OrdersResource.class,
                "listConversions", OrdersResource.class,
                "listSequenced", SequencedResource.class);

        assertInventories(expected, validated, "validator bound, gate installed");
        assertResourceClasses(expectedResourceClasses, validated);

        // Then: without a validator only requiredness changes, by the stated rule
        assertInventories(
                transform(expected, OperationInventoryTest::withoutValidator), unvalidated, "no validator bound");
        assertResourceClasses(expectedResourceClasses, unvalidated);

        // Then: under none nothing is enforced; everything else is unchanged
        assertInventories(transform(expected, row -> row.withEnforced(false)), ungated, "none strategy");
        assertResourceClasses(expectedResourceClasses, ungated);

        // Then: only the body binding lacks a location and a name
        for (MountPublication publication : List.of(validated, unvalidated, ungated)) {
            for (OperationPublication operation : publication.operations()) {
                for (InputBinding binding : operation.detail().inputs()) {
                    if (binding.origin() == BODY) {
                        assertNull(binding.location(), "body location");
                        assertNull(binding.name(), "body name");
                        assertEquals(UNKNOWN, binding.requiredness(), "body requiredness");
                    } else {
                        assertNotNull(binding.location(), "location of " + binding.name());
                        assertNotNull(binding.name(), "name of a " + binding.origin() + " binding");
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Response shape
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("The response shape records the return type, resource class, produces types, and output profile")
    void responseShapeRecordsReturnTypeAndOutputProfile(Vertx vertx) throws Exception {
        // Given: a class-level profile, a method-level profile, and a configured profile
        String configProfileId = "inventory-config-profile";
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of(
                appProfile(ResponsesResource.CLASS_PROFILE_ID),
                appProfile(ResponsesResource.METHOD_PROFILE_ID),
                appProfile(configProfileId)));

        // When: one mount over the profiled, the unprofiled, and the generic-superclass resource
        MountPublication publication = buildMount(
                vertx,
                TestFactories.builder()
                        .jsonMapperProfileRegistry(registry)
                        .jaxRsConfig(JaxRsConfig.builder()
                                .validationStrategy(NoneValidationStrategy.ID)
                                .jsonProfile(configProfileId)
                                .build()),
                Set.of(new ResponsesResource(), new UnprofiledResponsesResource(), new ItemCrudResource()));

        // Then: one row per return shape, keyed by method name (operation id)
        Map<String, ExpectedResponse> expected = new LinkedHashMap<>();
        expected.put(
                "item",
                new ExpectedResponse(
                        Item.class,
                        ResponsesResource.class,
                        false,
                        false,
                        List.of(),
                        ResponsesResource.METHOD_PROFILE_ID));
        expected.put(
                "futureItem",
                new ExpectedResponse(
                        returnType(ResponsesResource.class, "futureItem"),
                        ResponsesResource.class,
                        true,
                        false,
                        List.of(),
                        ResponsesResource.CLASS_PROFILE_ID));
        expected.put(
                "futureItems",
                new ExpectedResponse(
                        returnType(ResponsesResource.class, "futureItems"),
                        ResponsesResource.class,
                        true,
                        false,
                        List.of(),
                        ResponsesResource.CLASS_PROFILE_ID));
        expected.put(
                "nothing",
                new ExpectedResponse(
                        void.class,
                        ResponsesResource.class,
                        false,
                        true,
                        List.of(),
                        ResponsesResource.CLASS_PROFILE_ID));
        expected.put(
                "futureVoid",
                new ExpectedResponse(
                        returnType(ResponsesResource.class, "futureVoid"),
                        ResponsesResource.class,
                        true,
                        true,
                        List.of(),
                        ResponsesResource.CLASS_PROFILE_ID));
        expected.put(
                "response",
                new ExpectedResponse(
                        Response.class,
                        ResponsesResource.class,
                        false,
                        false,
                        List.of(),
                        ResponsesResource.CLASS_PROFILE_ID));
        expected.put(
                "text",
                new ExpectedResponse(
                        String.class,
                        ResponsesResource.class,
                        false,
                        false,
                        List.of("text/plain"),
                        ResponsesResource.CLASS_PROFILE_ID));
        expected.put(
                "plainItem",
                new ExpectedResponse(
                        Item.class, UnprofiledResponsesResource.class, false, false, List.of(), configProfileId));
        expected.put(
                "find",
                new ExpectedResponse(
                        returnType(CrudBase.class, "find"),
                        ItemCrudResource.class,
                        false,
                        false,
                        List.of(),
                        configProfileId));

        Map<String, OperationPublication> byId = operationsById(publication);
        assertEquals(expected.keySet(), byId.keySet(), "operation ids");
        for (Map.Entry<String, ExpectedResponse> row : expected.entrySet()) {
            OperationDetail detail = byId.get(row.getKey()).detail();
            assertNotNull(detail, "detail of " + row.getKey());
            ResponseShape response = detail.response();
            assertNotNull(response, "response of " + row.getKey());
            assertEquals(row.getValue(), ExpectedResponse.of(response), "response of " + row.getKey());
            assertEquals(detail.profileId(), response.outputProfileId(), "output profile of " + row.getKey());
        }

        // Then: the generic return types keep their structure, unresolved
        ParameterizedType futureItem = assertInstanceOf(
                ParameterizedType.class,
                byId.get("futureItem").detail().response().genericReturnType());
        assertEquals(Future.class, futureItem.getRawType());
        assertEquals(List.of(Item.class), List.of(futureItem.getActualTypeArguments()));

        ParameterizedType futureItems = assertInstanceOf(
                ParameterizedType.class,
                byId.get("futureItems").detail().response().genericReturnType());
        assertEquals(Future.class, futureItems.getRawType());
        ParameterizedType listOfItems =
                assertInstanceOf(ParameterizedType.class, futureItems.getActualTypeArguments()[0]);
        assertEquals(List.class, listOfItems.getRawType());
        assertEquals(List.of(Item.class), List.of(listOfItems.getActualTypeArguments()));

        ParameterizedType futureVoid = assertInstanceOf(
                ParameterizedType.class,
                byId.get("futureVoid").detail().response().genericReturnType());
        assertEquals(List.of(Void.class), List.of(futureVoid.getActualTypeArguments()));

        TypeVariable<?> entity = assertInstanceOf(
                TypeVariable.class, byId.get("find").detail().response().genericReturnType());
        assertEquals("T", entity.getName());
        assertEquals(CrudBase.class, entity.getGenericDeclaration());
        assertSame(ItemCrudResource.class, byId.get("find").detail().response().resourceClass());
    }

    // ---------------------------------------------------------------------------------------
    // The body binding
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("The body binding has no location or name and is UNKNOWN whatever the body schema")
    void bodyBindingIsUnknownAndHasNoLocationOrName(Vertx vertx) throws Exception {
        // Given: the five configurations of the absent-body gate characterization, by name
        JsonObject objectSchema = new JsonObject()
                .put("type", "object")
                .put("properties", new JsonObject().put("sku", new JsonObject().put("type", "string")));
        JsonObject objectOrNullSchema =
                new JsonObject().put("type", new JsonArray().add("object").add("null"));
        JsonObject emptySchema = new JsonObject();

        List<BodyCase> cases = List.of(
                // the gate rejects an absent body here only; the inventory still says UNKNOWN
                new BodyCase("canonical generated DTO schema", bodySchemaSource(objectSchema), true, true),
                new BodyCase("object-or-null schema", bodySchemaSource(objectOrNullSchema), true, true),
                new BodyCase("empty schema", bodySchemaSource(emptySchema), true, true),
                new BodyCase("no body schema", bodySchemaSource(null), true, false),
                new BodyCase("none strategy", bodySchemaSource(objectSchema), false, false));

        Expect submit = expect(BodyOnlyResource.class, "submitOrder");
        for (BodyCase bodyCase : cases) {
            TestFactories.Builder builder = TestFactories.builder()
                    .operationSchemaSource(Optional.of(bodyCase.source()))
                    .beanValidator(Optional.of(new NoViolationsBeanValidator()));
            if (bodyCase.gated()) {
                builder.validationStrategies(Set.of(new RecordingValidationStrategy()))
                        .jaxRsConfig(JaxRsConfig.builder()
                                .validationStrategy(RecordingValidationStrategy.ID)
                                .build());
            }

            // When: the mount is built
            MountPublication publication = buildMount(vertx, builder, Set.of(new BodyOnlyResource()));

            // Then: exactly one BODY binding, location and name null, UNKNOWN; enforced per case
            Row expectedBody = submit.body(0, Order.class).is(UNKNOWN).withEnforced(bodyCase.expectEnforced());
            List<InputBinding> inputs = inputsOf(publication, "submitOrder", bodyCase.name());
            assertEquals(List.of(expectedBody), rows(inputs), bodyCase.name());
            InputBinding body = inputs.get(0);
            assertSame(BODY, body.origin(), bodyCase.name());
            assertNull(body.location(), bodyCase.name() + ": location");
            assertNull(body.name(), bodyCase.name() + ": name");
            assertSame(UNKNOWN, body.requiredness(), bodyCase.name() + ": requiredness");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Requiredness kinds
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("Requiredness classifies defaults, collections, arrays, and constraint kinds")
    void requirednessClassifiesDefaultsCollectionsAndConstraintKinds(Vertx vertx) throws Exception {
        // Given: the recording strategy and a bound validator; GET /kinds declares no groups
        MountPublication publication = buildMount(
                vertx,
                TestFactories.builder()
                        .validationStrategies(Set.of(new RecordingValidationStrategy()))
                        .jaxRsConfig(JaxRsConfig.builder()
                                .validationStrategy(RecordingValidationStrategy.ID)
                                .build())
                        .beanValidator(Optional.of(new NoViolationsBeanValidator())),
                Set.of(new KindsResource()));
        assertTrue(GeneratedJaxRsDescriptorRegistry.shared()
                .lookup(KindsResource.class)
                .isEmpty());
        assertTrue(GeneratedJaxRsBeanParamRegistry.shared().lookup(Paging.class).isEmpty());

        // When: the inventory of GET /kinds is read
        List<InputBinding> inputs = inputsOf(publication, "listKinds", "GET /kinds");

        // Then: in declaration order, with these classifications
        assertEquals(
                List.of(
                        "size",
                        "tags",
                        "ids",
                        "codes",
                        "name",
                        "title",
                        "both",
                        "note",
                        "zip",
                        "plain",
                        "pageSize",
                        "filter"),
                inputs.stream().map(InputBinding::name).toList(),
                "bound names in declaration order");
        Map<String, Requiredness> expected = new LinkedHashMap<>();
        expected.put("size", NOT_REQUIRED); // defaulted
        expected.put("tags", NOT_REQUIRED); // collection
        expected.put("ids", UNKNOWN); // collection
        expected.put("codes", REQUIRED); // array
        expected.put("name", REQUIRED); // blank
        expected.put("title", REQUIRED); // empty
        expected.put("both", REQUIRED); // notnull
        expected.put("note", UNKNOWN); // size
        expected.put("zip", UNKNOWN); // composed
        expected.put("plain", NOT_REQUIRED); // unconstrained
        expected.put("pageSize", NOT_REQUIRED); // defaulted
        expected.put("filter", REQUIRED); // blank
        Map<String, Requiredness> actual = new LinkedHashMap<>();
        for (InputBinding binding : inputs) {
            actual.put(binding.name(), binding.requiredness());
        }
        assertEquals(expected, actual);

        // Then: the full binding facts agree with the declaration
        Expect kinds = expect(KindsResource.class, "listKinds");
        assertEquals(
                List.of(
                        kinds.param(0, QUERY, "size", Integer.class)
                                .defaults("5")
                                .is(NOT_REQUIRED),
                        kinds.param(1, QUERY, "tags", genericParameterType(KindsResource.class, "listKinds", 1))
                                .is(NOT_REQUIRED),
                        kinds.param(2, QUERY, "ids", genericParameterType(KindsResource.class, "listKinds", 2))
                                .is(UNKNOWN),
                        kinds.param(3, QUERY, "codes", String[].class).is(REQUIRED),
                        kinds.param(4, QUERY, "name", String.class).is(REQUIRED),
                        kinds.param(5, QUERY, "title", String.class).is(REQUIRED),
                        kinds.param(6, QUERY, "both", String.class).is(REQUIRED),
                        kinds.param(7, QUERY, "note", String.class).is(UNKNOWN),
                        kinds.param(8, QUERY, "zip", String.class).is(UNKNOWN),
                        kinds.param(9, QUERY, "plain", String.class).is(NOT_REQUIRED),
                        kinds.field(10, QUERY, "pageSize", Integer.class, Paging.class, "pageSize")
                                .defaults("50")
                                .is(NOT_REQUIRED),
                        kinds.field(10, QUERY, "filter", String.class, Paging.class, "filter")
                                .is(REQUIRED)),
                rows(inputs));
    }

    @Test
    @DisplayName("Requiredness never claims a missing primitive is accepted or rejected unless it certainly is")
    void requirednessClassifiesPrimitiveInputs(Vertx vertx) throws Exception {
        // Given: GET /primitive-kinds declares no groups; a primitive query parameter without a
        // default, and a @Valid record composite with primitive and explicit-accessor members
        assertTrue(GeneratedJaxRsDescriptorRegistry.shared()
                .lookup(PrimitiveKindsResource.class)
                .isEmpty());
        assertTrue(GeneratedJaxRsBeanParamRegistry.shared()
                .lookup(PrimitiveLimits.class)
                .isEmpty());
        // Given: the recording strategy, once with a bound validator and once without
        MountPublication validated = buildMount(
                vertx,
                TestFactories.builder()
                        .validationStrategies(Set.of(new RecordingValidationStrategy()))
                        .jaxRsConfig(JaxRsConfig.builder()
                                .validationStrategy(RecordingValidationStrategy.ID)
                                .build())
                        .beanValidator(Optional.of(new NoViolationsBeanValidator())),
                Set.of(new PrimitiveKindsResource()));
        MountPublication unvalidated = buildMount(
                vertx,
                TestFactories.builder()
                        .validationStrategies(Set.of(new RecordingValidationStrategy()))
                        .jaxRsConfig(JaxRsConfig.builder()
                                .validationStrategy(RecordingValidationStrategy.ID)
                                .build()),
                Set.of(new PrimitiveKindsResource()));

        // When: the inventory of GET /primitive-kinds is read from both mounts
        List<InputBinding> withValidator = inputsOf(validated, "listPrimitiveKinds", "validator bound");
        List<InputBinding> withoutValidator = inputsOf(unvalidated, "listPrimitiveKinds", "no validator");

        // Then: with a validator bound, in declaration order, with these classifications
        Map<String, Requiredness> expectedWithValidator = new LinkedHashMap<>();
        // an absent primitive parameter cannot be bound, so the call is rejected by conversion
        expectedWithValidator.put("count", UNKNOWN);
        // an absent primitive member binds 0, so @NotNull never fires
        expectedWithValidator.put("page", NOT_REQUIRED);
        // 0 then meets another constraint, whose outcome is not classified
        expectedWithValidator.put("offset", UNKNOWN);
        // @NotNull on the component is read from the backing field of an explicit accessor
        expectedWithValidator.put("region", REQUIRED);

        // Then: without a validator, the primitive parameter is still rejected by conversion,
        // while nothing rejects an absent composite member
        Map<String, Requiredness> expectedWithoutValidator = new LinkedHashMap<>();
        expectedWithoutValidator.put("count", UNKNOWN);
        expectedWithoutValidator.put("page", NOT_REQUIRED);
        expectedWithoutValidator.put("offset", NOT_REQUIRED);
        expectedWithoutValidator.put("region", NOT_REQUIRED);

        // Then: the full binding facts agree with the declaration
        Expect primitives = expect(PrimitiveKindsResource.class, "listPrimitiveKinds");
        List<Row> expectedRows = List.of(
                primitives.param(0, QUERY, "count", int.class).is(UNKNOWN),
                primitives
                        .field(1, QUERY, "page", int.class, PrimitiveLimits.class, "page")
                        .is(NOT_REQUIRED),
                primitives
                        .field(1, QUERY, "offset", int.class, PrimitiveLimits.class, "offset")
                        .is(UNKNOWN),
                primitives
                        .field(1, QUERY, "region", String.class, PrimitiveLimits.class, "region")
                        .withAnnotations(regionAnnotations())
                        .is(REQUIRED));

        assertAll(
                () -> assertEquals(expectedWithValidator, requirednessByName(withValidator), "validator bound"),
                () -> assertEquals(expectedWithoutValidator, requirednessByName(withoutValidator), "no validator"),
                () -> assertEquals(expectedRows, rows(withValidator), "binding facts, validator bound"));
    }

    @Test
    @DisplayName("A composite field binding carries the member annotations its requiredness was judged on")
    void bindingAnnotationsCarryTheMemberAnnotationsRequirednessWasJudgedOn(Vertx vertx) throws Exception {
        // Given: the record composite declares @NotNull on the components page and region; page's
        // annotations propagate to its implicit accessor, while region has an explicit accessor
        // that declares only @QueryParam("region"), so its @NotNull stays on the component/field
        MountPublication mount = buildMount(
                vertx,
                TestFactories.builder()
                        .validationStrategies(Set.of(new RecordingValidationStrategy()))
                        .jaxRsConfig(JaxRsConfig.builder()
                                .validationStrategy(RecordingValidationStrategy.ID)
                                .build())
                        .beanValidator(Optional.of(new NoViolationsBeanValidator())),
                Set.of(new PrimitiveKindsResource()));

        // When: the inventory of GET /primitive-kinds is read
        List<InputBinding> inputs = inputsOf(mount, "listPrimitiveKinds", "member annotations");
        InputBinding region = bindingNamed(inputs, "region");
        InputBinding page = bindingNamed(inputs, "page");

        // Then: region is judged REQUIRED from the component's @NotNull, and its annotations carry
        // that @NotNull as well as the accessor's @QueryParam("region")
        assertAll(
                () -> assertEquals(REQUIRED, region.requiredness(), "region requiredness"),
                () -> assertTrue(
                        hasAnnotation(region, NotNull.class),
                        "region annotations must carry @NotNull: " + region.annotations()),
                () -> assertEquals(
                        List.of("region"),
                        queryParamValues(region),
                        "region annotations must keep @QueryParam(\"region\")"),
                // Then: page carries both its @NotNull and @QueryParam("page")
                () -> assertTrue(
                        hasAnnotation(page, NotNull.class),
                        "page annotations must carry @NotNull: " + page.annotations()),
                () -> assertEquals(List.of("page"), queryParamValues(page), "page annotations keep @QueryParam"));
    }

    private static InputBinding bindingNamed(List<InputBinding> inputs, String name) {
        for (InputBinding binding : inputs) {
            if (name.equals(binding.name())) {
                return binding;
            }
        }
        throw new AssertionError("no binding named " + name);
    }

    private static boolean hasAnnotation(InputBinding binding, Class<? extends Annotation> type) {
        for (Annotation annotation : binding.annotations()) {
            if (annotation.annotationType() == type) {
                return true;
            }
        }
        return false;
    }

    private static List<String> queryParamValues(InputBinding binding) {
        List<String> values = new ArrayList<>();
        for (Annotation annotation : binding.annotations()) {
            if (annotation instanceof jakarta.ws.rs.QueryParam queryParam) {
                values.add(queryParam.value());
            }
        }
        return values;
    }

    @Test
    @DisplayName("A null-rejecting constraint whose groups cannot be read never makes an input required")
    void requirednessTreatsUnreadableConstraintGroupsAsInactive(Vertx vertx) throws Exception {
        // Given: GET /unreadable-groups defined in a class loader that cannot load UnloadableGroup,
        // as when a group class is missing from the runtime class path
        assertTrue(GeneratedJaxRsDescriptorRegistry.shared()
                .lookup(UnreadableGroupsResource.class)
                .isEmpty());
        Class<?> resourceClass = new GroupHidingLoader(
                        OperationInventoryTest.class.getClassLoader(),
                        UnreadableGroupsResource.class,
                        UnloadableGroup.class)
                .loadClass(UnreadableGroupsResource.class.getName());
        Object resource = resourceClass.getConstructor().newInstance();
        // Guard: the resource really comes from that loader, and zone's @NotNull groups cannot be read
        assertFalse(resourceClass == UnreadableGroupsResource.class, "resource class from the hiding loader");
        Annotation zoneNotNull =
                declaredMethod(resourceClass, "listUnreadableGroups").getParameterAnnotations()[0][1];
        assertEquals(NotNull.class, zoneNotNull.annotationType(), "zone's second annotation is @NotNull");
        assertThrows(TypeNotPresentException.class, () -> ((NotNull) zoneNotNull).groups(), "zone's groups()");
        // Given: the recording strategy and a bound validator
        MountPublication publication = buildMount(
                vertx,
                TestFactories.builder()
                        .validationStrategies(Set.of(new RecordingValidationStrategy()))
                        .jaxRsConfig(JaxRsConfig.builder()
                                .validationStrategy(RecordingValidationStrategy.ID)
                                .build())
                        .beanValidator(Optional.of(new NoViolationsBeanValidator())),
                Set.of(resource));

        // When: the inventory of GET /unreadable-groups is read
        List<InputBinding> inputs = inputsOf(publication, "listUnreadableGroups", "unreadable groups");

        // Then: the constraint with unreadable groups counts as inactive, so zone is not required;
        // area's readable Default-group @NotNull makes it required
        Map<String, Requiredness> expected = new LinkedHashMap<>();
        expected.put("zone", UNKNOWN);
        expected.put("area", REQUIRED);
        assertEquals(expected, requirednessByName(inputs), "unreadable groups");
    }

    /**
     * Defines one class itself from its class-file bytes, so annotations on it resolve their class
     * values through this loader, and refuses to load one other class, which then behaves as missing
     * from the class path; every other class is loaded by the parent.
     */
    private static final class GroupHidingLoader extends ClassLoader {

        private final String definedName;
        private final String hiddenName;

        GroupHidingLoader(ClassLoader parent, Class<?> defined, Class<?> hidden) {
            super(parent);
            this.definedName = defined.getName();
            this.hiddenName = hidden.getName();
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.equals(hiddenName)) {
                    throw new ClassNotFoundException(name);
                }
                if (!name.equals(definedName)) {
                    return super.loadClass(name, resolve);
                }
                Class<?> defined = findLoadedClass(name);
                if (defined == null) {
                    String resource = name.replace('.', '/') + ".class";
                    try (InputStream in = getParent().getResourceAsStream(resource)) {
                        if (in == null) {
                            throw new ClassNotFoundException(name);
                        }
                        byte[] bytes = in.readAllBytes();
                        defined = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                if (resolve) {
                    resolveClass(defined);
                }
                return defined;
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Method-level hiding entries
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("A method-level hidden entry hides the input it names by name and location")
    void methodLevelHiddenEntriesHideTheInputsTheyName(Vertx vertx) throws Exception {
        // Given: a reflection-path resource whose operations hide inputs from the method only: a
        // method-level @Parameter, a @Parameters container, @Operation(parameters = ...), and a
        // method-level @Parameter naming a composite's field; the inputs themselves are unmarked
        assertTrue(GeneratedJaxRsDescriptorRegistry.shared()
                .lookup(MethodHiddenInputsResource.class)
                .isEmpty());
        assertTrue(GeneratedJaxRsBeanParamRegistry.shared()
                .lookup(MethodHiddenBean.class)
                .isEmpty());

        // When: the mount is built with a sink that wants detail
        MountPublication publication =
                buildMount(vertx, TestFactories.builder(), Set.of(new MethodHiddenInputsResource()));

        // Then: every operation is published, including the one whose visible entry names no input
        assertEquals(
                Set.of(
                        "hideHeaderByMethod",
                        "hideByContainer",
                        "hideByOperation",
                        "hideCompositeField",
                        "declareVisibleUnbound"),
                operationsById(publication).keySet(),
                "operation ids");

        // Then: each input is bound where the fixture says, so each entry's location is a real match
        Map<String, Map<String, ParamLocation>> expectedLocations = new LinkedHashMap<>();
        expectedLocations.put("hideHeaderByMethod", orderedMap("X-Debug-Token", HEADER, "page", QUERY));
        expectedLocations.put("hideByContainer", orderedMap("a", QUERY, "b", QUERY));
        expectedLocations.put("hideByOperation", orderedMap("c", QUERY, "d", QUERY));
        expectedLocations.put("hideCompositeField", orderedMap("internal", QUERY, "external", QUERY));
        expectedLocations.put("declareVisibleUnbound", Map.of("w", QUERY));

        // Then: exactly the named inputs are hidden; every sibling input stays visible
        Map<String, Map<String, Boolean>> expectedHidden = new LinkedHashMap<>();
        // method-level @Parameter(name = "X-Debug-Token", in = HEADER, hidden = true)
        expectedHidden.put("hideHeaderByMethod", orderedMap("X-Debug-Token", true, "page", false));
        // @Parameters: a (QUERY) hidden = true, b (QUERY) hidden = false
        expectedHidden.put("hideByContainer", orderedMap("a", true, "b", false));
        // @Operation(parameters = @Parameter(name = "c", in = QUERY, hidden = true))
        expectedHidden.put("hideByOperation", orderedMap("c", true, "d", false));
        // method-level @Parameter(name = "internal", in = QUERY, hidden = true) on a composite field
        expectedHidden.put("hideCompositeField", orderedMap("internal", true, "external", false));
        // method-level @Parameter(name = "v", in = QUERY, hidden = false) names no input
        expectedHidden.put("declareVisibleUnbound", Map.of("w", false));

        Map<String, Origin> compositeOrigins = Map.of("internal", COMPOSITE_FIELD, "external", COMPOSITE_FIELD);
        List<Executable> checks = new ArrayList<>();
        for (String operationId : expectedHidden.keySet()) {
            List<InputBinding> inputs = inputsOf(publication, operationId, "method-level hiding");
            Map<String, ParamLocation> locations = new LinkedHashMap<>();
            Map<String, Boolean> hidden = new LinkedHashMap<>();
            Map<String, Origin> origins = new LinkedHashMap<>();
            for (InputBinding binding : inputs) {
                locations.put(binding.name(), binding.location());
                hidden.put(binding.name(), binding.hidden());
                origins.put(binding.name(), binding.origin());
            }
            Map<String, Origin> expectedOrigins = new LinkedHashMap<>();
            for (String name : expectedHidden.get(operationId).keySet()) {
                expectedOrigins.put(name, compositeOrigins.getOrDefault(name, PARAMETER));
            }
            checks.add(() -> assertEquals(expectedLocations.get(operationId), locations, operationId + ": locations"));
            checks.add(() -> assertEquals(expectedOrigins, origins, operationId + ": origins"));
            checks.add(() -> assertEquals(expectedHidden.get(operationId), hidden, operationId + ": name → hidden"));
        }
        assertAll("method-level hiding", checks);
    }

    @Test
    @DisplayName("A hidden entry whose location matches no input fails the mount build")
    void hiddenEntryWithMismatchedLocationFailsTheMountBuild(Vertx vertx) {
        // Given: @Parameter(name = "q", in = HEADER, hidden = true) while q is bound from the query
        assertTrue(GeneratedJaxRsDescriptorRegistry.shared()
                .lookup(MismatchedHiddenInputResource.class)
                .isEmpty());
        RecordingSink sink = new RecordingSink(applicationName -> true);

        // When: the mount is built with a sink that wants detail
        RestConfigurationException failure =
                assertMountBuildFails(vertx, sink, Set.of(new MismatchedHiddenInputResource()));

        // Then: the failure names the operation and the entry, and nothing is published
        String message = String.valueOf(failure.getMessage());
        assertAll(
                () -> assertTrue(message.contains("hideQueryAsHeader"), "names the operation: " + message),
                () -> assertTrue(Pattern.compile("\\bq\\b").matcher(message).find(), "names the entry q: " + message),
                () -> assertTrue(
                        Pattern.compile("\\bheader\\b", Pattern.CASE_INSENSITIVE)
                                .matcher(message)
                                .find(),
                        "names the entry's location: " + message),
                () -> assertTrue(sink.received().isEmpty(), "nothing is published"));
    }

    @Test
    @DisplayName("A hidden entry naming no input fails the mount build")
    void hiddenEntryNamingNoInputFailsTheMountBuild(Vertx vertx) {
        // Given: @Parameter(name = "ghost", hidden = true), no location, and no input named ghost
        assertTrue(GeneratedJaxRsDescriptorRegistry.shared()
                .lookup(UnmatchedHiddenInputResource.class)
                .isEmpty());
        RecordingSink sink = new RecordingSink(applicationName -> true);

        // When: the mount is built with a sink that wants detail
        RestConfigurationException failure =
                assertMountBuildFails(vertx, sink, Set.of(new UnmatchedHiddenInputResource()));

        // Then: the failure names the operation and the entry, and nothing is published
        String message = String.valueOf(failure.getMessage());
        assertAll(
                () -> assertTrue(message.contains("hideGhost"), "names the operation: " + message),
                () -> assertTrue(message.contains("ghost"), "names the entry: " + message),
                () -> assertTrue(sink.received().isEmpty(), "nothing is published"));
    }

    @Test
    @DisplayName("A hidden entry without a location hides every same-named input; a cookie or path entry only its own")
    void hiddenEntriesMatchByLocationOrEverywhereWithoutOne(Vertx vertx) throws Exception {
        // Given: a reflection-path resource whose hidden entries either omit the location or name
        // COOKIE or PATH; the inputs themselves are unmarked
        assertTrue(GeneratedJaxRsDescriptorRegistry.shared()
                .lookup(LocationHiddenInputsResource.class)
                .isEmpty());

        // When: the mount is built with a sink that wants detail
        MountPublication publication =
                buildMount(vertx, TestFactories.builder(), Set.of(new LocationHiddenInputsResource()));

        // Then: every binding, in declaration order, with its location and hidden flag
        Map<String, List<HiddenFact>> expected = new LinkedHashMap<>();
        // @Parameter(name = "dq", hidden = true): no location, hides the query input
        expected.put(
                "hideDefaultQuery", List.of(new HiddenFact(QUERY, "dq", true), new HiddenFact(QUERY, "keep", false)));
        // @Parameter(name = "dup", hidden = true): one entry hides the query and the header input
        expected.put(
                "hideSharedName",
                List.of(
                        new HiddenFact(QUERY, "dup", true),
                        new HiddenFact(HEADER, "dup", true),
                        new HiddenFact(QUERY, "other", false)));
        // @Parameter(name = "fq", hidden = true): no location, hides the form input
        expected.put("hideForm", List.of(new HiddenFact(FORM, "fq", true), new HiddenFact(FORM, "fk", false)));
        // @Parameter(name = "ck", in = COOKIE, hidden = true): the query input ck stays visible
        expected.put("hideCookie", List.of(new HiddenFact(COOKIE, "ck", true), new HiddenFact(QUERY, "ck", false)));
        // @Parameter(name = "pid", in = PATH, hidden = true): the query input pid stays visible
        expected.put("hidePath", List.of(new HiddenFact(PATH, "pid", true), new HiddenFact(QUERY, "pid", false)));

        List<Executable> checks = new ArrayList<>();
        for (Map.Entry<String, List<HiddenFact>> operation : expected.entrySet()) {
            List<InputBinding> inputs = inputsOf(publication, operation.getKey(), "location matching");
            checks.add(() -> assertEquals(operation.getValue(), hiddenFacts(inputs), operation.getKey()));
            for (InputBinding binding : inputs) {
                checks.add(() -> assertSame(PARAMETER, binding.origin(), operation.getKey() + ": origin"));
            }
        }
        assertAll("location matching", checks);
    }

    @Test
    @DisplayName("A visible method-level entry never un-hides an input its own marker hides")
    void visibleEntryKeepsAnInputHiddenByItsOwnMarker(Vertx vertx) throws Exception {
        // Given: @Parameter(name = "own", in = QUERY, hidden = false) on the method, while the query
        // input own carries @Parameter(hidden = true) itself
        assertTrue(GeneratedJaxRsDescriptorRegistry.shared()
                .lookup(LocationHiddenInputsResource.class)
                .isEmpty());

        // When: the mount is built with a sink that wants detail
        MountPublication publication =
                buildMount(vertx, TestFactories.builder(), Set.of(new LocationHiddenInputsResource()));

        // Then: own stays hidden and its sibling seen stays visible
        assertEquals(
                List.of(new HiddenFact(QUERY, "own", true), new HiddenFact(QUERY, "seen", false)),
                hiddenFacts(inputsOf(publication, "keepOwnHidden", "own marker")));
    }

    @Test
    @DisplayName("A hidden entry with a blank name fails the mount build")
    void hiddenEntryWithBlankNameFailsTheMountBuild(Vertx vertx) {
        // Given: @Parameter(name = "", hidden = true), no location, beside query input present
        assertTrue(GeneratedJaxRsDescriptorRegistry.shared()
                .lookup(BlankHiddenInputResource.class)
                .isEmpty());
        RecordingSink sink = new RecordingSink(applicationName -> true);

        // When: the mount is built with a sink that wants detail
        RestConfigurationException failure = assertMountBuildFails(vertx, sink, Set.of(new BlankHiddenInputResource()));

        // Then: the failure names the operation and the blank entry, and nothing is published
        String message = String.valueOf(failure.getMessage());
        assertAll(
                () -> assertTrue(message.contains("'hideBlank'"), "names the operation: " + message),
                () -> assertTrue(message.contains("named ''"), "names the blank entry: " + message),
                () -> assertTrue(sink.received().isEmpty(), "nothing is published"));
    }

    @Test
    @DisplayName("A failing hidden entry's name is bounded and free of control characters in the message")
    void hiddenEntryNameIsBoundedAndSanitizedInTheFailure(Vertx vertx) {
        // Given: a hidden entry, no location, named "bell", BEL (octal 007), 123 'a', "TAIL", 168 'z'
        assertTrue(GeneratedJaxRsDescriptorRegistry.shared()
                .lookup(UnsafeNameHiddenInputResource.class)
                .isEmpty());
        String name = UnsafeNameHiddenInputResource.NAME;
        assertEquals(300, name.length(), "fixture name length");
        assertEquals('\007', name.charAt(4), "fixture name carries BEL at index 4");
        assertEquals(128, name.indexOf("TAIL"), "fixture marker starts at index 128");
        RecordingSink sink = new RecordingSink(applicationName -> true);

        // When: the mount is built with a sink that wants detail
        RestConfigurationException failure =
                assertMountBuildFails(vertx, sink, Set.of(new UnsafeNameHiddenInputResource()));

        // Then: the name appears cut to its first 128 characters, BEL shown as '?', marked "..."
        String expectedName = "bell?" + "a".repeat(123) + "...";
        String message = String.valueOf(failure.getMessage());
        assertAll(
                () -> assertTrue(message.contains("'hideUnsafeName'"), "names the operation: " + message),
                () -> assertTrue(message.contains("named '" + expectedName + "'"), "bounded name: " + message),
                () -> assertFalse(message.contains("TAIL"), "text past the bound is cut: " + message),
                () -> assertFalse(message.contains("z"), "text past the bound is cut: " + message),
                // 193 characters of fixed text and operation id, plus the 131-character bounded name
                () -> assertTrue(message.length() <= 324, "message length " + message.length() + ": " + message),
                () -> assertTrue(
                        message.chars().noneMatch(Character::isISOControl), "no control characters: " + message),
                () -> assertTrue(sink.received().isEmpty(), "nothing is published"));
    }

    /** One binding's location, name, and hidden flag. */
    private record HiddenFact(
            @Nullable ParamLocation location, @Nullable String name, boolean hidden) {}

    private static List<HiddenFact> hiddenFacts(List<InputBinding> inputs) {
        List<HiddenFact> facts = new ArrayList<>(inputs.size());
        for (InputBinding binding : inputs) {
            facts.add(new HiddenFact(binding.location(), binding.name(), binding.hidden()));
        }
        return facts;
    }

    /**
     * Builds one mount at {@code /*} with the given sink and asserts that the build fails at startup
     * with a {@link RestConfigurationException}.
     */
    private static RestConfigurationException assertMountBuildFails(
            Vertx vertx, RecordingSink sink, Set<Object> resources) {
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .publicationSinks(new LinkedHashSet<>(List.of(sink)))
                .build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", resources);
        return assertThrows(RestConfigurationException.class, () -> mount.createRouter(vertx)
                .toCompletionStage()
                .toCompletableFuture()
                .get(BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private static <V> Map<String, V> orderedMap(String firstKey, V firstValue, String secondKey, V secondValue) {
        Map<String, V> map = new LinkedHashMap<>();
        map.put(firstKey, firstValue);
        map.put(secondKey, secondValue);
        return map;
    }

    private static Map<String, Requiredness> requirednessByName(List<InputBinding> inputs) {
        Map<String, Requiredness> byName = new LinkedHashMap<>();
        for (InputBinding binding : inputs) {
            byName.put(binding.name(), binding.requiredness());
        }
        return byName;
    }

    // ---------------------------------------------------------------------------------------
    // Mount building and reading
    // ---------------------------------------------------------------------------------------

    /**
     * Builds one mount at {@code /*} with a sink wanting detail for every mount, and returns the
     * single publication it recorded.
     */
    private static MountPublication buildMount(Vertx vertx, TestFactories.Builder builder, Set<Object> resources)
            throws Exception {
        RecordingSink sink = new RecordingSink(applicationName -> true);
        JaxRsRouterMount.Factory factory =
                builder.publicationSinks(new LinkedHashSet<>(List.of(sink))).build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", resources);
        mount.createRouter(vertx)
                .toCompletionStage()
                .toCompletableFuture()
                .get(BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return sink.onlyReceived();
    }

    private static Map<String, OperationPublication> operationsById(MountPublication publication) {
        Map<String, OperationPublication> byId = new LinkedHashMap<>();
        for (OperationPublication operation : publication.operations()) {
            byId.put(operation.operationId(), operation);
        }
        return byId;
    }

    /** Returns the non-empty inventory of one operation, failing by assertion when it is missing. */
    private static List<InputBinding> inputsOf(MountPublication publication, String operationId, String context) {
        OperationPublication operation = operationsById(publication).get(operationId);
        assertNotNull(operation, context + ": operation " + operationId);
        assertNotNull(operation.detail(), context + ": detail of " + operationId);
        List<InputBinding> inputs = operation.detail().inputs();
        assertNotNull(inputs, context + ": inputs of " + operationId);
        assertFalse(inputs.isEmpty(), context + ": inputs of " + operationId + " must not be empty");
        return inputs;
    }

    private static void assertInventories(
            Map<String, List<Row>> expected, MountPublication publication, String variant) {
        assertEquals(expected.keySet(), operationsById(publication).keySet(), variant + ": operation ids");
        for (Map.Entry<String, List<Row>> operation : expected.entrySet()) {
            List<InputBinding> inputs = inputsOf(publication, operation.getKey(), variant);
            assertEquals(operation.getValue(), rows(inputs), variant + ": inputs of " + operation.getKey());
        }
    }

    private static void assertResourceClasses(Map<String, Class<?>> expected, MountPublication publication) {
        Map<String, OperationPublication> byId = operationsById(publication);
        for (Map.Entry<String, Class<?>> operation : expected.entrySet()) {
            OperationDetail detail = byId.get(operation.getKey()).detail();
            assertNotNull(detail, "detail of " + operation.getKey());
            assertNotNull(detail.response(), "response of " + operation.getKey());
            assertSame(
                    operation.getValue(), detail.response().resourceClass(), "resource class of " + operation.getKey());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Expected rows
    // ---------------------------------------------------------------------------------------

    /**
     * Comparable view of one {@link InputBinding}: every component, with the annotations as a list
     * compared by {@code Annotation.equals}. {@code requiredness} {@code null} marks a row whose
     * expectation was never stated, so it can never match.
     */
    private record Row(
            Origin origin,
            @Nullable ParamLocation location,
            @Nullable String name,
            Type type,
            @Nullable String defaultValue,
            @Nullable Requiredness requiredness,
            boolean hidden,
            boolean schemaEnforced,
            List<Annotation> annotations,
            @Nullable Integer methodParameterIndex,
            @Nullable Class<?> compositeType) {

        Row defaults(String value) {
            return new Row(
                    origin,
                    location,
                    name,
                    type,
                    value,
                    requiredness,
                    hidden,
                    schemaEnforced,
                    annotations,
                    methodParameterIndex,
                    compositeType);
        }

        Row is(Requiredness value) {
            return new Row(
                    origin,
                    location,
                    name,
                    type,
                    defaultValue,
                    value,
                    hidden,
                    schemaEnforced,
                    annotations,
                    methodParameterIndex,
                    compositeType);
        }

        Row enforced() {
            return withEnforced(true);
        }

        Row withAnnotations(List<Annotation> value) {
            return new Row(
                    origin,
                    location,
                    name,
                    type,
                    defaultValue,
                    requiredness,
                    hidden,
                    schemaEnforced,
                    value,
                    methodParameterIndex,
                    compositeType);
        }

        Row withEnforced(boolean value) {
            return new Row(
                    origin,
                    location,
                    name,
                    type,
                    defaultValue,
                    requiredness,
                    hidden,
                    value,
                    annotations,
                    methodParameterIndex,
                    compositeType);
        }

        static Row of(InputBinding binding) {
            return new Row(
                    binding.origin(),
                    binding.location(),
                    binding.name(),
                    binding.type(),
                    binding.defaultValue(),
                    binding.requiredness(),
                    binding.hidden(),
                    binding.schemaEnforced(),
                    List.copyOf(binding.annotations()),
                    binding.methodParameterIndex(),
                    binding.compositeType());
        }
    }

    private static List<Row> rows(List<InputBinding> inputs) {
        List<Row> rows = new ArrayList<>(inputs.size());
        for (InputBinding binding : inputs) {
            rows.add(Row.of(binding));
        }
        return rows;
    }

    /**
     * Without a bound validator nothing can reject a missing value, so every {@code PARAMETER} and
     * {@code COMPOSITE_FIELD} binding other than a {@code PATH} binding is {@code NOT_REQUIRED}; a
     * path binding stays {@code REQUIRED} and the body stays {@code UNKNOWN}.
     */
    private static Row withoutValidator(Row row) {
        return row.origin() == BODY || row.location() == PATH ? row : row.is(NOT_REQUIRED);
    }

    private static Map<String, List<Row>> transform(Map<String, List<Row>> expected, Function<Row, Row> change) {
        Map<String, List<Row>> transformed = new LinkedHashMap<>();
        for (Map.Entry<String, List<Row>> operation : expected.entrySet()) {
            transformed.put(
                    operation.getKey(),
                    operation.getValue().stream().map(change).toList());
        }
        return transformed;
    }

    private static Expect expect(Class<?> resourceClass, String methodName) {
        return new Expect(declaredMethod(resourceClass, methodName));
    }

    /**
     * Named builder for the expected rows of one resource method. Annotations are read by
     * reflection from the fixture declaration itself: the method parameter, the POJO field, or the
     * record component's accessor.
     */
    private record Expect(Method method) {

        Row param(int index, ParamLocation location, String name, Type type) {
            return new Row(
                    PARAMETER,
                    location,
                    name,
                    type,
                    null,
                    null,
                    false,
                    false,
                    List.of(method.getParameterAnnotations()[index]),
                    index,
                    null);
        }

        Row body(int index, Type type) {
            return new Row(
                    BODY,
                    null,
                    null,
                    type,
                    null,
                    null,
                    false,
                    false,
                    List.of(method.getParameterAnnotations()[index]),
                    index,
                    null);
        }

        Row field(int index, ParamLocation location, String name, Type type, Class<?> compositeType, String member) {
            return new Row(
                    COMPOSITE_FIELD,
                    location,
                    name,
                    type,
                    null,
                    null,
                    false,
                    false,
                    memberAnnotations(compositeType, member),
                    index,
                    compositeType);
        }
    }

    /**
     * The declared annotations of {@code PrimitiveLimits.region}: the explicit accessor's
     * {@code @QueryParam("region")} first, then the component's {@code @NotNull} carried by the
     * backing field.
     */
    private static List<Annotation> regionAnnotations() {
        try {
            return List.of(
                    PrimitiveLimits.class.getMethod("region").getAnnotation(jakarta.ws.rs.QueryParam.class),
                    PrimitiveLimits.class.getDeclaredField("region").getAnnotation(NotNull.class));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("fixture member PrimitiveLimits.region", e);
        }
    }

    private static List<Annotation> memberAnnotations(Class<?> compositeType, String member) {
        try {
            if (compositeType.isRecord()) {
                return List.of(compositeType.getMethod(member).getAnnotations());
            }
            return List.of(compositeType.getDeclaredField(member).getAnnotations());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("fixture member " + compositeType.getSimpleName() + "." + member, e);
        }
    }

    private static Method declaredMethod(Class<?> type, String methodName) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && !method.isSynthetic()) {
                return method;
            }
        }
        throw new AssertionError("fixture method " + type.getSimpleName() + "." + methodName);
    }

    private static Type genericParameterType(Class<?> type, String methodName, int index) {
        return declaredMethod(type, methodName).getGenericParameterTypes()[index];
    }

    private static Type returnType(Class<?> type, String methodName) {
        return declaredMethod(type, methodName).getGenericReturnType();
    }

    // ---------------------------------------------------------------------------------------
    // Response and body cases
    // ---------------------------------------------------------------------------------------

    /** Comparable view of one {@link ResponseShape}. */
    private record ExpectedResponse(
            Type genericReturnType,
            Class<?> resourceClass,
            boolean returnsFuture,
            boolean returnsVoid,
            List<String> produces,
            String outputProfileId) {

        static ExpectedResponse of(ResponseShape shape) {
            return new ExpectedResponse(
                    shape.genericReturnType(),
                    shape.resourceClass(),
                    shape.returnsFuture(),
                    shape.returnsVoid(),
                    List.copyOf(shape.produces()),
                    shape.outputProfileId());
        }
    }

    /** One body-schema configuration, named after the matching absent-body gate case. */
    private record BodyCase(String name, OperationSchemaSource source, boolean gated, boolean expectEnforced) {}

    /** A source returning the given body schema (none when {@code null}) and no parameter schema. */
    private static OperationSchemaSource bodySchemaSource(@Nullable JsonObject bodySchema) {
        return new CountingSchemaSource((op, call) -> bodySchema == null
                ? OperationSchemas.builder().build()
                : OperationSchemas.builder().bodySchema(bodySchema.copy()).build());
    }

    /** A source returning schemas for path {@code id}, query {@code q}, and the body, only. */
    private static OperationSchemaSource idQueryAndBodySchemas() {
        return new CountingSchemaSource((op, call) -> OperationSchemas.builder()
                .bodySchema(new JsonObject().put("type", "object"))
                .parameterSchema(ParamLocation.PATH, "id", new JsonObject().put("type", "string"))
                .parameterSchema(ParamLocation.QUERY, "q", new JsonObject().put("type", "integer"))
                .build());
    }

    private static JsonMapperProfile appProfile(String id) {
        return JsonMapperProfiles.of(
                JsonProfileId.of(id), new ObjectMapper().registerModule(VertxJsonSupport.module()));
    }
}
