// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.validation.ParameterViolation;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.security.SecurityPolicy;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.QueryParam;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConstraintViolationMapper}, verifying that
 * {@link ParameterViolation} records are correctly translated to
 * {@link RestValidationException} with per-field HTTP location context.
 *
 * <p>Tests cover all {@link ResourceMethodMeta.ParamSource} variants and the
 * {@code @BeanParam} POJO / record reflection path.
 */
class ConstraintViolationMapperTest {

    // --- Test helper to build ResourceMethodMeta ---

    /**
     * Builds a minimal {@link ResourceMethodMeta} with the given parameter descriptors,
     * using {@code Object.toString()} as a stand-in method.
     *
     * @param params zero or more parameter descriptors
     * @return a fully constructed {@link ResourceMethodMeta}
     */
    private static ResourceMethodMeta metaWithParams(ResourceMethodMeta.ParamMeta... params) {
        try {
            return new ResourceMethodMeta(
                    new Object(),
                    Object.class.getMethod("toString"),
                    "testOp",
                    "POST",
                    "/test",
                    List.of(params),
                    Void.class,
                    false,
                    true,
                    new SecurityPolicy.None(),
                    ResourceMethodMeta.MediaTypes.EMPTY,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("Should map BODY violation with correct location and path")
    void shouldMapBodyViolation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, Object.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, new ViolationDetail("name", "must not be blank", "required", null)));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        assertEquals(1, ex.errors().size());
        ValidationErrorDetail err = ex.errors().get(0);
        assertEquals("name", err.path());
        assertEquals("must not be blank", err.detail());
        assertEquals("body", err.location());
        assertEquals("required", err.type());
    }

    @Test
    @DisplayName("Should map nested BODY violation path")
    void shouldMapNestedBodyViolation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, Object.class));
        List<ParameterViolation> violations = List.of(
                new ParameterViolation(0, new ViolationDetail("address.city", "must not be blank", "required", null)));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        assertEquals("address.city", ex.errors().get(0).path());
        assertEquals("body", ex.errors().get(0).location());
    }

    @Test
    @DisplayName("Should map QUERY violation with param name")
    void shouldMapQueryViolation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta("page", ResourceMethodMeta.ParamSource.QUERY, int.class));
        List<ParameterViolation> violations = List.of(
                new ParameterViolation(0, new ViolationDetail("", "must be positive", "min", Map.of("value", 1))));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        ValidationErrorDetail err = ex.errors().get(0);
        assertEquals("page", err.path());
        assertEquals("query", err.location());
        assertEquals("min", err.type());
        assertEquals(Map.of("value", 1), err.args());
    }

    @Test
    @DisplayName("Should map PATH violation with param name")
    void shouldMapPathViolation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta("id", ResourceMethodMeta.ParamSource.PATH, String.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, ViolationDetail.of("", "must not be blank")));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        assertEquals("id", ex.errors().get(0).path());
        assertEquals("path", ex.errors().get(0).location());
    }

    @Test
    @DisplayName("Should map HEADER violation with param name")
    void shouldMapHeaderViolation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta("X-Request-Id", ResourceMethodMeta.ParamSource.HEADER, String.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, ViolationDetail.of("", "must not be blank")));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        assertEquals("X-Request-Id", ex.errors().get(0).path());
        assertEquals("header", ex.errors().get(0).location());
    }

    @Test
    @DisplayName("Should map COOKIE violation with param name")
    void shouldMapCookieViolation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta("session", ResourceMethodMeta.ParamSource.COOKIE, String.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, ViolationDetail.of("", "must not be blank")));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        assertEquals("session", ex.errors().get(0).path());
        assertEquals("cookie", ex.errors().get(0).location());
    }

    @Test
    @DisplayName("Should map FORM violation with param name")
    void shouldMapFormViolation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta("username", ResourceMethodMeta.ParamSource.FORM, String.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, ViolationDetail.of("", "must not be blank")));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        assertEquals("username", ex.errors().get(0).path());
        assertEquals("form", ex.errors().get(0).location());
    }

    @Test
    @DisplayName("Should infer location from @BeanParam POJO field's JAX-RS annotation")
    void shouldInferBeanParamPojoLocation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BEAN_PARAM, PageParamBean.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, new ViolationDetail("page", "must be positive", "min", null)));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        ValidationErrorDetail err = ex.errors().get(0);
        assertEquals("page", err.path());
        assertEquals("query", err.location());
    }

    @Test
    @DisplayName("Should infer location from @BeanParam record component's JAX-RS annotation")
    void shouldInferBeanParamRecordLocation() {
        ResourceMethodMeta meta = metaWithParams(new ResourceMethodMeta.ParamMeta(
                null, ResourceMethodMeta.ParamSource.BEAN_PARAM, PageParamRecord.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, new ViolationDetail("size", "must be positive", "min", null)));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        ValidationErrorDetail err = ex.errors().get(0);
        assertEquals("size", err.path());
        assertEquals("query", err.location());
    }

    @Test
    @DisplayName("Should fall back to null location for unknown parameter index")
    void shouldFallBackForUnknownIndex() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, Object.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(-1, ViolationDetail.of("field", "invalid")));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        assertNull(ex.errors().get(0).location());
    }

    @Test
    @DisplayName("Should pass args through to ValidationErrorDetail")
    void shouldPassArgsThroughToErrorDetail() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, Object.class));
        Map<String, Object> args = Map.of("min", 1, "max", 100);
        List<ParameterViolation> violations = List.of(new ParameterViolation(
                0, new ViolationDetail("quantity", "size must be between 1 and 100", "size", args)));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        assertEquals(args, ex.errors().get(0).args());
    }

    @Test
    @DisplayName("Should handle CONTEXT parameter with null location")
    void shouldHandleContextParameter() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.CONTEXT, Object.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, ViolationDetail.of("field", "invalid")));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        assertNull(ex.errors().get(0).location());
    }

    @Test
    @DisplayName("Should handle multiple violations across different param sources")
    void shouldHandleMultipleViolations() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta("id", ResourceMethodMeta.ParamSource.PATH, String.class),
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, Object.class));
        List<ParameterViolation> violations = List.of(
                new ParameterViolation(0, ViolationDetail.of("", "must not be blank")),
                new ParameterViolation(1, new ViolationDetail("email", "invalid format", "email", null)));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        assertEquals(2, ex.errors().size());
        assertEquals("path", ex.errors().get(0).location());
        assertEquals("id", ex.errors().get(0).path());
        assertEquals("body", ex.errors().get(1).location());
        assertEquals("email", ex.errors().get(1).path());
    }

    // --- Test bean types ---

    /** POJO bean used to test {@code @BeanParam} field reflection for location inference. */
    static class PageParamBean {
        @QueryParam("page")
        int page;

        @QueryParam("size")
        int size;
    }

    /** Record used to test {@code @BeanParam} record-component reflection for location inference. */
    record PageParamRecord(
            @QueryParam("page") int page,
            @CookieParam("session") String session,
            @QueryParam("size") int size) {}
}
