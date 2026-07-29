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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import dev.vertique.rest.jaxrs.request.DefaultBoundRequest;
import io.vertx.core.MultiMap;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.RoutingContext;
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
import org.junit.jupiter.api.Nested;
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
 * <p>The stub fixtures pin the <em>state machine</em>; the {@link RealBinderSeam} nested group pins
 * that the production {@link dev.vertique.rest.jaxrs.request.DefaultBoundRequest} actually feeds it the
 * shape it expects, for each of those three sources. Both halves are load-bearing: a stub can hand the
 * extractor a shape the binder never produces.
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

        @SuppressWarnings("unused")
        public String scalar(String name) {
            return name;
        }

        @SuppressWarnings("unused")
        public String scalarInt(Integer id) {
            return String.valueOf(id);
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

    @ParameterizedTest(name = "declaredType={1}")
    @MethodSource("readOnlyShapes")
    @DisplayName("Absence with no @DefaultValue yields an empty collection for every supported interface")
    void absentCollection_withoutDefault_isEmptyForEveryShape(String methodName, Class<?> declaredType)
            throws Exception {
        Method method = CollectionResource.class.getMethod(methodName, declaredType);
        ResourceMethodMeta.ParamMeta param = paramMeta("tags", QUERY, declaredType, String.class, null);
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        Object[] args = extractor.extractArguments(null, boundRequest(QUERY, Map.of()));

        Collection<?> collection = assertInstanceOf(
                Collection.class,
                args[0],
                declaredType.getSimpleName() + " must yield an empty collection on absence, never null");
        assertTrue(declaredType.isInstance(collection), "must materialize as exactly " + declaredType.getSimpleName());
        assertTrue(collection.isEmpty(), "the collection must be empty when no values were submitted");
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

    @ParameterizedTest(name = "declaredType={1}")
    @MethodSource("readOnlyShapes")
    @DisplayName("The single-entry @DefaultValue collection is read-only, like a bound one")
    @SuppressWarnings("unchecked")
    void absentCollection_withDefault_isReadOnly(String methodName, Class<?> declaredType) throws Exception {
        Method method = CollectionResource.class.getMethod(methodName, declaredType);
        ResourceMethodMeta.ParamMeta param = paramMeta("tags", QUERY, declaredType, String.class, "x");
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        Object[] args = extractor.extractArguments(null, boundRequest(QUERY, Map.of()));

        Collection<Object> collection = (Collection<Object>) assertInstanceOf(Collection.class, args[0]);
        assertEquals(1, collection.size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> collection.add("z"),
                "the @DefaultValue path must materialize read-only, exactly as the bound-values path does");
    }

    @Test
    @DisplayName("A collection @DefaultValue is NOT policy-processed (mirrors the scalar rule)")
    void collectionDefaultValue_isNotPolicyProcessed() throws Exception {
        Method method = CollectionResource.class.getMethod("scalarAndList", String.class, List.class);
        ResourceMethodMeta.ParamMeta scalarParam = paramMeta("name", QUERY, String.class, null, null);
        ResourceMethodMeta.ParamMeta listParam = paramMeta("tags", QUERY, List.class, String.class, "x");
        ResourceMethodMeta meta =
                metaFor(method, List.of(scalarParam, listParam), List.of(MarkerCanonicalizer.class), List.of());
        ParameterExtractor extractor = extractorFor(meta, new UppercasingProcessor());

        // "name" is PRESENT, so its value proves the processor is actually wired for this route; "tags"
        // is ABSENT, so its single entry comes from @DefaultValue("x") and must bypass the chain.
        BoundRequest req = boundRequest(QUERY, Map.of("name", RequestValue.of("hi")));

        Object[] args = extractor.extractArguments(null, req);

        assertEquals("HI", args[0], "the processor must be active on this route (otherwise the test proves nothing)");
        List<?> list = assertInstanceOf(List.class, args[1]);
        assertEquals(
                List.of("x"),
                list,
                "a @DefaultValue must not traverse the input-policy chain — it is framework-supplied, not "
                        + "client-supplied, mirroring the scalar @DefaultValue rule (§4 decision 5)");
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

    // --- 3b. A present non-JsonArray value for a collection param (binder defence in depth) ---

    @ParameterizedTest(name = "source={0}")
    @MethodSource("collectionSources")
    @DisplayName("A present scalar-shaped value for a collection param materializes a single-entry collection")
    void presentScalarShapedValue_forCollectionParam_materializesSingleEntry(ResourceMethodMeta.ParamSource source)
            throws Exception {
        Method method = CollectionResource.class.getMethod("list", List.class);
        ResourceMethodMeta.ParamMeta param = paramMeta("tags", source, List.class, String.class, null);
        ResourceMethodMeta meta = metaFor(method, List.of(param));
        ParameterExtractor extractor = extractorFor(meta);

        // Deliberately NOT a JsonArray: the state machine must never be able to ask for a converter
        // targeting the COLLECTION type, whatever shape a binder hands it. The declared type is List,
        // for which no converter exists, so a fall-through to the scalar branch is a guaranteed 500.
        BoundRequest req = boundRequest(source, Map.of("tags", RequestValue.of("a")));

        Object[] args = extractor.extractArguments(null, req);

        List<?> list = assertInstanceOf(
                List.class,
                args[0],
                "componentType() != null must route through coerceCollection unconditionally, wrapping a "
                        + "single non-JsonArray value as a one-element collection");
        assertEquals(List.of("a"), list);
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

    // --- 10. Real-binder seam ---

    /**
     * Drives the same state machine through the <em>real</em> {@link DefaultBoundRequest} instead of the
     * hand-written {@link BoundRequest} stub above.
     *
     * <p>Why this group exists: the outer class's {@code boundRequest(source, values)} fixture puts a
     * {@link JsonArray} straight into the map for whichever source is under test. For {@code COOKIE}
     * that is a shape the production binder never produced — {@code DefaultBoundRequest.bindCookies}
     * called {@code wrapScalar} unconditionally, so a present collection-declared {@code @CookieParam}
     * bound as a bare {@link String}, never reached {@code coerceCollection}, and asked the resolver for
     * a converter targeting the <em>collection</em> type (there is none, so it threw
     * {@code ParamConverterNotFoundException} &rarr; HTTP 500). The stub therefore proved the
     * {@code COOKIE} presence row for the wrong reason.
     *
     * <p>These tests build the {@link ResourceMethodMeta} with the outer class's fixtures, project it
     * through the production {@link ResourceMethodMetaToDescriptorAdapter} (exactly as
     * {@code ResourceMethodInvoker} does), bind a mocked Vert.x request with the real
     * {@link DefaultBoundRequest}, and then run {@link ParameterExtractor} over it — so the binder's
     * bound shape and the state machine's expected shape are pinned together, end to end.
     */
    @Nested
    @DisplayName("The real DefaultBoundRequest feeds the state machine the shape it expects")
    class RealBinderSeam {

        /**
         * Builds a mocked {@link RoutingContext} exposing the given query/header/cookie transport
         * values and no body; any argument may be {@code null} for "nothing submitted".
         *
         * @param query   the raw query multi-map, or {@code null}
         * @param headers the raw header multi-map, or {@code null}
         * @param cookies the raw request cookies, or {@code null}
         * @return the mocked routing context
         */
        private RoutingContext mockContext(MultiMap query, MultiMap headers, Set<Cookie> cookies) {
            RoutingContext ctx = mock(RoutingContext.class);
            HttpServerRequest request = mock(HttpServerRequest.class);
            when(ctx.request()).thenReturn(request);
            when(ctx.pathParams()).thenReturn(Map.of());
            when(ctx.queryParams()).thenReturn(query != null ? query : MultiMap.caseInsensitiveMultiMap());
            when(request.headers()).thenReturn(headers != null ? headers : MultiMap.caseInsensitiveMultiMap());
            when(request.cookies()).thenReturn(cookies != null ? cookies : Set.of());
            when(ctx.body()).thenReturn(null);
            return ctx;
        }

        /**
         * Binds a real {@link DefaultBoundRequest} for {@code meta} over the given transport values,
         * projecting {@code meta} through the production descriptor adapter first.
         *
         * @param meta    the resource-method metadata whose declared params drive binding
         * @param query   the raw query multi-map, or {@code null}
         * @param headers the raw header multi-map, or {@code null}
         * @param cookies the raw request cookies, or {@code null}
         * @return the real bound request
         */
        private BoundRequest realBoundRequest(
                ResourceMethodMeta meta, MultiMap query, MultiMap headers, Set<Cookie> cookies) {
            return new DefaultBoundRequest(
                    mockContext(query, headers, cookies), ResourceMethodMetaToDescriptorAdapter.adapt(meta));
        }

        /**
         * Builds a mocked request {@link Cookie} with the given name and value.
         *
         * @param name  the cookie name
         * @param value the cookie value
         * @return the mocked cookie
         */
        private Cookie cookie(String name, String value) {
            Cookie cookie = mock(Cookie.class);
            when(cookie.getName()).thenReturn(name);
            when(cookie.getValue()).thenReturn(value);
            return cookie;
        }

        private MultiMap multiMap(String name, String... values) {
            MultiMap map = MultiMap.caseInsensitiveMultiMap();
            for (String value : values) {
                map.add(name, value);
            }
            return map;
        }

        @Test
        @DisplayName("QUERY: repeated values bound by the real binder reach coerceCollection")
        void realBinder_presentQueryCollection_materializes() throws Exception {
            Method method = CollectionResource.class.getMethod("list", List.class);
            ResourceMethodMeta meta =
                    metaFor(method, List.of(paramMeta("tags", QUERY, List.class, String.class, null)));

            BoundRequest req = realBoundRequest(meta, multiMap("tags", "a", "b"), null, null);
            Object[] args = extractorFor(meta).extractArguments(null, req);

            List<?> list = assertInstanceOf(List.class, args[0]);
            assertEquals(2, list.size(), "both repeated query values must be bound");
            assertTrue(list.containsAll(List.of("a", "b")));
        }

        @Test
        @DisplayName("HEADER: repeated values bound by the real binder reach coerceCollection")
        void realBinder_presentHeaderCollection_materializes() throws Exception {
            Method method = CollectionResource.class.getMethod("list", List.class);
            ResourceMethodMeta meta =
                    metaFor(method, List.of(paramMeta("tags", HEADER, List.class, String.class, null)));

            BoundRequest req = realBoundRequest(meta, null, multiMap("tags", "a", "b"), null);
            Object[] args = extractorFor(meta).extractArguments(null, req);

            List<?> list = assertInstanceOf(List.class, args[0]);
            assertEquals(2, list.size(), "both repeated header values must be bound");
            assertTrue(list.containsAll(List.of("a", "b")));
        }

        @Test
        @DisplayName("HEADER: a declared name whose casing differs from the wire name binds every value")
        void realBinder_presentHeaderCollection_caseMismatchedName_bindsAllValues() throws Exception {
            Method method = CollectionResource.class.getMethod("list", List.class);
            ResourceMethodMeta meta =
                    metaFor(method, List.of(paramMeta("X-Tags", HEADER, List.class, String.class, null)));

            // RFC 9113 §8.2.1 requires HTTP/2 to transmit header field names in lower case, so with ALPN
            // enabled the wire name differs from the declared one for EVERY HTTP/2 client. The
            // descriptor lookup in DefaultBoundRequest.findDescriptor must therefore match HEADER names
            // case-insensitively, exactly as ParameterExtractor.lookup already does.
            BoundRequest req = realBoundRequest(meta, null, multiMap("x-tags", "a", "b"), null);
            Object[] args = extractorFor(meta).extractArguments(null, req);

            List<?> list = assertInstanceOf(
                    List.class,
                    args[0],
                    "a case-mismatched header must still reach coerceCollection; a scalar wrap asks for a "
                            + "converter targeting List and 500s");
            assertEquals(2, list.size(), "both repeated header values must bind despite the casing difference");
            assertTrue(list.containsAll(List.of("a", "b")));
        }

        @Test
        @DisplayName("COOKIE: a declared name whose casing differs from the wire name still binds")
        void realBinder_presentCookieCollection_caseMismatchedName_binds() throws Exception {
            Method method = CollectionResource.class.getMethod("list", List.class);
            ResourceMethodMeta meta =
                    metaFor(method, List.of(paramMeta("Session-Tags", COOKIE, List.class, String.class, null)));

            BoundRequest req = realBoundRequest(meta, null, null, Set.of(cookie("session-tags", "a")));
            Object[] args = extractorFor(meta).extractArguments(null, req);

            List<?> list = assertInstanceOf(
                    List.class,
                    args[0],
                    "cookie names are bound case-insensitively, so findDescriptor must match them the same way");
            assertEquals(List.of("a"), list, "a cookie is single-valued, so the collection has exactly one entry");
        }

        @Test
        @DisplayName("QUERY: a declared name whose casing differs stays unmatched (query is case-sensitive)")
        void realBinder_presentQueryCollection_caseMismatchedName_staysAbsent() throws Exception {
            Method method = CollectionResource.class.getMethod("list", List.class);
            ResourceMethodMeta meta =
                    metaFor(method, List.of(paramMeta("Tags", QUERY, List.class, String.class, null)));

            BoundRequest req = realBoundRequest(meta, multiMap("tags", "a", "b"), null, null);
            Object[] args = extractorFor(meta).extractArguments(null, req);

            List<?> list = assertInstanceOf(List.class, args[0], "absence must yield the empty collection, not null");
            assertTrue(
                    list.isEmpty(),
                    "query parameter names are case-SENSITIVE on both halves of the lookup, so relaxing the "
                            + "header/cookie match must not leak into QUERY");
        }

        @Test
        @DisplayName("COOKIE: a present List<String> cookie yields a single-entry collection, not a 500")
        void realBinder_presentCookieList_materializesSingleEntry() throws Exception {
            Method method = CollectionResource.class.getMethod("list", List.class);
            ResourceMethodMeta meta =
                    metaFor(method, List.of(paramMeta("tags", COOKIE, List.class, String.class, null)));

            BoundRequest req = realBoundRequest(meta, null, null, Set.of(cookie("tags", "a")));
            Object[] args = extractorFor(meta).extractArguments(null, req);

            List<?> list = assertInstanceOf(
                    List.class,
                    args[0],
                    "bindCookies must bind a collection-declared cookie as a JsonArray so it reaches "
                            + "coerceCollection; wrapScalar leaves a bare String that 500s");
            assertEquals(List.of("a"), list, "a cookie is single-valued, so the collection has exactly one entry");
        }

        @Test
        @DisplayName("COOKIE: a present Set<String> cookie yields a single-entry set, not a 500")
        void realBinder_presentCookieSet_materializesSingleEntry() throws Exception {
            Method method = CollectionResource.class.getMethod("set", Set.class);
            ResourceMethodMeta meta =
                    metaFor(method, List.of(paramMeta("tags", COOKIE, Set.class, String.class, null)));

            BoundRequest req = realBoundRequest(meta, null, null, Set.of(cookie("tags", "a")));
            Object[] args = extractorFor(meta).extractArguments(null, req);

            Set<?> set = assertInstanceOf(Set.class, args[0]);
            assertEquals(Set.of("a"), set);
        }

        @Test
        @DisplayName("COOKIE: a present String[] cookie yields a single-element array, not a 500")
        void realBinder_presentCookieArray_materializesSingleElement() throws Exception {
            Method method = CollectionResource.class.getMethod("array", String[].class);
            ResourceMethodMeta meta =
                    metaFor(method, List.of(paramMeta("tags", COOKIE, String[].class, String.class, null)));

            BoundRequest req = realBoundRequest(meta, null, null, Set.of(cookie("tags", "a")));
            Object[] args = extractorFor(meta).extractArguments(null, req);

            String[] array = assertInstanceOf(String[].class, args[0]);
            assertEquals(1, array.length);
            assertEquals("a", array[0]);
        }

        @Test
        @DisplayName("COOKIE: an absent List<String> cookie still yields an empty collection")
        void realBinder_absentCookieCollection_yieldsEmptyCollection() throws Exception {
            Method method = CollectionResource.class.getMethod("list", List.class);
            ResourceMethodMeta meta =
                    metaFor(method, List.of(paramMeta("tags", COOKIE, List.class, String.class, null)));

            BoundRequest req = realBoundRequest(meta, null, null, Set.of());
            Object[] args = extractorFor(meta).extractArguments(null, req);

            List<?> list = assertInstanceOf(List.class, args[0], "absence must not regress to null");
            assertTrue(list.isEmpty());
        }

        @Test
        @DisplayName("COOKIE: a scalar String cookie still binds its raw value (non-regression)")
        void realBinder_scalarCookie_bindsRawValue() throws Exception {
            Method method = CollectionResource.class.getMethod("scalar", String.class);
            ResourceMethodMeta meta = metaFor(method, List.of(paramMeta("session", COOKIE, String.class, null, null)));

            BoundRequest req = realBoundRequest(meta, null, null, Set.of(cookie("session", "abc")));
            Object[] args = extractorFor(meta).extractArguments(null, req);

            assertEquals("abc", args[0], "scalar cookie binding must stay identical");
        }

        @Test
        @DisplayName("COOKIE: a scalar Integer cookie still coerces to its declared type (non-regression)")
        void realBinder_scalarCookie_stillCoercesToDeclaredType() throws Exception {
            Method method = CollectionResource.class.getMethod("scalarInt", Integer.class);
            ResourceMethodMeta meta = metaFor(method, List.of(paramMeta("id", COOKIE, Integer.class, null, null)));

            BoundRequest req = realBoundRequest(meta, null, null, Set.of(cookie("id", "42")));

            assertEquals(
                    42,
                    req.cookies().get("id").getInteger(),
                    "the descriptor-scoped scalar coercion must survive the wrapValues first-value fallback");

            Object[] args = extractorFor(meta).extractArguments(null, req);
            assertEquals(42, args[0]);
        }
    }
}
