// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.validation.ParameterViolation;
import dev.vertique.core.validation.PropertyPath;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.json.JacksonFieldNameResolver;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    /**
     * A routing context backed by {@code data}: {@code get}, {@code put} and {@code data()} all read
     * and write the same map, so whichever accessor a reader uses sees what a writer stored.
     */
    private static RoutingContext context(Map<String, Object> data) {
        RoutingContext ctx = mock(RoutingContext.class);
        when(ctx.get(anyString())).thenAnswer(inv -> data.get(inv.<String>getArgument(0)));
        when(ctx.get(anyString(), any())).thenAnswer(inv -> {
            Object value = data.get(inv.<String>getArgument(0));
            return value != null ? value : inv.getArgument(1);
        });
        when(ctx.put(anyString(), any())).thenAnswer(inv -> {
            data.put(inv.getArgument(0), inv.getArgument(1));
            return ctx;
        });
        when(ctx.data()).thenReturn(data);
        return ctx;
    }

    /**
     * Maps with a context that carries no body binding, the identity name projection and an arbitrary
     * projection mapper. For rows that do not concern a body pointer.
     */
    private static RestValidationException map(ResourceMethodMeta meta, List<ParameterViolation> violations) {
        return ConstraintViolationMapper.toRestValidationException(
                meta, violations, context(new HashMap<>()), InputFieldNameResolver.IDENTITY, new ObjectMapper());
    }

    // --- Tests ---

    @Test
    @DisplayName("Should map BODY violation with location body and neither member when no binder marked the body")
    void shouldMapBodyViolation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, Object.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, new ViolationDetail("name", "must not be blank", "required", null)));

        RestValidationException ex = map(meta, violations);

        assertEquals(1, ex.errors().size());
        ValidationErrorDetail err = ex.errors().get(0);
        assertNull(err.pointer(), "pointer");
        assertNull(err.parameter(), "parameter");
        assertEquals("must not be blank", err.detail());
        assertEquals("body", err.location());
        assertEquals("required", err.type());
    }

    @Test
    @DisplayName("Should keep the Java path out of an unmarked nested BODY violation")
    void shouldMapNestedBodyViolation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, Object.class));
        List<ParameterViolation> violations = List.of(
                new ParameterViolation(0, new ViolationDetail("address.city", "must not be blank", "required", null)));

        RestValidationException ex = map(meta, violations);

        ValidationErrorDetail err = ex.errors().get(0);
        assertNull(err.pointer(), "pointer");
        assertNull(err.parameter(), "parameter");
        assertNeverRendered("address", err);
        assertEquals("body", err.location());
    }

    @Test
    @DisplayName("Should map QUERY violation with param name")
    void shouldMapQueryViolation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta("page", ResourceMethodMeta.ParamSource.QUERY, int.class));
        List<ParameterViolation> violations = List.of(
                new ParameterViolation(0, new ViolationDetail("", "must be positive", "min", Map.of("value", 1))));

        RestValidationException ex = map(meta, violations);

        ValidationErrorDetail err = ex.errors().get(0);
        assertEquals("page", err.parameter());
        assertNull(err.pointer());
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

        RestValidationException ex = map(meta, violations);

        assertEquals("id", ex.errors().get(0).parameter());
        assertNull(ex.errors().get(0).pointer());
        assertEquals("path", ex.errors().get(0).location());
    }

    @Test
    @DisplayName("Should map HEADER violation with param name")
    void shouldMapHeaderViolation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta("X-Request-Id", ResourceMethodMeta.ParamSource.HEADER, String.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, ViolationDetail.of("", "must not be blank")));

        RestValidationException ex = map(meta, violations);

        assertEquals("X-Request-Id", ex.errors().get(0).parameter());
        assertNull(ex.errors().get(0).pointer());
        assertEquals("header", ex.errors().get(0).location());
    }

    @Test
    @DisplayName("Should map COOKIE violation with param name")
    void shouldMapCookieViolation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta("session", ResourceMethodMeta.ParamSource.COOKIE, String.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, ViolationDetail.of("", "must not be blank")));

        RestValidationException ex = map(meta, violations);

        assertEquals("session", ex.errors().get(0).parameter());
        assertNull(ex.errors().get(0).pointer());
        assertEquals("cookie", ex.errors().get(0).location());
    }

    @Test
    @DisplayName("Should map FORM violation with param name")
    void shouldMapFormViolation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta("username", ResourceMethodMeta.ParamSource.FORM, String.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, ViolationDetail.of("", "must not be blank")));

        RestValidationException ex = map(meta, violations);

        assertEquals("username", ex.errors().get(0).parameter());
        assertNull(ex.errors().get(0).pointer());
        assertEquals("form", ex.errors().get(0).location());
    }

    @Test
    @DisplayName("Should infer location from @BeanParam POJO field's JAX-RS annotation")
    void shouldInferBeanParamPojoLocation() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BEAN_PARAM, PageParamBean.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, new ViolationDetail("page", "must be positive", "min", null)));

        RestValidationException ex = map(meta, violations);

        ValidationErrorDetail err = ex.errors().get(0);
        assertEquals("page", err.parameter());
        assertNull(err.pointer());
        assertEquals("query", err.location());
    }

    @Test
    @DisplayName("Should infer location from @BeanParam record component's JAX-RS annotation")
    void shouldInferBeanParamRecordLocation() {
        ResourceMethodMeta meta = metaWithParams(new ResourceMethodMeta.ParamMeta(
                null, ResourceMethodMeta.ParamSource.BEAN_PARAM, PageParamRecord.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, new ViolationDetail("size", "must be positive", "min", null)));

        RestValidationException ex = map(meta, violations);

        ValidationErrorDetail err = ex.errors().get(0);
        assertEquals("size", err.parameter());
        assertNull(err.pointer());
        assertEquals("query", err.location());
    }

    @Test
    @DisplayName("Should render an indexed @BeanParam list member like the unindexed field")
    void shouldRenderIndexedBeanParamListMemberLikeField() {
        ValidationErrorDetail plain = beanParamError("ids");
        ValidationErrorDetail indexed = beanParamError("ids[2]");

        assertEquals("id", plain.parameter());
        assertNull(plain.pointer());
        assertEquals("query", plain.location());
        assertEquals("id", indexed.parameter());
        assertEquals("query", indexed.location());
        assertEquals(plain, indexed);
    }

    @Test
    @DisplayName("Should render the same entry for every position of a @BeanParam list member")
    void shouldRenderSameEntryForEveryBeanParamPosition() {
        ValidationErrorDetail first = beanParamError("ids[0]");
        ValidationErrorDetail later = beanParamError("ids[7]");

        assertEquals("id", first.parameter());
        assertEquals("query", first.location());
        assertEquals(first, later);
    }

    @Test
    @DisplayName("Should render only the member name of a nested @BeanParam path without a structured path")
    void shouldRenderOnlyMemberNameOfNestedBeanParamPath() {
        ValidationErrorDetail plain = beanParamError("addresses.city");
        ValidationErrorDetail indexed = beanParamError("addresses[0].city");

        assertEquals("addr", plain.parameter());
        assertNull(plain.pointer());
        assertEquals("query", plain.location());
        assertEquals("addr", indexed.parameter());
        assertEquals("query", indexed.location());
        assertEquals(plain, indexed);
    }

    @Test
    @DisplayName("Should render an unkeyed map or set marker of a @BeanParam member like the plain path")
    void shouldRenderBeanParamOpaqueMarkerLikePlainPath() {
        ValidationErrorDetail plainMap = beanParamError("byKey.name");
        ValidationErrorDetail markedMap = beanParamError("byKey[].name");
        ValidationErrorDetail plainSet = beanParamError("codes");
        ValidationErrorDetail markedSet = beanParamError("codes[]");

        assertEquals("by", plainMap.parameter());
        assertEquals("query", plainMap.location());
        assertEquals(plainMap, markedMap);
        assertEquals("code", plainSet.parameter());
        assertEquals("query", plainSet.location());
        assertEquals(plainSet, markedSet);
    }

    @Test
    @DisplayName("Should render the same entry for a deeply nested @BeanParam path with position markers")
    void shouldRenderSameEntryForNestedBeanParamPathWithMarkers() {
        ValidationErrorDetail plain = beanParamError("addresses.lines.text");
        ValidationErrorDetail marked = beanParamError("addresses[0].lines[3].text");

        assertEquals("addr", plain.parameter());
        assertEquals("query", plain.location());
        assertEquals(plain, marked);
    }

    @Test
    @DisplayName("Should render neither member for an unannotated @BeanParam member, with or without markers")
    void shouldRenderNeitherMemberForUnannotatedBeanParamMember() {
        ValidationErrorDetail indexed = beanParamError("unmapped[1]");
        ValidationErrorDetail marked = beanParamError("unmapped[].x");

        assertNeitherMemberNorLocation(indexed);
        assertNeverRendered("unmapped", indexed);
        assertEquals("must be positive", indexed.detail());
        assertEquals("min", indexed.type());
        assertNeitherMemberNorLocation(marked);
        assertNeverRendered("unmapped", marked);
        assertEquals("must be positive", marked.detail());
        assertEquals("min", marked.type());
    }

    private static ValidationErrorDetail beanParamError(String path) {
        ResourceMethodMeta meta = metaWithParams(new ResourceMethodMeta.ParamMeta(
                null, ResourceMethodMeta.ParamSource.BEAN_PARAM, CollectionParamBean.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, new ViolationDetail(path, "must be positive", "min", null)));

        return map(meta, violations).errors().get(0);
    }

    @Test
    @DisplayName("Should render neither member for an unknown parameter index")
    void shouldFallBackForUnknownIndex() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, Object.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(-1, ViolationDetail.of("field", "invalid")));

        RestValidationException ex = map(meta, violations);

        assertNeitherMemberNorLocation(ex.errors().get(0));
        assertNeverRendered("field", ex.errors().get(0));
    }

    @Test
    @DisplayName("Should pass args through to ValidationErrorDetail")
    void shouldPassArgsThroughToErrorDetail() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, Object.class));
        Map<String, Object> args = Map.of("min", 1, "max", 100);
        List<ParameterViolation> violations = List.of(new ParameterViolation(
                0, new ViolationDetail("quantity", "size must be between 1 and 100", "size", args)));

        RestValidationException ex = map(meta, violations);

        assertEquals(args, ex.errors().get(0).args());
    }

    @Test
    @DisplayName("Should handle CONTEXT parameter with null location")
    void shouldHandleContextParameter() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.CONTEXT, Object.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(0, ViolationDetail.of("field", "invalid")));

        RestValidationException ex = map(meta, violations);

        assertNeitherMemberNorLocation(ex.errors().get(0));
        assertNeverRendered("field", ex.errors().get(0));
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

        RestValidationException ex = map(meta, violations);

        assertEquals(2, ex.errors().size());
        assertEquals("path", ex.errors().get(0).location());
        assertEquals("id", ex.errors().get(0).parameter());
        assertNull(ex.errors().get(0).pointer());
        assertEquals("body", ex.errors().get(1).location());
        assertNull(ex.errors().get(1).pointer(), "pointer");
        assertNull(ex.errors().get(1).parameter(), "parameter");
    }

    // --- Parameter members of non-body entries ---

    private static PropertyPath.Segment prop(String name) {
        return new PropertyPath.Property(name, null);
    }

    private static PropertyPath.Segment idx(int index) {
        return new PropertyPath.Index(index);
    }

    private static PropertyPath.Segment opaque() {
        return new PropertyPath.Opaque();
    }

    private static PropertyPath path(PropertyPath.Segment... segments) {
        return new PropertyPath(List.of(segments));
    }

    /** Maps one violation on a method whose only parameter is {@code pm}. */
    private static ValidationErrorDetail mapSingle(ResourceMethodMeta.ParamMeta pm, ParameterViolation violation) {
        return map(metaWithParams(pm), List.of(violation)).errors().get(0);
    }

    private static ParameterViolation violation(String javaPath, PropertyPath propertyPath) {
        return new ParameterViolation(0, new ViolationDetail(javaPath, "must be positive", "min", null), propertyPath);
    }

    private static void assertNeitherMemberNorLocation(ValidationErrorDetail err) {
        assertNull(err.pointer(), "pointer");
        assertNull(err.parameter(), "parameter");
        assertNull(err.location(), "location");
    }

    /** The whole rendered entry, so a client-chosen text cannot hide in any member. */
    private static void assertNeverRendered(String text, ValidationErrorDetail err) {
        assertFalse(err.toString().contains(text), () -> text + " leaked into " + err);
    }

    /** The field names cached for a bean type, empty when the type has no cache entry. */
    private static Set<Object> cachedFieldNames(Class<?> beanType) {
        try {
            Field cacheField = ConstraintViolationMapper.class.getDeclaredField("BEAN_FIELD_CACHE");
            cacheField.setAccessible(true);
            Map<?, ?> cache = (Map<?, ?>) cacheField.get(null);
            Object forType = cache.get(beanType);
            return forType == null ? Set.of() : new HashSet<>(((Map<?, ?>) forType).keySet());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static Stream<Arguments> declaredParameterLocations() {
        return Stream.of(
                Arguments.of(ResourceMethodMeta.ParamSource.QUERY, "page", "query"),
                Arguments.of(ResourceMethodMeta.ParamSource.PATH, "id", "path"),
                Arguments.of(ResourceMethodMeta.ParamSource.HEADER, "X-Tenant", "header"),
                Arguments.of(ResourceMethodMeta.ParamSource.COOKIE, "session", "cookie"),
                Arguments.of(ResourceMethodMeta.ParamSource.FORM, "note", "form"));
    }

    @ParameterizedTest(name = "{0} reports {1}")
    @MethodSource("declaredParameterLocations")
    @DisplayName("Should report the declared parameter name for every parameter location")
    void parameterLocationsReportTheDeclaredName(
            ResourceMethodMeta.ParamSource source, String declaredName, String location) {
        ValidationErrorDetail err = mapSingle(
                new ResourceMethodMeta.ParamMeta(declaredName, source, String.class),
                violation("", PropertyPath.root()));

        assertEquals(declaredName, err.parameter());
        assertNull(err.pointer());
        assertEquals(location, err.location());
        assertEquals("must be positive", err.detail());
        assertEquals("min", err.type());
    }

    @ParameterizedTest(name = "{0} without a structured path")
    @MethodSource("declaredParameterLocations")
    @DisplayName("Should render the declared name alone when the structured path is missing")
    void missingStructuredPathRendersNoIndexRun(
            ResourceMethodMeta.ParamSource source, String declaredName, String location) {
        ValidationErrorDetail err = mapSingle(
                new ResourceMethodMeta.ParamMeta(declaredName, source, List.class), violation("ZZKEY[3].x", null));

        assertEquals(declaredName, err.parameter());
        assertNull(err.pointer());
        assertEquals(location, err.location());
        assertNeverRendered("ZZKEY", err);
    }

    @ParameterizedTest(name = "{0} with an empty declared name")
    @MethodSource("declaredParameterLocations")
    @DisplayName("Should render neither member when the declared name is empty")
    void emptyDeclaredNameRendersNeitherMember(
            ResourceMethodMeta.ParamSource source, String ignoredName, String ignoredLocation) {
        ResourceMethodMeta.ParamMeta pm = new ResourceMethodMeta.ParamMeta("", source, List.class);

        for (ValidationErrorDetail err : List.of(
                mapSingle(pm, violation("", PropertyPath.root())),
                mapSingle(pm, violation("[1]", path(idx(1)))),
                mapSingle(pm, violation("", null)))) {
            assertNull(err.pointer());
            assertNull(err.parameter());
            assertEquals("must be positive", err.detail());
            assertEquals("min", err.type());
        }
    }

    @Test
    @DisplayName("Should render neither member for a @BeanParam member whose annotation name is empty")
    void emptyBeanParamAnnotationNameRendersNeitherMember() {
        ResourceMethodMeta.ParamMeta pm =
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BEAN_PARAM, BlankNameBean.class);

        for (ValidationErrorDetail err : List.of(
                mapSingle(pm, violation("blank", path(prop("blank")))), mapSingle(pm, violation("blank", null)))) {
            assertNull(err.pointer());
            assertNull(err.parameter());
            assertEquals("must be positive", err.detail());
        }
    }

    @Test
    @DisplayName("Should carry leading indexes for container parameters and @BeanParam members")
    void containerAndBeanParamMembersCarryLeadingIndexes() {
        // Given a query list parameter violated at its second item
        ValidationErrorDetail query = mapSingle(
                new ResourceMethodMeta.ParamMeta("ids", ResourceMethodMeta.ParamSource.QUERY, List.class),
                violation("[1]", path(idx(1))));

        // And a @BeanParam record whose form list member is violated at its first item
        ResourceMethodMeta.ParamMeta bean =
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BEAN_PARAM, TagsBean.class);
        ValidationErrorDetail indexed = mapSingle(bean, violation("tags[0]", path(prop("tags"), idx(0))));

        // And the same member without a structured path, whose Java path holds a client map key
        Set<Object> cachedBefore = cachedFieldNames(TagsBean.class);
        ValidationErrorDetail unstructured = mapSingle(bean, violation("tags[ZZKEY].x", null));
        Set<Object> cachedAfter = cachedFieldNames(TagsBean.class);

        assertEquals("ids[1]", query.parameter());
        assertNull(query.pointer());
        assertEquals("query", query.location());

        assertEquals("tag[0]", indexed.parameter());
        assertNull(indexed.pointer());
        assertEquals("form", indexed.location());

        assertEquals("tag", unstructured.parameter());
        assertNull(unstructured.pointer());
        assertEquals("form", unstructured.location());
        assertNeverRendered("ZZKEY", unstructured);

        Set<Object> added = new HashSet<>(cachedAfter);
        added.removeAll(cachedBefore);
        assertTrue(Set.of("tags").containsAll(added), () -> "request-derived cache keys: " + added);
        assertTrue(Set.of("tags").containsAll(cachedAfter), () -> "request-derived cache keys: " + cachedAfter);
    }

    static Stream<Arguments> leadingIndexRuns() {
        return Stream.of(
                Arguments.of(List.<PropertyPath.Segment>of(), "ids"),
                Arguments.of(List.of(idx(1)), "ids[1]"),
                Arguments.of(List.of(idx(0), idx(2)), "ids[0][2]"),
                Arguments.of(List.of(idx(1), prop("x")), "ids[1]"),
                Arguments.of(List.of(idx(1), prop("x"), idx(4)), "ids[1]"),
                Arguments.of(List.of(idx(1), opaque(), idx(2)), "ids[1]"),
                Arguments.of(List.of(opaque()), "ids"),
                Arguments.of(List.of(opaque(), idx(1)), "ids"),
                Arguments.of(List.of(prop("x"), idx(1)), "ids"));
    }

    @ParameterizedTest(name = "{0} renders {1}")
    @MethodSource("leadingIndexRuns")
    @DisplayName("Should stop the index run of a container parameter at the first non-index segment")
    void indexRunStopsAtFirstNonIndexSegment(List<PropertyPath.Segment> segments, String expected) {
        ValidationErrorDetail err = mapSingle(
                new ResourceMethodMeta.ParamMeta("ids", ResourceMethodMeta.ParamSource.QUERY, List.class),
                violation("ZZKEY", new PropertyPath(segments)));

        assertEquals(expected, err.parameter());
        assertNull(err.pointer());
        assertEquals("query", err.location());
        assertNeverRendered("ZZKEY", err);
    }

    static Stream<Arguments> beanParamIndexRuns() {
        return Stream.of(
                Arguments.of(List.of(prop("ids")), "id"),
                Arguments.of(List.of(prop("ids"), idx(2)), "id[2]"),
                Arguments.of(List.of(prop("addresses"), idx(0), prop("city")), "addr[0]"),
                Arguments.of(List.of(prop("addresses"), idx(0), idx(2), prop("city")), "addr[0][2]"),
                Arguments.of(List.of(prop("addresses"), opaque(), idx(1)), "addr"),
                Arguments.of(List.of(prop("byKey"), opaque(), prop("name")), "by"),
                Arguments.of(List.of(prop("codes"), opaque()), "code"));
    }

    @ParameterizedTest(name = "{0} renders {1}")
    @MethodSource("beanParamIndexRuns")
    @DisplayName("Should render the index run that follows the @BeanParam root field and stop at the next property")
    void beanParamIndexRunFollowsTheRootField(List<PropertyPath.Segment> segments, String expected) {
        ValidationErrorDetail err = mapSingle(
                new ResourceMethodMeta.ParamMeta(
                        null, ResourceMethodMeta.ParamSource.BEAN_PARAM, CollectionParamBean.class),
                violation("ZZKEY", new PropertyPath(segments)));

        assertEquals(expected, err.parameter());
        assertNull(err.pointer());
        assertEquals("query", err.location());
        assertNeverRendered("ZZKEY", err);
    }

    @Test
    @DisplayName("Should never cache a @BeanParam root name that is not a declared field")
    void undeclaredBeanParamRootNamesAreNeverCached() {
        ResourceMethodMeta.ParamMeta pm =
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BEAN_PARAM, UncachedBean.class);

        ValidationErrorDetail fromJavaPath = mapSingle(pm, violation("ZZKEY.x", null));
        ValidationErrorDetail fromBracketedPath = mapSingle(pm, violation("ZZMAP[ZZKEY]", null));
        ValidationErrorDetail fromStructuredPath = mapSingle(pm, violation("ZZNAME", path(prop("ZZNAME"))));

        for (ValidationErrorDetail err : List.of(fromJavaPath, fromBracketedPath, fromStructuredPath)) {
            assertNull(err.pointer());
            assertNull(err.parameter());
            assertNeverRendered("ZZ", err);
        }
        Set<Object> cached = cachedFieldNames(UncachedBean.class);
        assertTrue(Set.of("declared").containsAll(cached), () -> "request-derived cache keys: " + cached);
    }

    @ParameterizedTest(name = "parameter index {0}")
    @ValueSource(ints = {-1, 3})
    @DisplayName("Should render neither member when the parameter cannot be determined")
    void undeterminableLocationsCarryNeitherMember(int parameterIndex) {
        // Given a cross-parameter or return-value style violation without a parameter index
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta("page", ResourceMethodMeta.ParamSource.QUERY, int.class));
        ParameterViolation unknown = new ParameterViolation(
                parameterIndex, new ViolationDetail("ZZFIELD", "invalid", "custom", null), path(prop("ZZFIELD")));
        // And a @BeanParam member without a JAX-RS annotation
        ValidationErrorDetail unannotatedStructured = mapSingle(
                new ResourceMethodMeta.ParamMeta(
                        null, ResourceMethodMeta.ParamSource.BEAN_PARAM, CollectionParamBean.class),
                new ParameterViolation(
                        0, new ViolationDetail("unmapped", "invalid", "custom", null), path(prop("unmapped"))));
        ValidationErrorDetail unannotatedUnstructured = mapSingle(
                new ResourceMethodMeta.ParamMeta(
                        null, ResourceMethodMeta.ParamSource.BEAN_PARAM, CollectionParamBean.class),
                new ParameterViolation(0, new ViolationDetail("unmapped", "invalid", "custom", null)));

        // When mapped
        ValidationErrorDetail unknownIndex =
                map(meta, List.of(unknown)).errors().get(0);

        // Then neither member and no location are carried, while detail and type are kept
        for (ValidationErrorDetail err : List.of(unknownIndex, unannotatedStructured, unannotatedUnstructured)) {
            assertNeitherMemberNorLocation(err);
            assertNeverRendered("ZZFIELD", err);
            assertNeverRendered("unmapped", err);
            assertEquals("invalid", err.detail());
            assertEquals("custom", err.type());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = ResourceMethodMeta.ParamSource.class,
            names = {"CONTEXT", "PRECONDITIONS", "FILE_UPLOADS", "ENTITY_PARTS"})
    @DisplayName("Should render neither member for a violation on a framework-injected parameter")
    void otherParameterSourcesCarryNeitherMember(ResourceMethodMeta.ParamSource source) {
        ResourceMethodMeta.ParamMeta pm = new ResourceMethodMeta.ParamMeta(null, source, Object.class);

        for (ValidationErrorDetail err : List.of(
                mapSingle(pm, violation("ZZPATH", null)),
                mapSingle(pm, violation("ZZPATH", path(prop("ZZPATH"), idx(1)))))) {
            assertNeitherMemberNorLocation(err);
            assertNeverRendered("ZZPATH", err);
            assertEquals("must be positive", err.detail());
            assertEquals("min", err.type());
        }
    }

    // --- BODY entries: pointer, form parameter or neither member ---

    /** A body with one renamed member. */
    record BodyAddress(@JsonProperty("zip_code") String zipCode) {}

    /** A body whose member is renamed only by a naming strategy. */
    record BodyShipping(String zipCode) {}

    /** A list element with one member. */
    record BodyItem(String name) {}

    /** A body holding a list of {@link BodyItem}s. */
    record BodyCart(List<BodyItem> items) {}

    /** A form body whose list member is renamed. */
    record BodyTags(@JsonProperty("tag_list") List<String> tags) {}

    /**
     * The routing state of one request. The mark, the route mapper the dispatch stashed and the
     * document the body was bound from are the only inputs the mapper reads from the context.
     */
    private static final class Request {
        private final Map<String, Object> data = new HashMap<>();
        private final RoutingContext ctx = context(data);

        Request body(Object document) {
            BoundRequest bound = mock(BoundRequest.class);
            when(bound.body()).thenReturn(RequestValue.of(document));
            data.put(BoundRequest.KEY_META_DATA_BOUND_REQUEST, bound);
            return this;
        }

        Request routeMapper(ObjectMapper mapper) {
            data.put(BoundRequest.KEY_RESOLVED_BODY_MAPPER, mapper);
            return this;
        }

        Request marked(BodyBindingMark.Kind kind, ObjectMapper mapper) {
            BodyBindingMark.record(ctx, kind, mapper);
            return this;
        }
    }

    private static ResourceMethodMeta bodyMeta(Class<?> type) {
        return metaWithParams(new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, type));
    }

    /** Maps the way the invoker does: the route's projection is built from {@code projectionMapper}. */
    private static RestValidationException mapBody(
            ResourceMethodMeta meta, List<ParameterViolation> violations, Request request, ObjectMapper projection) {
        return ConstraintViolationMapper.toRestValidationException(
                meta, violations, request.ctx, JacksonFieldNameResolver.forMapper(projection), projection);
    }

    private static ValidationErrorDetail mapOneBody(
            Class<?> type, PropertyPath path, Request request, ObjectMapper projection) {
        return mapBody(bodyMeta(type), List.of(bodyViolation(path)), request, projection)
                .errors()
                .get(0);
    }

    private static ParameterViolation bodyViolation(PropertyPath path) {
        return new ParameterViolation(
                0, new ViolationDetail(path.toJavaPath(), "must not be blank", "required", null), path);
    }

    private static JsonObject zipCodeDocument() {
        return new JsonObject().put("zip_code", "");
    }

    private static ObjectMapper snakeCaseMapper() {
        return new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    private static void assertPointer(String expected, ValidationErrorDetail err) {
        assertEquals(expected, err.pointer(), "pointer");
        assertNull(err.parameter(), "parameter");
        assertEquals("body", err.location());
        assertEquals("must not be blank", err.detail());
        assertEquals("required", err.type());
    }

    private static void assertBodyWithoutMembers(ValidationErrorDetail err) {
        assertNull(err.pointer(), "pointer");
        assertNull(err.parameter(), "parameter");
        assertEquals("body", err.location());
        assertEquals("must not be blank", err.detail());
        assertEquals("required", err.type());
    }

    private static void assertFormParameter(String expected, ValidationErrorDetail err) {
        assertNull(err.pointer(), "pointer");
        assertEquals(expected, err.parameter(), "parameter");
        assertEquals("form", err.location());
    }

    private static void assertFormWithoutMembers(ValidationErrorDetail err) {
        assertNull(err.pointer(), "pointer");
        assertNull(err.parameter(), "parameter");
        assertEquals("form", err.location());
    }

    private static void assertAtMostOneMember(ValidationErrorDetail err) {
        assertTrue(err.pointer() == null || err.parameter() == null, () -> "both members set on " + err);
    }

    @Test
    @DisplayName("Should report a pointer for a JSON body bound with the effective and projection mapper")
    void jsonBodyBoundWithTheRouteMapperReportsAPointer() {
        // Given a body a framework JSON binder materialized with the route mapper, and a renamed member
        ObjectMapper routeMapper = new ObjectMapper();
        Request request = new Request()
                .body(zipCodeDocument())
                .routeMapper(routeMapper)
                .marked(BodyBindingMark.Kind.JSON, routeMapper);

        // When the blank member is mapped
        ValidationErrorDetail err = mapOneBody(BodyAddress.class, path(prop("zipCode")), request, routeMapper);

        // Then the wire name is the pointer, with location body and no parameter
        assertPointer("/zip_code", err);
    }

    @Test
    @DisplayName("Should resolve a root list body through its generic type")
    void rootListBodyResolvesThroughTheGenericType() {
        // Given a List<BodyItem> body, whose declared class alone does not name the element type
        ObjectMapper routeMapper = new ObjectMapper();
        ResourceMethodMeta meta = metaWithParams(new ResourceMethodMeta.ParamMeta(
                null,
                ResourceMethodMeta.ParamSource.BODY,
                List.class,
                BodyItem.class,
                new TypeReference<List<BodyItem>>() {}.getType(),
                null,
                (Annotation[]) null));
        Request request = new Request()
                .body(new JsonArray().add(new JsonObject().put("name", "a")).add(new JsonObject()))
                .routeMapper(routeMapper)
                .marked(BodyBindingMark.Kind.JSON, routeMapper);

        // When the second element's missing member is mapped
        ValidationErrorDetail err = mapBody(
                        meta, List.of(bodyViolation(path(idx(1), prop("name")))), request, routeMapper)
                .errors()
                .get(0);

        // Then the pointer starts with the position in the root array
        assertPointer("/1/name", err);
    }

    @Test
    @DisplayName("Should report a class-level body violation as the empty pointer")
    void wholeBodyViolationReportsTheEmptyPointer() {
        // Given a marked JSON body and a violation of the body itself
        ObjectMapper routeMapper = new ObjectMapper();
        Request request = new Request()
                .body(zipCodeDocument())
                .routeMapper(routeMapper)
                .marked(BodyBindingMark.Kind.JSON, routeMapper);

        // When mapped
        ValidationErrorDetail err = mapOneBody(BodyAddress.class, PropertyPath.root(), request, routeMapper);

        // Then the pointer is present and empty, not absent
        assertEquals("", err.pointer());
        assertNull(err.parameter());
        assertEquals("body", err.location());
    }

    @Test
    @DisplayName("Should report neither member for a body no framework binder materialized")
    void unmarkedBodyReportsNeitherMember() {
        // Given an application-decoded body: a document and a route mapper but no mark
        ObjectMapper routeMapper = new ObjectMapper();
        Request request = new Request().body(zipCodeDocument()).routeMapper(routeMapper);

        // When mapped
        ValidationErrorDetail err = mapOneBody(BodyAddress.class, path(prop("zipCode")), request, routeMapper);

        // Then the location stays body and no Java name is offered in its place
        assertBodyWithoutMembers(err);
        assertNeverRendered("zipCode", err);
    }

    /** A violation that carries only the validator's path text, as a validator without structured paths yields. */
    private static ParameterViolation textOnlyBodyViolation(String javaPath) {
        return new ParameterViolation(0, new ViolationDetail(javaPath, "must not be blank", "required", null), null);
    }

    @Test
    @DisplayName("Should report neither member for a marked JSON body violation that has only the path text")
    void textOnlyBodyViolationReportsNeitherMember() {
        // Given a body a framework binder materialized with the route mapper, whose document holds the member
        ObjectMapper routeMapper = new ObjectMapper();
        Request request = new Request()
                .body(zipCodeDocument())
                .routeMapper(routeMapper)
                .marked(BodyBindingMark.Kind.JSON, routeMapper);

        // When violations without a structured path are mapped, one of them nested
        List<ValidationErrorDetail> errors = mapBody(
                        bodyMeta(BodyAddress.class),
                        List.of(textOnlyBodyViolation("zipCode"), textOnlyBodyViolation("ZZNESTED[a].zipCode")),
                        request,
                        routeMapper)
                .errors();

        // Then the location stays body, the detail and type are kept and no text of the path is offered
        assertEquals(2, errors.size());
        for (ValidationErrorDetail err : errors) {
            assertBodyWithoutMembers(err);
            assertNeverRendered("zipCode", err);
            assertNeverRendered("ZZNESTED", err);
        }
    }

    @Test
    @DisplayName("Should count violations with only the path text toward the body cap")
    void textOnlyBodyViolationsCountTowardTheCap() {
        // Given 100 path-text violations that sort first and 5 structured ones that sort after them
        ObjectMapper routeMapper = new ObjectMapper();
        JsonArray items = new JsonArray();
        List<ParameterViolation> violations = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            violations.add(textOnlyBodyViolation("a%03d".formatted(i)));
        }
        for (int i = 0; i < 5; i++) {
            items.add(new JsonObject().put("name", ""));
            violations.add(bodyViolation(path(prop("items"), idx(i), prop("name"))));
        }
        Request request = new Request()
                .body(new JsonObject().put("items", items))
                .routeMapper(routeMapper)
                .marked(BodyBindingMark.Kind.JSON, routeMapper);

        // When mapped
        List<ValidationErrorDetail> errors = mapBody(bodyMeta(BodyCart.class), violations, request, routeMapper)
                .errors();

        // Then all 105 entries are without members: the path-text ones used up the cap
        assertEquals(105, errors.size());
        for (ValidationErrorDetail err : errors) {
            assertBodyWithoutMembers(err);
        }
    }

    @Test
    @DisplayName("Should report neither member for a JSON mark with no readable document")
    void markedJsonBodyWithoutAReadableDocumentReportsNeitherMember() {
        // Given JSON marks whose document cannot be read: no bound request, a scalar, a failing read
        ObjectMapper routeMapper = new ObjectMapper();
        Request noBoundRequest = new Request().routeMapper(routeMapper).marked(BodyBindingMark.Kind.JSON, routeMapper);
        Request scalar = new Request()
                .body("not a document")
                .routeMapper(routeMapper)
                .marked(BodyBindingMark.Kind.JSON, routeMapper);
        Request failingRead = new Request().routeMapper(routeMapper).marked(BodyBindingMark.Kind.JSON, routeMapper);
        BoundRequest failing = mock(BoundRequest.class);
        when(failing.body()).thenThrow(new IllegalStateException("unreadable"));
        failingRead.data.put(BoundRequest.KEY_META_DATA_BOUND_REQUEST, failing);

        // When the same structured violation is mapped for each
        for (Request request : List.of(noBoundRequest, scalar, failingRead)) {
            ValidationErrorDetail err = mapOneBody(BodyAddress.class, path(prop("zipCode")), request, routeMapper);

            // Then it is located in the body without a pointer or a parameter
            assertBodyWithoutMembers(err);
            assertNeverRendered("zipCode", err);
        }
    }

    @Test
    @DisplayName("Should report neither member when the marked mapper is not the effective mapper")
    void markedMapperOtherThanTheEffectiveMapperOmitsThePointer() {
        // Given a body bound with one mapper while the request's route mapper is another
        ObjectMapper marked = new ObjectMapper();
        ObjectMapper routeMapper = new ObjectMapper();
        Request request = new Request()
                .body(zipCodeDocument())
                .routeMapper(routeMapper)
                .marked(BodyBindingMark.Kind.JSON, marked);

        // When mapped with a projection built from the route mapper
        ValidationErrorDetail err = mapOneBody(BodyAddress.class, path(prop("zipCode")), request, routeMapper);

        // Then no pointer is projected with the wrong names
        assertBodyWithoutMembers(err);
    }

    @Test
    @DisplayName("Should compare mappers by identity, not by configuration")
    void equivalentButDistinctMapperInstanceOmitsThePointer() {
        // Given a mark holding a copy of the effective mapper: same configuration, other instance
        ObjectMapper routeMapper = new ObjectMapper();
        Request request = new Request()
                .body(zipCodeDocument())
                .routeMapper(routeMapper)
                .marked(BodyBindingMark.Kind.JSON, routeMapper.copy());

        // When mapped
        ValidationErrorDetail err = mapOneBody(BodyAddress.class, path(prop("zipCode")), request, routeMapper);

        // Then the identity check fails and no pointer is projected
        assertBodyWithoutMembers(err);
    }

    @Test
    @DisplayName("Should report neither member when the projection was built from another mapper")
    void projectionBuiltFromAnotherMapperOmitsThePointer() {
        // Given a mark equal to the effective mapper, but a projection captured from a different one
        ObjectMapper routeMapper = new ObjectMapper();
        ObjectMapper capturedAtRegistration = new ObjectMapper();
        Request request = new Request()
                .body(zipCodeDocument())
                .routeMapper(routeMapper)
                .marked(BodyBindingMark.Kind.JSON, routeMapper);

        // When mapped with that projection mapper
        ValidationErrorDetail err =
                mapOneBody(BodyAddress.class, path(prop("zipCode")), request, capturedAtRegistration);

        // Then all three mappers are not the same instance, so no pointer is projected
        assertBodyWithoutMembers(err);
    }

    @Test
    @DisplayName("Should read the live process mapper when the route stashed no mapper")
    void routeWithoutAStashedMapperUsesTheLiveProcessMapper() {
        // Given a default-profile route: nothing under the route-mapper key
        ObjectMapper processMapper = VertiqueJson.mapper();
        Request bound = new Request().body(zipCodeDocument()).marked(BodyBindingMark.Kind.JSON, processMapper);
        Request boundByOther =
                new Request().body(zipCodeDocument()).marked(BodyBindingMark.Kind.JSON, new ObjectMapper());

        // When mapped with a projection built from the process mapper
        ValidationErrorDetail matching = mapOneBody(BodyAddress.class, path(prop("zipCode")), bound, processMapper);
        ValidationErrorDetail mismatching =
                mapOneBody(BodyAddress.class, path(prop("zipCode")), boundByOther, processMapper);

        // Then only the body bound with the process mapper gets a pointer
        assertPointer("/zip_code", matching);
        assertBodyWithoutMembers(mismatching);
    }

    @Test
    @DisplayName("Should compute the effective mapper per request, never cache it between calls")
    void effectiveMapperIsReadPerRequest() {
        // Given one route (one meta, one projection) and a body bound with mapper a
        ObjectMapper a = new ObjectMapper();
        ObjectMapper b = new ObjectMapper();
        ResourceMethodMeta meta = bodyMeta(BodyAddress.class);
        InputFieldNameResolver names = JacksonFieldNameResolver.forMapper(a);
        List<ParameterViolation> violations = List.of(bodyViolation(path(prop("zipCode"))));
        Request request = new Request().body(zipCodeDocument()).routeMapper(a).marked(BodyBindingMark.Kind.JSON, a);

        // When the effective mapper is a, then b, then a again between three calls
        ValidationErrorDetail first = ConstraintViolationMapper.toRestValidationException(
                        meta, violations, request.ctx, names, a)
                .errors()
                .get(0);
        request.routeMapper(b);
        ValidationErrorDetail second = ConstraintViolationMapper.toRestValidationException(
                        meta, violations, request.ctx, names, a)
                .errors()
                .get(0);
        request.routeMapper(a);
        ValidationErrorDetail third = ConstraintViolationMapper.toRestValidationException(
                        meta, violations, request.ctx, names, a)
                .errors()
                .get(0);

        // Then each call follows the mapper in force for it
        assertPointer("/zip_code", first);
        assertBodyWithoutMembers(second);
        assertPointer("/zip_code", third);
    }

    @Test
    @DisplayName("Should report a form body member under its wire name with location form")
    void formBodyReportsTheMarkedMappersPrimaryWireName() {
        // Given a form-urlencoded body bound to a record with a renamed member
        ObjectMapper codec = new ObjectMapper();
        Request request = new Request().routeMapper(codec).marked(BodyBindingMark.Kind.FORM, codec);

        // When the blank member is mapped
        ValidationErrorDetail err = mapOneBody(BodyAddress.class, path(prop("zipCode")), request, codec);

        // Then the entry is a parameter in the form location, with no pointer
        assertFormParameter("zip_code", err);
        assertEquals("must not be blank", err.detail());
        assertEquals("required", err.type());
    }

    @Test
    @DisplayName("Should name a form member with the mapper the form binder passed, not the route mapper")
    void formBodyUsesTheMapperItWasBoundWith() {
        // Given a form body bound on the process codec while the route stashed a snake-case profile mapper
        ObjectMapper processCodec = new ObjectMapper();
        ObjectMapper routeProfile = snakeCaseMapper();
        Request onProcessCodec =
                new Request().routeMapper(routeProfile).marked(BodyBindingMark.Kind.FORM, processCodec);
        // And a form body the object-processor branch bound with the route's own profile mapper
        Request onRouteProfile =
                new Request().routeMapper(routeProfile).marked(BodyBindingMark.Kind.FORM, routeProfile);

        // When the same unannotated member is mapped
        ValidationErrorDetail viaProcessCodec =
                mapOneBody(BodyShipping.class, path(prop("zipCode")), onProcessCodec, routeProfile);
        ValidationErrorDetail viaRouteProfile =
                mapOneBody(BodyShipping.class, path(prop("zipCode")), onRouteProfile, routeProfile);

        // Then each entry carries the name of the mapper that bound it
        assertFormParameter("zipCode", viaProcessCodec);
        assertFormParameter("zip_code", viaRouteProfile);
    }

    @Test
    @DisplayName("Should append only the leading position run after a form member's wire name")
    void formBodyAppendsTheLeadingPositionRun() {
        // Given a form body with a renamed list member
        ObjectMapper codec = new ObjectMapper();
        Request request = new Request().routeMapper(codec).marked(BodyBindingMark.Kind.FORM, codec);

        // When positions, a later property and an opaque marker follow the member
        ValidationErrorDetail position = mapOneBody(BodyTags.class, path(prop("tags"), idx(1)), request, codec);
        ValidationErrorDetail nested =
                mapOneBody(BodyTags.class, path(prop("tags"), idx(0), idx(2), prop("name")), request, codec);
        ValidationErrorDetail opaqueMarker = mapOneBody(BodyTags.class, path(prop("tags"), opaque()), request, codec);
        ValidationErrorDetail memberItself = mapOneBody(BodyTags.class, path(prop("tags")), request, codec);

        // Then the run of positions is kept and nothing after the first other segment
        assertFormParameter("tag_list[1]", position);
        assertFormParameter("tag_list[0][2]", nested);
        assertFormParameter("tag_list", opaqueMarker);
        assertFormParameter("tag_list", memberItself);
    }

    @Test
    @DisplayName("Should report neither member for a form property the mapper does not bind")
    void formBodyPropertyThatIsNotBoundReportsNeitherMember() {
        // Given a form body and a violation on a property the bound type does not have
        ObjectMapper codec = new ObjectMapper();
        Request request = new Request().routeMapper(codec).marked(BodyBindingMark.Kind.FORM, codec);

        // When the unknown property and the body itself are mapped
        ValidationErrorDetail unknown = mapOneBody(BodyAddress.class, path(prop("ZZUNBOUND")), request, codec);
        ValidationErrorDetail whole = mapOneBody(BodyAddress.class, PropertyPath.root(), request, codec);

        // Then no name is offered, and the Java name is never rendered
        assertFormWithoutMembers(unknown);
        assertNeverRendered("ZZUNBOUND", unknown);
        assertFormWithoutMembers(whole);
    }

    @Test
    @DisplayName("Should not cap form body entries")
    void formBodyEntriesAreNotCapped() {
        // Given a form body with many violations
        ObjectMapper codec = new ObjectMapper();
        Request request = new Request().routeMapper(codec).marked(BodyBindingMark.Kind.FORM, codec);
        List<ParameterViolation> violations = IntStream.range(0, 150)
                .mapToObj(i -> bodyViolation(path(prop("tags"), idx(i))))
                .toList();

        // When mapped
        List<ValidationErrorDetail> errors =
                mapBody(bodyMeta(BodyTags.class), violations, request, codec).errors();

        // Then every entry names its parameter, including those beyond the JSON pointer cap
        assertEquals(150, errors.size());
        for (ValidationErrorDetail err : errors) {
            assertNull(err.pointer());
            assertNotNull(err.parameter(), () -> "no parameter on " + err);
            assertEquals("form", err.location());
        }
        assertEquals(
                150,
                errors.stream().map(ValidationErrorDetail::parameter).distinct().count());
    }

    @Test
    @DisplayName("Should project the first 100 BODY violations in sorted order and leave the rest unlocated")
    void sortedOrderDecidesWhichHundredBodyViolationsGetAPointer() {
        // Given 150 blank items in the document and the violations in a shuffled order
        ObjectMapper routeMapper = new ObjectMapper();
        JsonArray items = new JsonArray();
        for (int i = 0; i < 150; i++) {
            items.add(new JsonObject().put("name", ""));
        }
        List<ParameterViolation> violations = new ArrayList<>(IntStream.range(0, 150)
                .mapToObj(i -> bodyViolation(path(prop("items"), idx(i), prop("name"))))
                .toList());
        Collections.shuffle(violations, new Random(7));
        // And the order the java paths sort in: by their rendered text
        List<Integer> sorted = IntStream.range(0, 150)
                .boxed()
                .sorted(Comparator.comparing(i -> "items[" + i + "].name"))
                .toList();

        // When mapped twice
        List<List<ValidationErrorDetail>> runs = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            Request request = new Request()
                    .body(new JsonObject().put("items", items))
                    .routeMapper(routeMapper)
                    .marked(BodyBindingMark.Kind.JSON, routeMapper);
            runs.add(mapBody(bodyMeta(BodyCart.class), violations, request, routeMapper)
                    .errors());
        }

        // Then the entries follow the sorted order, exactly the first 100 carry a pointer, on both runs
        for (List<ValidationErrorDetail> errors : runs) {
            assertEquals(150, errors.size());
            for (int rank = 0; rank < 150; rank++) {
                ValidationErrorDetail err = errors.get(rank);
                if (rank < 100) {
                    assertPointer("/items/" + sorted.get(rank) + "/name", err);
                } else {
                    assertBodyWithoutMembers(err);
                }
            }
        }
        assertEquals(runs.get(0), runs.get(1));
    }

    @Test
    @DisplayName("Should count only BODY violations toward the cap and order entries by parameter index first")
    void capCountsOnlyBodyViolationsAndParameterIndexSortsFirst() {
        // Given a query parameter at index 0, a body at index 1, 50 query violations and 120 body ones
        ObjectMapper routeMapper = new ObjectMapper();
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta("page", ResourceMethodMeta.ParamSource.QUERY, int.class),
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, BodyCart.class));
        JsonArray items = new JsonArray();
        List<ParameterViolation> violations = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            items.add(new JsonObject().put("name", ""));
            PropertyPath path = path(prop("items"), idx(i), prop("name"));
            violations.add(new ParameterViolation(
                    1, new ViolationDetail(path.toJavaPath(), "must not be blank", "required", null), path));
        }
        for (int i = 0; i < 50; i++) {
            violations.add(new ParameterViolation(0, new ViolationDetail("", "must be positive", "min", null)));
        }
        Collections.reverse(violations);
        Request request = new Request()
                .body(new JsonObject().put("items", items))
                .routeMapper(routeMapper)
                .marked(BodyBindingMark.Kind.JSON, routeMapper);

        // When mapped
        List<ValidationErrorDetail> errors =
                mapBody(meta, violations, request, routeMapper).errors();

        // Then the 50 query entries come first and are untouched by the cap
        assertEquals(170, errors.size());
        for (ValidationErrorDetail err : errors.subList(0, 50)) {
            assertEquals("query", err.location());
            assertEquals("page", err.parameter());
        }
        // And the 100 body entries after them carry a pointer, the last 20 do not
        assertEquals(
                100,
                errors.subList(50, 170).stream()
                        .filter(e -> e.pointer() != null)
                        .count());
        assertEquals(
                20,
                errors.subList(50, 170).stream()
                        .filter(e -> e.pointer() == null)
                        .count());
        for (ValidationErrorDetail err : errors.subList(50, 150)) {
            assertNotNull(err.pointer(), () -> "expected a pointer on " + err);
        }
        for (ValidationErrorDetail err : errors.subList(150, 170)) {
            assertBodyWithoutMembers(err);
        }
    }

    @Test
    @DisplayName("Should break sort ties by parameter index, constraint type, message and original position")
    void entriesFollowTheSortKeysInOrder() {
        // Given body violations on one rendered path that differ only in the later sort keys, and a
        // violation with an unknown parameter index
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta("q", ResourceMethodMeta.ParamSource.QUERY, String.class),
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, Object.class));
        List<ParameterViolation> violations = List.of(
                tagged(1, "p", "b", "msg", "first-listed-b"),
                tagged(1, "p", "a", "z", "type-a-message-z"),
                tagged(1, "p", "a", "m", "type-a-message-m-first"),
                tagged(1, "p", "a", "m", "type-a-message-m-second"),
                tagged(1, "a", "z", "z", "path-a"),
                tagged(0, "p", "z", "z", "query"),
                tagged(-1, "p", "z", "z", "unknown-index"));

        // When mapped
        List<String> order = map(meta, violations).errors().stream()
                .map(err -> (String) err.args().get("tag"))
                .toList();

        // Then parameter index sorts first (the unknown index before 0), then path, type, message and position
        assertEquals(
                List.of(
                        "unknown-index",
                        "query",
                        "path-a",
                        "type-a-message-m-first",
                        "type-a-message-m-second",
                        "type-a-message-z",
                        "first-listed-b"),
                order);
    }

    private static ParameterViolation tagged(int index, String path, String type, String message, String tag) {
        return new ParameterViolation(index, new ViolationDetail(path, message, type, Map.of("tag", tag)));
    }

    @Test
    @DisplayName("Should set at most one of pointer and parameter on every kind of body entry")
    void pointerAndParameterAreNeverBothSet() {
        // Given every outcome a body violation can take
        ObjectMapper m = new ObjectMapper();
        List<ValidationErrorDetail> all = new ArrayList<>();
        all.add(mapOneBody(
                BodyAddress.class,
                path(prop("zipCode")),
                new Request().body(zipCodeDocument()).routeMapper(m).marked(BodyBindingMark.Kind.JSON, m),
                m));
        all.add(mapOneBody(
                BodyAddress.class,
                path(prop("zipCode")),
                new Request().body(zipCodeDocument()).routeMapper(m),
                m));
        all.add(mapOneBody(
                BodyAddress.class,
                path(prop("zipCode")),
                new Request().routeMapper(m).marked(BodyBindingMark.Kind.FORM, m),
                m));
        all.add(mapOneBody(
                BodyAddress.class,
                path(prop("ZZUNBOUND")),
                new Request().routeMapper(m).marked(BodyBindingMark.Kind.FORM, m),
                m));

        // Then no entry carries both members
        assertEquals(4, all.size());
        all.forEach(ConstraintViolationMapperTest::assertAtMostOneMember);
        assertNotNull(all.get(0).pointer());
        assertNull(all.get(1).pointer());
        assertNotNull(all.get(2).parameter());
        assertNull(all.get(3).parameter());
    }

    /** Delegates to a real projection and counts the per-class bound-name lookups. */
    private static final class CountingNames implements InputFieldNameResolver {
        private final InputFieldNameResolver delegate;
        private int boundNameLookups;

        CountingNames(InputFieldNameResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public String logicalName(Class<?> ownerType, String wireName) {
            return delegate.logicalName(ownerType, wireName);
        }

        @Override
        public void precompute(Class<?> ownerType) {
            delegate.precompute(ownerType);
        }

        @Override
        public Map<String, PromotedField> promotedFields(Class<?> ownerType) {
            return delegate.promotedFields(ownerType);
        }

        @Override
        public Set<String> boundJavaNames(Class<?> ownerType) {
            boundNameLookups++;
            return delegate.boundJavaNames(ownerType);
        }

        @Override
        public Set<String> unroutableWireNames(Class<?> ownerType) {
            return delegate.unroutableWireNames(ownerType);
        }

        @Override
        public Set<Class<?>> polymorphicSubtypes(Class<?> ownerType) {
            return delegate.polymorphicSubtypes(ownerType);
        }
    }

    @Test
    @DisplayName("Should share one resolution session across a call's violations and none across calls")
    void oneResolverSessionServesTheWholeCall() {
        // Given 60 violations on elements of one list, resolved against a counting projection
        ObjectMapper routeMapper = new ObjectMapper();
        CountingNames names = new CountingNames(JacksonFieldNameResolver.forMapper(routeMapper));
        JsonArray items = new JsonArray();
        List<ParameterViolation> violations = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            items.add(new JsonObject().put("name", ""));
            violations.add(bodyViolation(path(prop("items"), idx(i), prop("name"))));
        }
        Request request = new Request()
                .body(new JsonObject().put("items", items))
                .routeMapper(routeMapper)
                .marked(BodyBindingMark.Kind.JSON, routeMapper);

        // When mapped twice
        ConstraintViolationMapper.toRestValidationException(
                bodyMeta(BodyCart.class), violations, request.ctx, names, routeMapper);
        int afterFirstCall = names.boundNameLookups;
        ConstraintViolationMapper.toRestValidationException(
                bodyMeta(BodyCart.class), violations, request.ctx, names, routeMapper);
        int afterSecondCall = names.boundNameLookups;

        // Then the session looked each owner class up once (two classes), and the next call started afresh
        assertTrue(afterFirstCall >= 1, "the projection is consulted for bound names");
        assertTrue(afterFirstCall <= 4, () -> afterFirstCall + " bound-name lookups for 60 violations of 2 classes");
        assertTrue(afterSecondCall > afterFirstCall, "a later call must not reuse the earlier session");
    }

    // --- Test bean types ---

    /** POJO bean used to test {@code @BeanParam} field reflection for location inference. */
    static class PageParamBean {
        @QueryParam("page")
        int page;

        @QueryParam("size")
        int size;
    }

    /** POJO bean whose JAX-RS names differ from its field names and whose members are collections. */
    static class CollectionParamBean {
        @QueryParam("id")
        List<Integer> ids;

        @QueryParam("addr")
        List<Object> addresses;

        @QueryParam("by")
        Map<String, Object> byKey;

        @QueryParam("code")
        Set<String> codes;

        String unmapped;
    }

    /** Record whose only member is a form list, used to observe the field cache. */
    record TagsBean(@FormParam("tag") List<String> tags) {}

    /** POJO with a blank JAX-RS name. */
    static class BlankNameBean {
        @QueryParam("")
        String blank;
    }

    /** POJO with one declared field, used to observe that undeclared names are never cached. */
    static class UncachedBean {
        @QueryParam("d")
        String declared;
    }

    /** Record used to test {@code @BeanParam} record-component reflection for location inference. */
    record PageParamRecord(
            @QueryParam("page") int page,
            @CookieParam("session") String session,
            @QueryParam("size") int size) {}
}
