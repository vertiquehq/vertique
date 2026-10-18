// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.validation.ParameterViolation;
import dev.vertique.core.validation.PropertyPath;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.security.SecurityPolicy;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.QueryParam;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

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

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

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

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

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

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

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

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

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

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

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

        return ConstraintViolationMapper.toRestValidationException(meta, violations)
                .errors()
                .get(0);
    }

    @Test
    @DisplayName("Should render neither member for an unknown parameter index")
    void shouldFallBackForUnknownIndex() {
        ResourceMethodMeta meta = metaWithParams(
                new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, Object.class));
        List<ParameterViolation> violations =
                List.of(new ParameterViolation(-1, ViolationDetail.of("field", "invalid")));

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

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

        RestValidationException ex = ConstraintViolationMapper.toRestValidationException(meta, violations);

        assertEquals(2, ex.errors().size());
        assertEquals("path", ex.errors().get(0).location());
        assertEquals("id", ex.errors().get(0).parameter());
        assertNull(ex.errors().get(0).pointer());
        assertEquals("body", ex.errors().get(1).location());
        assertEquals("email", ex.errors().get(1).path());
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
        return ConstraintViolationMapper.toRestValidationException(metaWithParams(pm), List.of(violation))
                .errors()
                .get(0);
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
        ValidationErrorDetail unknownIndex = ConstraintViolationMapper.toRestValidationException(meta, List.of(unknown))
                .errors()
                .get(0);

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
