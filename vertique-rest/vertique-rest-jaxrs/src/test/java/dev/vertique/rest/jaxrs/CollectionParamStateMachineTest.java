// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.COOKIE;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.HEADER;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.QUERY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.EffectiveInputPolicies;
import dev.vertique.rest.core.request.InputObjectProcessor;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for the collection parameter state machine frozen in
 * {@code docs/plans/feat-param-shape-parity.md} §4 decisions 2–5 (slice S4).
 *
 * <p>Drives the reflective {@link ParameterExtractor} directly with hand-built
 * {@link ResourceMethodMeta.ParamMeta}/{@link BoundRequest} fixtures, mirroring
 * {@link SetAndArrayQueryParamBindTest}'s style, over the three bindable sources that support
 * multi-value collections at this layer: {@code QUERY}, {@code HEADER}, and {@code COOKIE}
 * ({@code FORM} collection binding is covered separately in {@code FormParamCollectionBindTest},
 * slice S5; {@code PATH} is never multi-valued).
 *
 * <p>What these tests exist to prevent regressing — the behavior before ADR-0191 (plan §2 F7,
 * F12). Each bullet describes the OLD defect, not current behavior:
 * <ul>
 *   <li>{@code extractScalarValue} routed an absent collection's {@code @DefaultValue} through
 *       {@code coerceString}, whose conversion context used the <em>collection</em> type
 *       ({@code List.class}, etc.) as the target — no such converter is registered, so it
 *       threw {@code ParamConverterNotFoundException} (mapped to HTTP 500).</li>
 *   <li>An absent collection with no {@code @DefaultValue} returned {@code null} instead of the
 *       Jakarta REST 4.0-mandated empty collection; an absent array correctly stays {@code null}
 *       (arrays are not one of the three named collection interfaces).</li>
 *   <li>{@code coerceCollection} returned mutable {@code ArrayList}/{@code LinkedHashSet}/
 *       {@code TreeSet} instead of read-only wrappers.</li>
 *   <li>{@code coerceCollection} returned before the scalar path's {@code objectProcessor} block,
 *       so collection elements never traversed the input-policy chain that scalars already did.</li>
 * </ul>
 */
class CollectionParamStateMachineTest {

    // --- Fixture resource ---

    /** Resource exposing one method per collection/array shape under test. */
    static final class CollectionResource {
        @SuppressWarnings("unused")
        public String list(List<String> tags) {
            return String.valueOf(tags);
        }

        @SuppressWarnings("unused")
        public String set(Set<String> tags) {
            return String.valueOf(tags);
        }

        @SuppressWarnings("unused")
        public String sortedSet(SortedSet<String> tags) {
            return String.valueOf(tags);
        }

        @SuppressWarnings("unused")
        public String navigableSet(NavigableSet<String> tags) {
            return String.valueOf(tags);
        }

        @SuppressWarnings("unused")
        public String collection(Collection<String> tags) {
            return String.valueOf(tags);
        }

        @SuppressWarnings("unused")
        public String array(String[] tags) {
            return String.valueOf(tags.length);
        }

        @SuppressWarnings("unused")
        public String scalarAndList(String name, List<String> tags) {
            return name + tags;
        }
    }

    // --- Test doubles for the input-policy-chain test ---

    /** Marker {@link Canonicalizer} used only to make a route chain non-empty; never invoked directly. */
    static final class MarkerCanonicalizer implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }

    /**
     * {@link InputObjectProcessor} stub that upper-cases every {@link String} it is handed, so a call
     * observably proves whether processing happened (unlike a Mockito identity stub).
     */
    static final class UppercasingProcessor implements InputObjectProcessor {
        @Override
        public Object processStructuredBody(
                Object intermediateBody, Type targetType, EffectiveInputPolicies policies, InputLocation location) {
            if (intermediateBody instanceof String s) {
                return s.toUpperCase(Locale.ROOT);
            }
            return intermediateBody;
        }
    }

    // --- Fixture builders ---

    /**
     * Builds a {@link ResourceMethodMeta.ParamMeta} with the given collection/array shape.
     *
     * @param name          the parameter name
     * @param source        the parameter source
     * @param type          the declared parameter type (collection interface or array)
     * @param componentType the element type, or {@code null} for a scalar parameter
     * @param defaultValue  the {@code @DefaultValue} string, or {@code null}
     * @return the built {@link ResourceMethodMeta.ParamMeta}
     */
    private static ResourceMethodMeta.ParamMeta paramMeta(
            String name,
            ResourceMethodMeta.ParamSource source,
            Class<?> type,
            Class<?> componentType,
            String defaultValue) {
        return new ResourceMethodMeta.ParamMeta(
                name, source, type, componentType, null, defaultValue, (Annotation[]) null);
    }

    /**
     * Builds a {@link ResourceMethodMeta} for {@code method} with the given ordered params and empty
     * route-level input-policy chains.
     *
     * @param method the reflected resource method
     * @param params the ordered parameter metadata
     * @return the built {@link ResourceMethodMeta}
     */
    private static ResourceMethodMeta metaFor(Method method, List<ResourceMethodMeta.ParamMeta> params) {
        return metaFor(method, params, List.of(), List.of());
    }

    /**
     * Builds a {@link ResourceMethodMeta} for {@code method} with the given ordered params and
     * explicit route-level input-policy chains.
     *
     * @param method     the reflected resource method
     * @param params     the ordered parameter metadata
     * @param routeCanon the route-level canonicalizer chain
     * @param routeSanit the route-level sanitizer chain
     * @return the built {@link ResourceMethodMeta}
     */
    private static ResourceMethodMeta metaFor(
            Method method,
            List<ResourceMethodMeta.ParamMeta> params,
            List<Class<? extends Canonicalizer>> routeCanon,
            List<Class<? extends Sanitizer>> routeSanit) {
        return new ResourceMethodMeta(
                new CollectionResource(),
                method,
                method.getName(),
                "GET",
                "/things",
                params,
                String.class,
                false,
                false,
                new SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                routeCanon,
                routeSanit);
    }

    private static ParameterExtractor extractorFor(ResourceMethodMeta meta) {
        return new ParameterExtractor(meta, List.of(), new RestContextResolution(Set.of()));
    }

    private static ParameterExtractor extractorFor(ResourceMethodMeta meta, InputObjectProcessor processor) {
        return new ParameterExtractor(meta, List.of(), new RestContextResolution(Set.of()), processor);
    }

    /**
     * Builds a {@link BoundRequest} stub exposing {@code values} under the map matching
     * {@code source} (query/headers/cookies); the other two maps are empty. Path parameters and the
     * body are always empty/null — this fixture never exercises them.
     *
     * @param source the source whose map should carry {@code values}
     * @param values the values to expose for {@code source}
     * @return the built {@link BoundRequest} stub
     */
    private static BoundRequest boundRequest(ResourceMethodMeta.ParamSource source, Map<String, RequestValue> values) {
        Map<String, RequestValue> query = source == QUERY ? values : Map.of();
        Map<String, RequestValue> headers = source == HEADER ? values : Map.of();
        Map<String, RequestValue> cookies = source == COOKIE ? values : Map.of();
        return new BoundRequest() {
            @Override
            public Map<String, RequestValue> pathParameters() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> query() {
                return query;
            }

            @Override
            public Map<String, RequestValue> headers() {
                return headers;
            }

            @Override
            public Map<String, RequestValue> cookies() {
                return cookies;
            }

            @Override
            public RequestValue body() {
                return RequestValue.of(null);
            }

            @Override
            public HttpServerRequest raw() {
                return null;
            }
        };
    }

    // --- MethodSource providers ---

    private static Stream<ResourceMethodMeta.ParamSource> collectionSources() {
        return Stream.of(QUERY, HEADER, COOKIE);
    }

    private static Stream<Arguments> readOnlyShapes() {
        return Stream.of(
                Arguments.of("list", List.class),
                Arguments.of("set", Set.class),
                Arguments.of("sortedSet", SortedSet.class),
                Arguments.of("navigableSet", NavigableSet.class),
                Arguments.of("collection", Collection.class));
    }

    private static Stream<Arguments> materializationShapes() {
        return Stream.of(
                Arguments.of("set", Set.class),
                Arguments.of("sortedSet", SortedSet.class),
                Arguments.of("navigableSet", NavigableSet.class),
                Arguments.of("collection", Collection.class),
                Arguments.of("array", String[].class));
    }

    // --- 1. Absence, no default ---

    @ParameterizedTest(name = "source={0}")
    @MethodSource("collectionSources")
    @DisplayName("Absent List<String> with no @DefaultValue yields an empty, non-null collection")
    void absentCollection_withoutDefault_yieldsEmptyCollection(ResourceMethodMeta.ParamSource source) throws Exception {
        Method method = CollectionResource.class.getMethod("list", List.class);
        ResourceMethodMeta.ParamMeta param = paramMeta("tags", source, List.class, String.class, null);
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        BoundRequest req = boundRequest(source, Map.of());

        Object[] args = extractor.extractArguments(null, req);

        assertNotNull(
                args[0], "Jakarta REST 4.0: absence with no @DefaultValue must yield an empty collection, not null");
        List<?> list = assertInstanceOf(List.class, args[0]);
        assertTrue(list.isEmpty(), "the collection must be empty when no values were submitted");
    }

    // --- 2. Absence, with default ---

    @ParameterizedTest(name = "source={0}")
    @MethodSource("collectionSources")
    @DisplayName("Absent List<String> with @DefaultValue(\"x\") yields a single-entry collection")
    void absentCollection_withDefault_yieldsSingleEntry(ResourceMethodMeta.ParamSource source) throws Exception {
        Method method = CollectionResource.class.getMethod("list", List.class);
        ResourceMethodMeta.ParamMeta param = paramMeta("tags", source, List.class, String.class, "x");
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        BoundRequest req = boundRequest(source, Map.of());

        Object[] args = extractor.extractArguments(null, req);

        List<?> list = assertInstanceOf(List.class, args[0]);
        assertEquals(1, list.size(), "@DefaultValue on a collection must yield exactly one entry");
        assertEquals("x", list.get(0));
    }

    // --- 3. Presence ignores default (regression guard) ---

    @ParameterizedTest(name = "source={0}")
    @MethodSource("collectionSources")
    @DisplayName("Present List<String> values ignore @DefaultValue (regression guard)")
    void presentCollection_ignoresDefault(ResourceMethodMeta.ParamSource source) throws Exception {
        Method method = CollectionResource.class.getMethod("list", List.class);
        ResourceMethodMeta.ParamMeta param = paramMeta("tags", source, List.class, String.class, "x");
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        JsonArray bound = new JsonArray().add("a").add("b");
        BoundRequest req = boundRequest(source, Map.of("tags", RequestValue.of(bound)));

        Object[] args = extractor.extractArguments(null, req);

        List<?> list = assertInstanceOf(List.class, args[0]);
        assertEquals(2, list.size());
        assertTrue(list.contains("a"));
        assertTrue(list.contains("b"));
        assertFalse(list.contains("x"), "the default must be ignored once real values are present");
    }

    // --- 4. Absent array, no default (spec-conformant today; pinned against regression) ---

    @ParameterizedTest(name = "source={0}")
    @MethodSource("collectionSources")
    @DisplayName("Absent String[] with no @DefaultValue stays null (arrays are \"other object types\")")
    void absentArray_withoutDefault_yieldsNull(ResourceMethodMeta.ParamSource source) throws Exception {
        Method method = CollectionResource.class.getMethod("array", String[].class);
        ResourceMethodMeta.ParamMeta param = paramMeta("tags", source, String[].class, String.class, null);
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        BoundRequest req = boundRequest(source, Map.of());

        Object[] args = extractor.extractArguments(null, req);

        assertNull(
                args[0],
                "Spec: an array is not List/Set/SortedSet, so @DefaultValue's \"null for other object "
                        + "types\" rule applies to absence with no default");
    }

    // --- 5. Absent array, with default ---

    @ParameterizedTest(name = "source={0}")
    @MethodSource("collectionSources")
    @DisplayName("Absent String[] with @DefaultValue(\"x\") yields a single-element array")
    void absentArray_withDefault_yieldsSingleElementArray(ResourceMethodMeta.ParamSource source) throws Exception {
        Method method = CollectionResource.class.getMethod("array", String[].class);
        ResourceMethodMeta.ParamMeta param = paramMeta("tags", source, String[].class, String.class, "x");
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        BoundRequest req = boundRequest(source, Map.of());

        Object[] args = extractor.extractArguments(null, req);

        String[] array = assertInstanceOf(String[].class, args[0]);
        assertEquals(1, array.length, "a Vertique extension: @DefaultValue on an array yields a single element");
        assertEquals("x", array[0]);
    }

    // --- 6. Read-only materialization ---

    @ParameterizedTest(name = "declaredType={1}")
    @MethodSource("readOnlyShapes")
    @DisplayName("Bound collections are read-only per Jakarta REST 4.0")
    @SuppressWarnings("unchecked")
    void boundCollection_isReadOnly(String methodName, Class<?> declaredType) throws Exception {
        Method method = CollectionResource.class.getMethod(methodName, declaredType);
        ResourceMethodMeta.ParamMeta param = paramMeta("tags", QUERY, declaredType, String.class, null);
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        JsonArray bound = new JsonArray().add("a").add("b");
        BoundRequest req = boundRequest(QUERY, Map.of("tags", RequestValue.of(bound)));

        Object[] args = extractor.extractArguments(null, req);

        Collection<Object> collection = (Collection<Object>) assertInstanceOf(Collection.class, args[0]);
        assertThrows(
                UnsupportedOperationException.class,
                () -> collection.add("z"),
                declaredType.getSimpleName() + " parameter must be materialized read-only per Jakarta REST 4.0");
    }

    // --- 7. NavigableSet assignability trap guard ---

    @Test
    @DisplayName("NavigableSet<String> binds to an instanceof-NavigableSet, invocable value")
    void boundNavigableSet_isAssignableAndInvocable() throws Exception {
        Method method = CollectionResource.class.getMethod("navigableSet", NavigableSet.class);
        ResourceMethodMeta.ParamMeta param = paramMeta("tags", QUERY, NavigableSet.class, String.class, null);
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        JsonArray bound = new JsonArray().add("b").add("a");
        BoundRequest req = boundRequest(QUERY, Map.of("tags", RequestValue.of(bound)));

        Object[] args = extractor.extractArguments(null, req);

        NavigableSet<?> navigableSet = assertInstanceOf(NavigableSet.class, args[0]);
        assertEquals(List.of("a", "b"), List.copyOf(navigableSet), "a NavigableSet must hold sorted elements");
        assertDoesNotThrow(
                () -> method.invoke(meta.resourceInstance(), navigableSet),
                "Collections.unmodifiableSortedSet(...) returns a SortedSet that is NOT assignable to a "
                        + "NavigableSet parameter — this must use unmodifiableNavigableSet instead");
    }

    // --- 8. Declared-type materialization cascade ---

    @ParameterizedTest(name = "declaredType={1}")
    @MethodSource("materializationShapes")
    @DisplayName("Each declared collection/array shape materializes per the §4 decision 3 cascade")
    void boundCollection_declaredTypeMaterialized(String methodName, Class<?> declaredType) throws Exception {
        Method method = CollectionResource.class.getMethod(methodName, declaredType);
        ResourceMethodMeta.ParamMeta param = paramMeta("tags", QUERY, declaredType, String.class, null);
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        JsonArray bound = new JsonArray().add("b").add("a");
        BoundRequest req = boundRequest(QUERY, Map.of("tags", RequestValue.of(bound)));

        Object[] args = extractor.extractArguments(null, req);
        Object result = args[0];

        if (declaredType == Set.class) {
            Set<?> set = assertInstanceOf(Set.class, result);
            assertEquals(Set.of("a", "b"), set, "Set<T> must materialize as a Set");
        } else if (declaredType == SortedSet.class || declaredType == NavigableSet.class) {
            SortedSet<?> sorted = assertInstanceOf(SortedSet.class, result);
            assertTrue(declaredType.isInstance(sorted), "must materialize as exactly " + declaredType.getSimpleName());
            assertEquals(List.of("a", "b"), List.copyOf(sorted), "SortedSet/NavigableSet must be sorted");
        } else if (declaredType == Collection.class) {
            List<?> list = assertInstanceOf(List.class, result, "Collection<T> must materialize as a List");
            assertEquals(2, list.size());
            assertTrue(list.containsAll(List.of("a", "b")));
        } else if (declaredType == String[].class) {
            String[] array = assertInstanceOf(String[].class, result);
            assertEquals(2, array.length);
            assertEquals(Set.of("a", "b"), new HashSet<>(Arrays.asList(array)));
        }
    }

    // --- 9. Input-policy chain traversal ---

    @Test
    @DisplayName("Collection elements traverse the input-policy chain identically to scalars")
    void collectionElements_traverseInputPolicyChain() throws Exception {
        Method method = CollectionResource.class.getMethod("scalarAndList", String.class, List.class);
        ResourceMethodMeta.ParamMeta scalarParam = paramMeta("name", QUERY, String.class, null, null);
        ResourceMethodMeta.ParamMeta listParam = paramMeta("tags", QUERY, List.class, String.class, null);
        ResourceMethodMeta meta =
                metaFor(method, List.of(scalarParam, listParam), List.of(MarkerCanonicalizer.class), List.of());

        UppercasingProcessor processor = new UppercasingProcessor();
        ParameterExtractor extractor = extractorFor(meta, processor);

        JsonArray bound = new JsonArray().add("a").add("b");
        Map<String, RequestValue> query = Map.of("name", RequestValue.of("hi"), "tags", RequestValue.of(bound));
        BoundRequest req = boundRequest(QUERY, query);

        Object[] args = extractor.extractArguments(null, req);

        assertEquals(
                "HI", args[0], "scalar @QueryParam values already traverse the input-policy chain (regression guard)");

        List<?> list = assertInstanceOf(List.class, args[1]);
        assertEquals(
                List.of("A", "B"),
                list,
                "collection elements must traverse the input-policy chain identically to scalars (today: they don't)");
    }
}
