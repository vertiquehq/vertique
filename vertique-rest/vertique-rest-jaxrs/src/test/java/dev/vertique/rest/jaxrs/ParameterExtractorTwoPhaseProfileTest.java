// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputFieldNameResolver;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the TWO-PHASE materialization site in {@link ParameterExtractor#deserializeBody}
 * (slice 2.3, FR-JSON-024B). When an {@link InputObjectProcessor} is active, a structured JSON body
 * is intercepted, processed into an intermediate map, and then materialized to the DTO. This test
 * proves the final materialization uses the RESOLVED PROFILE MAPPER stashed on the
 * {@link RoutingContext} under {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER} — not the vertx global
 * mapper — and that a strict-profile materialization failure surfaces as a {@link ValidationException}
 * (HTTP 400 via {@code DefaultExceptionMapper}).
 *
 * <p>A full IT for the two-phase path is disproportionate (it requires a sanitization module on the
 * route), so the materialization site is covered here at the unit level as the plan permits: the
 * {@link InputObjectProcessor} is a pass-through stub, and the strict mapper DISABLES
 * {@code ALLOW_COERCION_OF_SCALARS} (which the vertx global mapper leaves enabled, verified) so a
 * string for the primitive {@code count} is rejected only when bound by the profile mapper.
 */
class ParameterExtractorTwoPhaseProfileTest {

    /** Simple POJO the BODY param materializes into; the primitive {@code count} makes coercion observable. */
    public static class Payload {
        public String name;
        public int count;
    }

    /** Resource fixture whose method has a single body parameter. */
    static final class BodyResource {
        @SuppressWarnings("unused")
        public String create(Payload dto) {
            return dto.name;
        }
    }

    /** Pass-through {@link InputObjectProcessor} that returns the intermediate body unchanged. */
    private static final class PassThroughProcessor implements InputObjectProcessor {
        @Override
        public Object processInput(
                Object intermediateBody,
                Type targetType,
                EffectiveInputPolicies policies,
                InputLocation location,
                InputFieldNameResolver nameResolver) {
            return intermediateBody;
        }
    }

    /**
     * Builds a mapper with Vert.x JSON support and {@code ALLOW_COERCION_OF_SCALARS} disabled, mirroring
     * the {@code no-coercion} IT profile so the two-phase materialization rejects a string-for-int.
     *
     * @return the no-coercion materialization mapper
     */
    private static ObjectMapper noCoercionMapper() {
        return JsonMapper.builder()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .addModule(VertxJsonSupport.module())
                .build();
    }

    /**
     * Builds a {@link ParameterExtractor} with a pass-through processor (so the two-phase path runs)
     * for the {@code create(Payload)} resource method.
     *
     * @return a two-phase-enabled extractor
     * @throws NoSuchMethodException never (the fixture method exists)
     */
    private static ParameterExtractor twoPhaseExtractor() throws NoSuchMethodException {
        Method create = BodyResource.class.getMethod("create", Payload.class);
        ResourceMethodMeta meta = new ResourceMethodMeta(
                new BodyResource(),
                create,
                "create",
                "POST",
                "/dto",
                List.of(new ResourceMethodMeta.ParamMeta("dto", ResourceMethodMeta.ParamSource.BODY, Payload.class)),
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
        return new ParameterExtractor(meta, decoders, new RestContextResolution(Set.of()), new PassThroughProcessor());
    }

    /**
     * Builds a mocked {@link RoutingContext} for a JSON POST, stashing {@code profileMapper} under
     * {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER} (or none when {@code profileMapper} is {@code null}).
     *
     * @param profileMapper the resolved profile mapper to stash, or {@code null} for the vertx path
     * @return the mocked routing context
     */
    private static RoutingContext jsonCtx(ObjectMapper profileMapper) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.getHeader("Content-Type")).thenReturn("application/json");
        when(ctx.<ObjectMapper>get(BoundRequest.KEY_RESOLVED_BODY_MAPPER)).thenReturn(profileMapper);
        return ctx;
    }

    /**
     * Builds a body whose {@code count} is the JSON string {@code "5"} — coerced by the vertx mapper,
     * rejected by the no-coercion profile mapper.
     *
     * @return the body request value
     */
    private static RequestValue stringCountBody() {
        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("name", "a");
        bodyMap.put("count", "5");
        return RequestValue.of(new JsonObject(bodyMap));
    }

    @Test
    @DisplayName("Two-phase: a no-coercion profile mapper rejects a coerced scalar as a ValidationException (400)")
    void twoPhaseProcessedBody_usesProfileMapper() throws Exception {
        ParameterExtractor extractor = twoPhaseExtractor();
        RoutingContext ctx = jsonCtx(noCoercionMapper());

        ValidationException ex = assertThrows(
                ValidationException.class,
                () -> extractor.deserializeBody(
                        stringCountBody(), Payload.class, null, ctx, EffectiveInputPolicies.NONE),
                "a string-for-int on the processed body must be rejected by the no-coercion profile mapper");
        assertTrue(ex.getMessage().contains("JSON profile"), "the 400 message must name the JSON profile");
    }

    @Test
    @DisplayName("Two-phase: the vertx path (no profile mapper) coerces the scalar and binds the DTO")
    void twoPhaseProcessedBody_vertxPathUnchanged() throws Exception {
        ParameterExtractor extractor = twoPhaseExtractor();
        RoutingContext ctx = jsonCtx(null);

        Object result =
                extractor.deserializeBody(stringCountBody(), Payload.class, null, ctx, EffectiveInputPolicies.NONE);
        assertInstanceOf(Payload.class, result);
        assertEquals("a", ((Payload) result).name, "the vertx path must bind the DTO");
        assertEquals(5, ((Payload) result).count, "the vertx path must coerce the string scalar to the int");
    }
}
