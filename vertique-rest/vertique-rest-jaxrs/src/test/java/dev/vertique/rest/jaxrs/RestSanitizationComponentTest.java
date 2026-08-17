// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Component;
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
}
