// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Component;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.json.JacksonFieldNameResolver;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.convert.ConversionContexts;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.sanitization.SanitizationModule;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Singleton;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger wiring tests proving that {@code RestModule}'s {@code @BindsOptionalOf} declaration for
 * the neutral {@link InputObjectProcessor} is satisfied by {@code SanitizationModule} (AC-INP-010,
 * REST-only): with the module installed the optional resolves present; without it, empty.
 *
 * <p>The wired engine is then driven through the REST body path with a {@code @JsonProperty}-renamed
 * DTO, proving the composed stack — REST's Jackson projection plus the engine's per-field metadata
 * lookup — applies a policy declared on a field whose wire name differs from its Java name.
 */
class RestSanitizationComponentTest {

    /** Component with {@code SanitizationModule} installed — the optional binding is satisfied. */
    @Singleton
    @Component(modules = {RestModule.class, SanitizationModule.class})
    interface WithSanitizationComponent {

        Optional<InputObjectProcessor> inputObjectProcessor();
    }

    /** Component without {@code SanitizationModule} — the optional binding stays empty. */
    @Singleton
    @Component(modules = RestModule.class)
    interface WithoutSanitizationComponent {

        Optional<InputObjectProcessor> inputObjectProcessor();
    }

    @Test
    @DisplayName("SanitizationModule satisfies RestModule's optional InputObjectProcessor binding")
    void sanitizationModulePresentResolvesProcessor() {
        WithSanitizationComponent component = DaggerRestSanitizationComponentTest_WithSanitizationComponent.create();

        assertTrue(component.inputObjectProcessor().isPresent());
    }

    @Test
    @DisplayName("Without SanitizationModule the optional InputObjectProcessor binding is empty")
    void withoutSanitizationModuleOptionalIsEmpty() {
        WithoutSanitizationComponent component =
                DaggerRestSanitizationComponentTest_WithoutSanitizationComponent.create();

        assertFalse(component.inputObjectProcessor().isPresent());
    }

    // --- Renamed-field body sanitization ---

    /** Sanitizer whose effect is unmistakable in an assertion. */
    public static final class UppercasingSanitizer implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext context) {
            return value == null ? null : value.toUpperCase(java.util.Locale.ROOT);
        }
    }

    /** Body DTO whose governed field is published under a different wire name. */
    public record RenamedBody(
            @JsonProperty("user_name") @Sanitize(UppercasingSanitizer.class)
            String userName,

            String city) {}

    /** Resource fixture whose method takes the renamed body. */
    static final class BodyResource {
        @SuppressWarnings("unused")
        public String create(RenamedBody body) {
            return body.userName();
        }
    }

    @Test
    @DisplayName("a @JsonProperty-renamed body field is sanitized through the wired REST body path")
    void shouldSanitizeJsonPropertyRenamedBodyFields() throws Exception {
        WithSanitizationComponent component = DaggerRestSanitizationComponentTest_WithSanitizationComponent.create();
        InputObjectProcessor processor =
                component.inputObjectProcessor().orElseThrow(() -> new AssertionError("engine must be bound"));

        Method create = BodyResource.class.getDeclaredMethod("create", RenamedBody.class);
        ResourceMethodMeta meta = new ResourceMethodMeta(
                new BodyResource(),
                create,
                "create",
                "POST",
                "/renamed",
                List.of(new ResourceMethodMeta.ParamMeta(
                        "body", ResourceMethodMeta.ParamSource.BODY, RenamedBody.class)),
                String.class,
                false,
                false,
                new dev.vertique.rest.core.security.SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        List<RequestBodyDecoder> decoders = List.of(new JsonRequestBodyDecoder());
        ParameterExtractor extractor = new ParameterExtractor(
                meta,
                decoders,
                new RestContextResolution(Set.of()),
                processor,
                ConversionContexts.defaultResolver(),
                JacksonFieldNameResolver.forRoute(null));

        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.getHeader("Content-Type")).thenReturn("application/json");
        when(ctx.<ObjectMapper>get(BoundRequest.KEY_RESOLVED_BODY_MAPPER)).thenReturn(null);

        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("user_name", "ada");
        bodyMap.put("city", "paris");

        Object result = extractor.deserializeBody(
                RequestValue.of(new JsonObject(bodyMap)), RenamedBody.class, null, ctx, EffectiveInputPolicies.NONE);

        RenamedBody bound = assertInstanceOf(RenamedBody.class, result, "the body must materialize");
        assertEquals(
                "ADA",
                bound.userName(),
                "the @Sanitize declared on the Java property must run on its renamed wire key");
        assertEquals("paris", bound.city(), "an ungoverned field is left untouched");
    }

    // --- Promoted keys: the real projection through the real engine, bound by the real mapper ---

    /** Inner type in the shape Lombok emits: a private governed field behind accessors. */
    public static class Location {
        @Sanitize(UppercasingSanitizer.class)
        private String street;

        public String getStreet() {
            return street;
        }

        public void setStreet(String street) {
            this.street = street;
        }
    }

    /** Body whose member is unwrapped, so {@code street} arrives as a key of the body itself. */
    public static class UnwrappedBody {
        private String name;

        @JsonUnwrapped
        private Location location;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Location getLocation() {
            return location;
        }

        public void setLocation(Location location) {
            this.location = location;
        }
    }

    /** Resource fixture whose method takes the unwrapped body. */
    static final class UnwrappedBodyResource {
        @SuppressWarnings("unused")
        public String create(UnwrappedBody body) {
            return body.getLocation().getStreet();
        }
    }

    @Test
    @DisplayName("a policy on an @JsonUnwrapped member's field runs on the flat wire key and Jackson binds the result")
    void shouldSanitizePromotedBodyKeysThroughTheWiredRestBodyPath() throws Exception {
        WithSanitizationComponent component = DaggerRestSanitizationComponentTest_WithSanitizationComponent.create();
        InputObjectProcessor processor =
                component.inputObjectProcessor().orElseThrow(() -> new AssertionError("engine must be bound"));
        JacksonFieldNameResolver bodyNameResolver = JacksonFieldNameResolver.forRoute(null);

        // What JaxRsRouteRegistrar does at registration: the engine walks the body type and hands
        // each owner to the projection, which is where an unroutable promoted key would fail.
        processor.precomputeFieldNameResolution(UnwrappedBody.class, bodyNameResolver);

        Method create = UnwrappedBodyResource.class.getDeclaredMethod("create", UnwrappedBody.class);
        ResourceMethodMeta meta = new ResourceMethodMeta(
                new UnwrappedBodyResource(),
                create,
                "create",
                "POST",
                "/unwrapped",
                List.of(new ResourceMethodMeta.ParamMeta(
                        "body", ResourceMethodMeta.ParamSource.BODY, UnwrappedBody.class)),
                String.class,
                false,
                false,
                new dev.vertique.rest.core.security.SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        ParameterExtractor extractor = new ParameterExtractor(
                meta,
                List.of(new JsonRequestBodyDecoder()),
                new RestContextResolution(Set.of()),
                processor,
                ConversionContexts.defaultResolver(),
                bodyNameResolver);

        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.getHeader("Content-Type")).thenReturn("application/json");
        when(ctx.<ObjectMapper>get(BoundRequest.KEY_RESOLVED_BODY_MAPPER)).thenReturn(null);

        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("name", "ada");
        bodyMap.put("street", "main");

        Object result = extractor.deserializeBody(
                RequestValue.of(new JsonObject(bodyMap)), UnwrappedBody.class, null, ctx, EffectiveInputPolicies.NONE);

        UnwrappedBody bound = assertInstanceOf(UnwrappedBody.class, result, "the body must materialize");
        assertEquals(
                "MAIN",
                bound.getLocation().getStreet(),
                "the @Sanitize declared on the unwrapped member's field must run on its flat wire key");
        assertEquals("ada", bound.getName(), "the body's own field is untouched");
    }

    /** A governed field the mapper reaches only through accessors of a different name. */
    public static class MismatchedBody {
        @Sanitize(UppercasingSanitizer.class)
        private String streetName;

        public String getStreet() {
            return streetName;
        }

        public void setStreet(String street) {
            this.streetName = street;
        }
    }

    @Test
    @DisplayName("a governed field the mapper binds under another property name fails registration, not silently")
    void shouldRefuseAGovernedFieldTheMapperBindsUnderAnotherName() {
        WithSanitizationComponent component = DaggerRestSanitizationComponentTest_WithSanitizationComponent.create();
        InputObjectProcessor processor =
                component.inputObjectProcessor().orElseThrow(() -> new AssertionError("engine must be bound"));

        // Jackson derives the property 'street' from the accessors and never learns that setStreet
        // writes 'streetName', so the field's policy could never be reached from the wire.
        ConfigurationException ex = assertThrows(
                ConfigurationException.class,
                () -> processor.precomputeFieldNameResolution(
                        MismatchedBody.class, JacksonFieldNameResolver.forRoute(null)));
        assertTrue(ex.getMessage().contains("'streetName'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("street"), ex.getMessage());
    }

    /** Drives {@code body} through registration and the wired REST body path for {@code type}. */
    private static <T> T bindThroughBodyPath(InputObjectProcessor processor, Class<T> type, Map<String, Object> body)
            throws Exception {
        JacksonFieldNameResolver bodyNameResolver = JacksonFieldNameResolver.forRoute(null);
        processor.precomputeFieldNameResolution(type, bodyNameResolver);

        Method create = UnwrappedBodyResource.class.getDeclaredMethod("create", UnwrappedBody.class);
        ResourceMethodMeta meta = new ResourceMethodMeta(
                new UnwrappedBodyResource(),
                create,
                "create",
                "POST",
                "/shape",
                List.of(new ResourceMethodMeta.ParamMeta("body", ResourceMethodMeta.ParamSource.BODY, type)),
                String.class,
                false,
                false,
                new dev.vertique.rest.core.security.SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        ParameterExtractor extractor = new ParameterExtractor(
                meta,
                List.of(new JsonRequestBodyDecoder()),
                new RestContextResolution(Set.of()),
                processor,
                ConversionContexts.defaultResolver(),
                bodyNameResolver);

        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.getHeader("Content-Type")).thenReturn("application/json");
        when(ctx.<ObjectMapper>get(BoundRequest.KEY_RESOLVED_BODY_MAPPER)).thenReturn(null);

        Object result = extractor.deserializeBody(
                RequestValue.of(new JsonObject(new LinkedHashMap<>(body))),
                type,
                null,
                ctx,
                EffectiveInputPolicies.NONE);
        return assertInstanceOf(type, result, "the body must materialize");
    }

    /** A record renamed on the wire: the creator parameter is linked to the component by name. */
    public record RecordBody(
            @JsonProperty("street_name") @Sanitize(UppercasingSanitizer.class)
            String streetName) {}

    /** A governed private field Jackson binds by inferring the field as the mutator of its getter. */
    public static class GetterOnlyBody {
        @Sanitize(UppercasingSanitizer.class)
        private String street;

        public String getStreet() {
            return street;
        }
    }

    /** A governed field written by a creator parameter Jackson cannot tie to it. */
    public static class CreatorBody {
        @Sanitize(UppercasingSanitizer.class)
        private final String streetName;

        @JsonCreator
        public CreatorBody(@JsonProperty("street_name") String s) {
            this.streetName = s;
        }

        public String getStreetName() {
            return streetName;
        }
    }

    /** The remedy: the field carries the parameter's wire name, so Jackson links the two. */
    public static class LinkedCreatorBody {
        @JsonProperty("street_name")
        @Sanitize(UppercasingSanitizer.class)
        private final String streetName;

        @JsonCreator
        public LinkedCreatorBody(@JsonProperty("street_name") String s) {
            this.streetName = s;
        }

        public String getStreetName() {
            return streetName;
        }
    }

    /** A creator parameter named after the governed field it writes, with no bean accessor at all. */
    public static class NamedCreatorBody {
        @Sanitize(UppercasingSanitizer.class)
        private final String streetName;

        @JsonCreator
        public NamedCreatorBody(@JsonProperty("streetName") String streetName) {
            this.streetName = streetName;
        }

        public String streetName() {
            return streetName;
        }
    }

    @Test
    @DisplayName(
            "the shapes Jackson links by name pass registration and are sanitized: record, getter-only, linked creator")
    void shouldAcceptAndSanitizeTheShapesJacksonLinksByName() throws Exception {
        InputObjectProcessor processor = DaggerRestSanitizationComponentTest_WithSanitizationComponent.create()
                .inputObjectProcessor()
                .orElseThrow(() -> new AssertionError("engine must be bound"));

        assertEquals(
                "MAIN",
                bindThroughBodyPath(processor, RecordBody.class, Map.of("street_name", "main"))
                        .streetName(),
                "a renamed record component is bound under its own name");
        assertEquals(
                "MAIN",
                bindThroughBodyPath(processor, GetterOnlyBody.class, Map.of("street", "main"))
                        .getStreet(),
                "a getter-only private field is inferred as the mutator and bound under its own name");
        assertEquals(
                "MAIN",
                bindThroughBodyPath(processor, LinkedCreatorBody.class, Map.of("street_name", "main"))
                        .getStreetName(),
                "a creator parameter linked to its field by wire name routes to the field's policy");
        assertEquals(
                "MAIN",
                bindThroughBodyPath(processor, NamedCreatorBody.class, Map.of("streetName", "main"))
                        .streetName(),
                "a creator parameter named after the field routes to the field's policy with no accessor to link");
    }

    @Test
    @DisplayName("a governed field written by a creator parameter Jackson cannot tie to it fails registration")
    void shouldRefuseAGovernedFieldWrittenByAnUnlinkedCreatorParameter() {
        InputObjectProcessor processor = DaggerRestSanitizationComponentTest_WithSanitizationComponent.create()
                .inputObjectProcessor()
                .orElseThrow(() -> new AssertionError("engine must be bound"));

        ConfigurationException ex = assertThrows(
                ConfigurationException.class,
                () -> processor.precomputeFieldNameResolution(
                        CreatorBody.class, JacksonFieldNameResolver.forRoute(null)));
        assertTrue(ex.getMessage().contains("street_name"), ex.getMessage());
    }

    // --- Schema-free structured bodies ---

    /** Resource fixture whose method takes a schema-free {@link JsonObject} body. */
    static final class JsonObjectBodyResource {
        @SuppressWarnings("unused")
        public String accept(JsonObject body) {
            return body.encode();
        }
    }

    @Test
    @DisplayName("a JsonObject body declaring a chain is processed to every string leaf")
    void shouldSanitizeStructuredJsonObjectBodies() throws Exception {
        WithSanitizationComponent component = DaggerRestSanitizationComponentTest_WithSanitizationComponent.create();
        InputObjectProcessor processor =
                component.inputObjectProcessor().orElseThrow(() -> new AssertionError("engine must be bound"));

        Method accept = JsonObjectBodyResource.class.getDeclaredMethod("accept", JsonObject.class);
        ResourceMethodMeta meta = new ResourceMethodMeta(
                new JsonObjectBodyResource(),
                accept,
                "accept",
                "POST",
                "/raw-json",
                List.of(new ResourceMethodMeta.ParamMeta(
                        "body", ResourceMethodMeta.ParamSource.BODY, JsonObject.class)),
                String.class,
                false,
                false,
                new dev.vertique.rest.core.security.SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        ParameterExtractor extractor = new ParameterExtractor(
                meta,
                List.of(new JsonRequestBodyDecoder()),
                new RestContextResolution(Set.of()),
                processor,
                ConversionContexts.defaultResolver(),
                JacksonFieldNameResolver.forRoute(null));

        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.getHeader("Content-Type")).thenReturn("application/json");
        when(ctx.<ObjectMapper>get(BoundRequest.KEY_RESOLVED_BODY_MAPPER)).thenReturn(null);

        // Decoded from text, exactly as a request body arrives: the backing map's nested values are
        // plain Maps and Lists, which is what the engine walks.
        JsonObject body = new JsonObject("{\"greeting\":\"ada\",\"nested\":{\"deep\":\"bob\"},\"list\":[\"carol\"]}");

        Object result = extractor.deserializeBody(
                RequestValue.of(body),
                JsonObject.class,
                null,
                ctx,
                new EffectiveInputPolicies(List.of(), List.of(UppercasingSanitizer.class)));

        JsonObject processed = assertInstanceOf(JsonObject.class, result, "a JsonObject body stays a JsonObject");
        assertEquals("ADA", processed.getString("greeting"), "the declared chain must reach a top-level string");
        assertEquals(
                "BOB",
                processed.getJsonObject("nested").getString("deep"),
                "the declared chain must reach a nested string");
        assertEquals(
                "CAROL",
                processed.getJsonArray("list").getString(0),
                "the declared chain must reach an array element string");
    }

    /** Resource fixture whose method takes a schema-free {@link JsonArray} body. */
    static final class JsonArrayBodyResource {
        @SuppressWarnings("unused")
        public String accept(JsonArray body) {
            return body.encode();
        }
    }

    @Test
    @DisplayName("a declared JsonArray body carrying an object payload falls through to the decoder chain")
    void shouldFallThroughWhenAJsonArrayBodyReceivesAnObjectPayload() throws Exception {
        WithSanitizationComponent component = DaggerRestSanitizationComponentTest_WithSanitizationComponent.create();
        InputObjectProcessor processor =
                component.inputObjectProcessor().orElseThrow(() -> new AssertionError("engine must be bound"));

        Method accept = JsonArrayBodyResource.class.getDeclaredMethod("accept", JsonArray.class);
        ResourceMethodMeta meta = new ResourceMethodMeta(
                new JsonArrayBodyResource(),
                accept,
                "accept",
                "POST",
                "/raw-array",
                List.of(new ResourceMethodMeta.ParamMeta("body", ResourceMethodMeta.ParamSource.BODY, JsonArray.class)),
                String.class,
                false,
                false,
                new dev.vertique.rest.core.security.SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        ParameterExtractor extractor = new ParameterExtractor(
                meta,
                List.of(new JsonRequestBodyDecoder()),
                new RestContextResolution(Set.of()),
                processor,
                ConversionContexts.defaultResolver(),
                JacksonFieldNameResolver.forRoute(null));

        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.getHeader("Content-Type")).thenReturn("application/json");
        when(ctx.<ObjectMapper>get(BoundRequest.KEY_RESOLVED_BODY_MAPPER)).thenReturn(null);

        // The payload is an object where the declared body type is an array: a shape mismatch the
        // decoder chain owns. Routing it through the engine instead would target the Vert.x wrapper
        // class itself and then materialize the processed map as a JsonArray.
        Object result = extractor.deserializeBody(
                RequestValue.of(new JsonObject("{\"greeting\":\"ada\"}")),
                JsonArray.class,
                null,
                ctx,
                new EffectiveInputPolicies(List.of(), List.of(UppercasingSanitizer.class)));

        assertNull(
                result,
                "a declared JsonArray body with an object payload must take the documented fall-through to "
                        + "JsonRequestBodyDecoder, whose mismatch answer is null");
    }
}
